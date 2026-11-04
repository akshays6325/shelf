#!/usr/bin/env node
// Builds, tests and publishes apps to Shelf.
//
//   node publish.mjs cleantube                 bump version, build, smoke-test on the emulator, publish
//   node publish.mjs cleantube splitly         several at once ("all" for every app)
//   node publish.mjs cleantube --notes "..."   set the "What's new" text (default: commit messages since last release)
//   node publish.mjs cleantube --dry-run       everything except uploading
//   node publish.mjs cleantube --local         publish to local-feed/ instead of GitHub (for testing Shelf)
//   node publish.mjs --list                    show what's published
//
// Flags: --no-bump (use the version in source as is), --skip-test.
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(fs.readFileSync(path.join(ROOT, 'apps.json'), 'utf8'));
const SDK = process.env.ANDROID_HOME || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk');
const BT = path.join(SDK, 'build-tools', '34.0.0');
const ADB = path.join(SDK, 'platform-tools', 'adb.exe');
const BASH = ['C:\\Program Files\\Git\\bin\\bash.exe', 'C:\\Program Files\\Git\\usr\\bin\\bash.exe'].find(fs.existsSync) || 'bash';

// ---- args -----------------------------------------------------------------
const args = process.argv.slice(2);
const flag = (f) => args.includes(f);
const opt = (f) => { const i = args.indexOf(f); return i >= 0 ? args[i + 1] : undefined; };
const notesArg = opt('--notes');
const ids = args.filter((a, i) => !a.startsWith('--') && args[i - 1] !== '--notes');
const LOCAL = flag('--local');
const DRY = flag('--dry-run');
const FEED_DIR = LOCAL ? path.join(ROOT, 'local-feed') : ROOT;
const INDEX = path.join(FEED_DIR, 'index.json');

const log = (...m) => console.log(...m);
const step = (m) => console.log(`\n\x1b[36m▸ ${m}\x1b[0m`);
const fail = (m) => { console.error(`\n\x1b[31m✗ ${m}\x1b[0m`); process.exit(1); };

function run(file, argv, opts = {}) {
  return execFileSync(file, argv, { encoding: 'utf8', maxBuffer: 64 << 20, ...opts });
}
function tryRun(file, argv, opts = {}) {
  const r = spawnSync(file, argv, { encoding: 'utf8', maxBuffer: 64 << 20, ...opts });
  return { ok: r.status === 0, out: (r.stdout || '') + (r.stderr || '') };
}

function loadIndex() {
  if (!fs.existsSync(INDEX)) return { apps: [] };
  return JSON.parse(fs.readFileSync(INDEX, 'utf8'));
}

// ---- version handling -----------------------------------------------------
function readVersion(app) {
  const file = path.join(ROOT, app.dir, app.version.file);
  const src = fs.readFileSync(file, 'utf8');
  const code = src.match(new RegExp(app.version.code));
  const name = src.match(new RegExp(app.version.name));
  if (!code || !name) fail(`${app.id}: couldn't find the version in ${app.version.file}`);
  return { file, src, code: Number(code[1]), name: name[1] };
}

function bumpName(name) {
  const m = name.match(/^(.*?)(\d+)(\D*)$/);
  return m ? `${m[1]}${Number(m[2]) + 1}${m[3]}` : `${name}.1`;
}

function writeVersion(app, v, code, name) {
  const replaceGroup = (src, re, value) => src.replace(new RegExp(re), (whole, g1) => whole.replace(g1, value));
  let src = replaceGroup(v.src, app.version.code, String(code));
  src = replaceGroup(src, app.version.name, name);
  fs.writeFileSync(v.file, src);
}

// ---- APK inspection -------------------------------------------------------
function badging(apk) {
  const out = run(path.join(BT, 'aapt2.exe'), ['dump', 'badging', apk]);
  const pkg = out.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/m);
  const icons = [...out.matchAll(/^application-icon-(\d+):'([^']+)'/gm)]
    .map((m) => ({ density: Number(m[1]), file: m[2] }))
    .filter((i) => i.file.endsWith('.png') && i.density < 65534)
    .sort((a, b) => b.density - a.density);
  return { packageName: pkg[1], versionCode: Number(pkg[2]), versionName: pkg[3], icon: icons[0]?.file };
}

function certSha256(apk) {
  const out = run('cmd.exe', ['/c', path.join(BT, 'apksigner.bat'), 'verify', '--print-certs', apk]);
  const m = out.match(/Signer #1 certificate SHA-256 digest: ([0-9a-f]+)/);
  if (!m) fail(`APK isn't signed: ${apk}`);
  return m[1];
}

function extractIcon(apk, entry, dest) {
  // unzip comes with Git Bash; Windows' own tar.exe reads zips too.
  let r = spawnSync('unzip', ['-p', apk, entry], { maxBuffer: 16 << 20 });
  if (r.status !== 0 || !r.stdout?.length) r = spawnSync(path.join(process.env.SystemRoot || 'C:/Windows', 'System32', 'tar.exe'), ['-xOf', apk, entry], { maxBuffer: 16 << 20 });
  if (r.status === 0 && r.stdout?.length > 0) {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, r.stdout);
    return true;
  }
  return false;
}

// ---- emulator smoke test --------------------------------------------------
function emulator() {
  if (!fs.existsSync(ADB)) return null;
  const out = tryRun(ADB, ['devices']).out;
  return out.split('\n').map((l) => l.trim().split(/\s+/)).find(([s, st]) => s?.startsWith('emulator-') && st === 'device')?.[0] || null;
}

function smokeTest(apk, pkg) {
  const dev = emulator();
  if (!dev) { log('  No emulator running: skipping the smoke test.'); return; }
  const adb = (...a) => tryRun(ADB, ['-s', dev, ...a]);
  log(`  Installing on ${dev}...`);
  let r = adb('install', '-r', '-d', apk);
  if (!r.ok && /UPDATE_INCOMPATIBLE|signatures do not match/i.test(r.out)) {
    // Only ever on an emulator: reinstall from scratch when an old copy has another key.
    log('  Emulator has a copy signed with another key; reinstalling.');
    adb('uninstall', pkg);
    r = adb('install', apk);
  }
  if (!r.ok) fail(`Install on the emulator failed:\n${r.out}`);
  adb('logcat', '-c');
  adb('shell', 'am', 'force-stop', pkg);
  const launch = adb('shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1');
  if (!launch.ok || /No activities found/.test(launch.out)) fail(`Couldn't launch ${pkg}:\n${launch.out}`);
  log('  Launched; watching for crashes for 8 s...');
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 8000);
  const crash = adb('logcat', '-d', '-b', 'crash').out;
  const alive = adb('shell', 'pidof', pkg).out.trim();
  if (crash.includes(pkg) || !alive) {
    const tail = crash.split('\n').filter(Boolean).slice(0, 25).join('\n');
    fail(`${pkg} crashed on launch.\n${tail || '(process not running, no crash log)'}`);
  }
  log('  ✓ Starts and keeps running.');
}

// ---- changelog ------------------------------------------------------------
function gitIn(dir, ...a) {
  const r = tryRun('git', ['-C', dir, ...a]);
  return r.ok ? r.out.trim() : null;
}
function changelog(app, prev) {
  if (notesArg) return { text: notesArg, commit: gitIn(path.join(ROOT, app.dir), 'rev-parse', 'HEAD') };
  const dir = path.join(ROOT, app.dir);
  const head = gitIn(dir, 'rev-parse', 'HEAD');
  if (!head) return { text: 'Improvements and fixes.', commit: null };
  const range = prev?.commit && gitIn(dir, 'cat-file', '-e', prev.commit) !== null ? `${prev.commit}..HEAD` : '-5';
  const subjects = gitIn(dir, 'log', range, '--format=%s', '--', '.')?.split('\n').filter(Boolean) ?? [];
  const text = subjects.length ? subjects.map((s) => `• ${s}`).join('\n') : 'Improvements and fixes.';
  return { text, commit: head };
}

// ---- publish one app ------------------------------------------------------
function publish(app, index) {
  console.log(`\n\x1b[1m━━ ${app.name} ━━\x1b[0m`);
  const prev = index.apps.find((a) => a.id === app.id);
  const dir = path.join(ROOT, app.dir);

  step('Version');
  const v = readVersion(app);
  let code = v.code, name = v.name;
  if (prev && code <= prev.versionCode) {
    if (flag('--no-bump')) fail(`versionCode ${code} isn't newer than the published ${prev.versionCode}.`);
    code = prev.versionCode + 1;
    if (name === prev.versionName) name = bumpName(prev.versionName);
    writeVersion(app, v, code, name);
    log(`  Bumped ${v.name} (${v.code}) → ${name} (${code}) in ${app.version.file}`);
  } else {
    log(`  ${name} (${code})${prev ? `, published: ${prev.versionName} (${prev.versionCode})` : ', first release'}`);
  }

  step('Build');
  const b = spawnSync(BASH, ['-c', app.build], { cwd: dir, stdio: 'inherit' });
  if (b.status !== 0) fail(`Build failed (${app.build} in ${app.dir}).`);
  const apk = path.join(dir, app.apk);
  const info = badging(apk);
  if (info.versionCode !== code) fail(`Built APK has versionCode ${info.versionCode}, expected ${code}.`);
  if (prev && info.packageName !== prev.packageName) fail(`Package changed: ${prev.packageName} → ${info.packageName}`);
  const cert = certSha256(apk);
  if (prev?.certSha256 && prev.certSha256 !== cert) {
    fail(`Signing key changed! Published copies use ${prev.certSha256.slice(0, 16)}…, this build uses ${cert.slice(0, 16)}….\n` +
      `Phones would refuse the update. Restore the original signing/ folder for ${app.id}.`);
  }
  const bytes = fs.readFileSync(apk);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  log(`  ${info.packageName} ${info.versionName} (${info.versionCode}), ${(bytes.length / 1048576).toFixed(1)} MB`);

  step('Test');
  if (flag('--skip-test')) log('  Skipped (--skip-test).');
  else smokeTest(apk, info.packageName);

  const asset = `${app.id}-${info.versionName}.apk`;
  const tag = `${app.id}-v${info.versionCode}`;
  const notes = changelog(app, prev);
  const iconRel = `icons/${app.id}.png`;
  const hasIcon = info.icon && extractIcon(apk, info.icon, path.join(FEED_DIR, iconRel));

  if (DRY) { step('Dry run: not publishing'); log(notes.text); return null; }

  step('Upload');
  let apkUrl;
  if (LOCAL) {
    fs.mkdirSync(path.join(FEED_DIR, 'apks'), { recursive: true });
    fs.copyFileSync(apk, path.join(FEED_DIR, 'apks', asset));
    apkUrl = `apks/${asset}`;
    log(`  Copied to local-feed/${apkUrl}`);
  } else {
    const tmp = fs.mkdtempSync(path.join(process.env.TEMP || '/tmp', 'shelf-'));
    const tmpApk = path.join(tmp, asset);
    fs.copyFileSync(apk, tmpApk);
    const r = tryRun('gh', ['release', 'create', tag, tmpApk, '--repo', config.repo,
      '--title', `${app.name} ${info.versionName}`, '--notes', notes.text]);
    fs.rmSync(tmp, { recursive: true, force: true });
    if (!r.ok) fail(`GitHub release failed:\n${r.out}`);
    apkUrl = `https://github.com/${config.repo}/releases/download/${tag}/${asset}`;
    log(`  ${apkUrl}`);
  }

  return {
    id: app.id,
    name: app.name,
    packageName: info.packageName,
    description: app.description,
    versionCode: info.versionCode,
    versionName: info.versionName,
    apkUrl,
    sha256,
    size: bytes.length,
    iconUrl: hasIcon ? iconRel : prev?.iconUrl ?? '',
    changelog: notes.text,
    updatedAt: new Date().toISOString(),
    certSha256: cert,
    commit: notes.commit,
  };
}

// ---- main -----------------------------------------------------------------
if (flag('--list') || ids.length === 0) {
  const index = loadIndex();
  console.log(`Published (${LOCAL ? 'local-feed' : config.repo}):`);
  for (const a of config.apps) {
    const p = index.apps.find((x) => x.id === a.id);
    console.log(`  ${a.id.padEnd(16)} ${p ? `${p.versionName} (${p.versionCode})  ${p.updatedAt.slice(0, 16).replace('T', ' ')}` : '—'}`);
  }
  if (ids.length === 0 && !flag('--list')) console.log('\nUsage: node publish.mjs <app-id ...|all> [--notes "..."] [--dry-run] [--local]');
  process.exit(0);
}

const selected = ids.includes('all') ? config.apps : ids.map((id) => config.apps.find((a) => a.id === id) || fail(`Unknown app "${id}". Known: ${config.apps.map((a) => a.id).join(', ')}`));
const index = loadIndex();
const published = [];
for (const app of selected) {
  const entry = publish(app, index);
  if (!entry) continue;
  index.apps = [...index.apps.filter((a) => a.id !== entry.id), entry]
    .sort((a, b) => config.apps.findIndex((x) => x.id === a.id) - config.apps.findIndex((x) => x.id === b.id));
  index.updated = entry.updatedAt;
  fs.mkdirSync(FEED_DIR, { recursive: true });
  fs.writeFileSync(INDEX, JSON.stringify(index, null, 2) + '\n');
  published.push(`${entry.name} ${entry.versionName}`);
}

if (published.length && !LOCAL) {
  step('Update catalog');
  const g = (...a) => { const r = tryRun('git', ['-C', ROOT, ...a]); if (!r.ok) fail(`git ${a[0]} failed:\n${r.out}`); return r.out; };
  g('add', 'index.json', 'icons');
  g('commit', '-m', `Publish ${published.join(', ')}`);
  g('push', 'origin', 'HEAD');
  log('  Pushed index.json. Phones pick it up on their next check (open Shelf to check now).');
}
if (published.length) console.log(`\n\x1b[32m✓ Published ${published.join(', ')}\x1b[0m`);
