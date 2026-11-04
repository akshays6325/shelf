#!/usr/bin/env node
// Reads ratings and reviews from the Shelf review server, and replies to them.
//
//   node feedback.mjs                      all reviews, newest first
//   node feedback.mjs cleantube            reviews for one app
//   node feedback.mjs --new                only reviews changed since the last --new run
//   node feedback.mjs --json               raw JSON (for scripts)
//   node feedback.mjs --reply 12 "Fixed in 1.4, thanks!"
//
// Needs shelf/.env (not committed) with:
//   SHELF_REVIEWS_URL=https://shelf-reviews.onrender.com
//   SHELF_ADMIN_TOKEN=<ADMIN_TOKEN from Render>
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const envFile = path.join(ROOT, '.env');
const env = { ...process.env };
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
    if (m && !(m[1] in process.env)) env[m[1]] = m[2];
  }
}
const BASE = (env.SHELF_REVIEWS_URL || '').replace(/\/+$/, '');
const TOKEN = env.SHELF_ADMIN_TOKEN || '';
if (!BASE || !TOKEN) {
  console.error('Set SHELF_REVIEWS_URL and SHELF_ADMIN_TOKEN in shelf/.env');
  process.exit(1);
}

const args = process.argv.slice(2);
const headers = { 'X-Admin-Token': TOKEN, 'Content-Type': 'application/json' };

async function api(method, p, body) {
  const r = await fetch(BASE + p, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(90000) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}

const ri = args.indexOf('--reply');
if (ri >= 0) {
  const id = args[ri + 1], text = args[ri + 2];
  if (!id || text === undefined) { console.error('Usage: --reply <review-id> "text" (empty text removes the reply)'); process.exit(1); }
  await api('POST', `/api/admin/reviews/${id}/reply`, { reply: text });
  console.log(`Replied to review #${id}.`);
  process.exit(0);
}

const stateFile = path.join(ROOT, '.feedback-seen');
const onlyNew = args.includes('--new');
const since = onlyNew && fs.existsSync(stateFile) ? Number(fs.readFileSync(stateFile, 'utf8')) : 0;
const app = args.find((a) => !a.startsWith('--'));
const qs = new URLSearchParams({ since: String(since), ...(app ? { app } : {}) });
const { reviews, users } = await api('GET', `/api/admin/reviews?${qs}`);
if (onlyNew) fs.writeFileSync(stateFile, String(Date.now()));

if (args.includes('--json')) { console.log(JSON.stringify(reviews, null, 2)); process.exit(0); }

const stars = (n) => '★'.repeat(n) + '☆'.repeat(5 - n);
console.log(`${reviews.length} review(s)${onlyNew ? ' since last check' : ''}${app ? ` for ${app}` : ''} · ${users} user(s) signed up\n`);
for (const r of reviews) {
  console.log(`#${r.id}  ${r.appId}  ${stars(r.rating)}  ${r.author.name} <${r.email}>  ${r.updatedAt.slice(0, 16).replace('T', ' ')}`);
  console.log(`     v${r.versionName ?? '?'}${r.device ? ` · ${r.device}` : ''}`);
  if (r.body) console.log(r.body.split('\n').map((l) => `     ${l}`).join('\n'));
  if (r.reply) console.log(`     ↳ reply: ${r.reply}`);
  console.log();
}
