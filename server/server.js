// Shelf review server: Google sign-in, ratings and reviews for the apps in the Shelf catalog.
//
// Env:
//   PORT                  default 5191
//   TURSO_DATABASE_URL    libsql://... (default: local SQLite file data/shelf.db)
//   TURSO_AUTH_TOKEN
//   GOOGLE_CLIENT_IDS     comma-separated OAuth client IDs whose ID tokens are accepted (the Web client ID)
//   ADMIN_TOKEN           secret for the developer endpoints (reading all feedback, replying)
//   CATALOG_URL           Shelf index.json; reviews are only accepted for apps listed there
//   DEV_LOGIN=1           local testing only: allows signing in without Google
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { createClient } = require('@libsql/client');

const PORT = Number(process.env.PORT || 5191);
const CLIENT_IDS = (process.env.GOOGLE_CLIENT_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
const DEV_LOGIN = process.env.DEV_LOGIN === '1';
const CATALOG_URL = process.env.CATALOG_URL || 'https://raw.githubusercontent.com/akshays6325/shelf/main/index.json';
const SESSION_DAYS = 180;

fs.mkdirSync(path.join(__dirname, 'data'), { recursive: true });
const DB_URL = process.env.TURSO_DATABASE_URL || pathToFileURL(path.join(__dirname, 'data', 'shelf.db')).href;
const db = createClient({ url: DB_URL, authToken: process.env.TURSO_AUTH_TOKEN });
const q = async (sql, args = []) => (await db.execute({ sql, args })).rows;

async function migrate() {
  await db.batch([
    `CREATE TABLE IF NOT EXISTS users (
       id INTEGER PRIMARY KEY,
       google_sub TEXT UNIQUE NOT NULL,
       email TEXT NOT NULL,
       name TEXT NOT NULL,
       picture TEXT,
       created_at INTEGER NOT NULL,
       last_seen_at INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS sessions (
       token_hash TEXT PRIMARY KEY,
       user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
       expires_at INTEGER NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS reviews (
       id INTEGER PRIMARY KEY,
       app_id TEXT NOT NULL,
       user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
       rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
       body TEXT NOT NULL DEFAULT '',
       version_name TEXT,
       version_code INTEGER,
       device TEXT,
       reply TEXT,
       reply_at INTEGER,
       created_at INTEGER NOT NULL,
       updated_at INTEGER NOT NULL,
       UNIQUE (app_id, user_id))`,
    'CREATE INDEX IF NOT EXISTS reviews_app ON reviews (app_id, updated_at DESC)',
  ], 'write');
}

// ---- Google ID token verification (RS256 against Google's published keys) ---------
let jwks = { keys: [], expires: 0 };
async function googleKeys() {
  if (Date.now() < jwks.expires) return jwks.keys;
  const res = await fetch('https://www.googleapis.com/oauth2/v3/certs');
  if (!res.ok) throw new Error('Could not fetch Google keys');
  const maxAge = Number((res.headers.get('cache-control') || '').match(/max-age=(\d+)/)?.[1] || 3600);
  jwks = { keys: (await res.json()).keys, expires: Date.now() + maxAge * 1000 };
  return jwks.keys;
}

async function verifyGoogleIdToken(idToken) {
  const parts = String(idToken || '').split('.');
  if (parts.length !== 3) throw httpError(401, 'Invalid sign-in token');
  const [h, p, s] = parts;
  let header, payload;
  try {
    header = JSON.parse(Buffer.from(h, 'base64url'));
    payload = JSON.parse(Buffer.from(p, 'base64url'));
  } catch {
    throw httpError(401, 'Invalid sign-in token');
  }
  const jwk = (await googleKeys()).find((k) => k.kid === header.kid);
  if (!jwk || header.alg !== 'RS256') throw httpError(401, 'Invalid sign-in token');
  const ok = crypto.verify('RSA-SHA256', Buffer.from(`${h}.${p}`),
    crypto.createPublicKey({ key: jwk, format: 'jwk' }), Buffer.from(s, 'base64url'));
  if (!ok) throw httpError(401, 'Invalid sign-in token');
  if (!['accounts.google.com', 'https://accounts.google.com'].includes(payload.iss)) throw httpError(401, 'Wrong token issuer');
  if (!CLIENT_IDS.includes(payload.aud)) throw httpError(401, 'Token is for another app');
  if (payload.exp * 1000 < Date.now()) throw httpError(401, 'Sign-in expired, try again');
  if (!payload.email_verified) throw httpError(401, 'Google account email is not verified');
  return payload;
}

// ---- helpers ---------------------------------------------------------------------
function httpError(status, message) { return Object.assign(new Error(message), { status }); }
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const now = () => Date.now();

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(json);
}

async function readJson(req) {
  let size = 0; const chunks = [];
  for await (const c of req) { size += c.length; if (size > 64 * 1024) throw httpError(413, 'Too large'); chunks.push(c); }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw httpError(400, 'Invalid JSON'); }
}

function cleanText(v, max) {
  return String(v ?? '').replace(/\r\n/g, '\n').replace(/[\u0000-\u0008\u000b-\u001f]/g, '').trim().slice(0, max);
}

const hits = new Map();
function rateLimit(key, max, windowMs) {
  const t = now(); const list = (hits.get(key) || []).filter((x) => t - x < windowMs);
  if (list.length >= max) throw httpError(429, 'Slow down a little and try again later.');
  list.push(t); hits.set(key, list);
}

async function startSession(userId) {
  const token = crypto.randomBytes(32).toString('base64url');
  await q('INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)',
    [sha256(token), userId, now() + SESSION_DAYS * 864e5]);
  return token;
}

async function currentUser(req) {
  const m = (req.headers.authorization || '').match(/^Bearer (\S+)$/);
  if (!m) return null;
  const [u] = await q(`SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
                       WHERE s.token_hash = ? AND s.expires_at > ?`, [sha256(m[1]), now()]);
  if (u && now() - Number(u.last_seen_at) > 3600e3) await q('UPDATE users SET last_seen_at = ? WHERE id = ?', [now(), u.id]);
  return u || null;
}
async function requireUser(req) {
  const u = await currentUser(req);
  if (!u) throw httpError(401, 'Sign in first');
  return u;
}
function requireAdmin(req) {
  const t = req.headers['x-admin-token'] || '';
  if (!ADMIN_TOKEN || t.length !== ADMIN_TOKEN.length || !crypto.timingSafeEqual(Buffer.from(t), Buffer.from(ADMIN_TOKEN))) {
    throw httpError(403, 'Forbidden');
  }
}

const publicUser = (u) => ({ id: Number(u.id), name: u.name, email: u.email, picture: u.picture || null });

// Apps listed in the catalog, refreshed every 10 minutes (reviews for other ids are refused).
let catalog = { ids: null, at: 0 };
async function knownApp(id) {
  if (!/^[a-z0-9-]{1,40}$/.test(id)) return false;
  if (now() - catalog.at > 600e3) {
    try {
      const r = await fetch(CATALOG_URL, { signal: AbortSignal.timeout(5000) });
      if (r.ok) catalog = { ids: new Set((await r.json()).apps.map((a) => a.id)), at: now() };
    } catch { /* keep the previous list */ }
  }
  return !catalog.ids || catalog.ids.has(id);
}

function toReview(r, me) {
  return {
    id: Number(r.id),
    appId: r.app_id,
    rating: Number(r.rating),
    body: r.body,
    versionName: r.version_name,
    author: { name: r.name, picture: r.picture || null },
    mine: me ? Number(r.user_id) === Number(me.id) : false,
    reply: r.reply || null,
    replyAt: r.reply_at ? new Date(Number(r.reply_at)).toISOString() : null,
    createdAt: new Date(Number(r.created_at)).toISOString(),
    updatedAt: new Date(Number(r.updated_at)).toISOString(),
  };
}

async function summary(appId) {
  const rows = await q('SELECT rating, COUNT(*) AS n FROM reviews WHERE app_id = ? GROUP BY rating', [appId]);
  const histogram = [0, 0, 0, 0, 0];
  let total = 0, sum = 0;
  for (const r of rows) { histogram[Number(r.rating) - 1] = Number(r.n); total += Number(r.n); sum += Number(r.n) * Number(r.rating); }
  return { average: total ? Math.round((sum / total) * 10) / 10 : 0, count: total, histogram };
}

// ---- routes ----------------------------------------------------------------------
const routes = [];
const route = (method, pattern, fn) => routes.push({ method, re: new RegExp(`^${pattern}$`), fn });

route('GET', '/healthz', async () => ({ ok: true }));
route('GET', '/api/config', async () => ({ devLogin: DEV_LOGIN, googleSignIn: CLIENT_IDS.length > 0 }));

route('POST', '/api/auth/google', async (req, _m, body) => {
  rateLimit(`auth:${req.socket.remoteAddress}`, 30, 15 * 60e3);
  const g = await verifyGoogleIdToken(body.idToken);
  const t = now();
  await q(`INSERT INTO users (google_sub, email, name, picture, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT (google_sub) DO UPDATE SET email = excluded.email, name = excluded.name,
             picture = excluded.picture, last_seen_at = excluded.last_seen_at`,
    [g.sub, g.email, cleanText(g.name || g.email.split('@')[0], 80), g.picture || null, t, t]);
  const [u] = await q('SELECT * FROM users WHERE google_sub = ?', [g.sub]);
  return { token: await startSession(u.id), user: publicUser(u) };
});

route('POST', '/api/auth/dev', async (_req, _m, body) => {
  if (!DEV_LOGIN) throw httpError(404, 'Not found');
  const email = cleanText(body.email || 'dev@example.com', 120);
  const t = now();
  await q(`INSERT INTO users (google_sub, email, name, picture, created_at, last_seen_at) VALUES (?, ?, ?, NULL, ?, ?)
           ON CONFLICT (google_sub) DO UPDATE SET name = excluded.name`,
    [`dev:${email}`, email, cleanText(body.name || 'Dev User', 80), t, t]);
  const [u] = await q('SELECT * FROM users WHERE google_sub = ?', [`dev:${email}`]);
  return { token: await startSession(u.id), user: publicUser(u) };
});

route('POST', '/api/auth/logout', async (req) => {
  const m = (req.headers.authorization || '').match(/^Bearer (\S+)$/);
  if (m) await q('DELETE FROM sessions WHERE token_hash = ?', [sha256(m[1])]);
  return { ok: true };
});

route('GET', '/api/me', async (req) => {
  const u = await requireUser(req);
  const rows = await q(`SELECT r.*, u.name, u.picture FROM reviews r JOIN users u ON u.id = r.user_id
                        WHERE r.user_id = ? ORDER BY r.updated_at DESC`, [u.id]);
  return { user: publicUser(u), reviews: rows.map((r) => toReview(r, u)) };
});

route('DELETE', '/api/me', async (req) => {
  const u = await requireUser(req);
  await q('DELETE FROM reviews WHERE user_id = ?', [u.id]);
  await q('DELETE FROM sessions WHERE user_id = ?', [u.id]);
  await q('DELETE FROM users WHERE id = ?', [u.id]);
  return { ok: true };
});

route('GET', '/api/ratings', async () => {
  const rows = await q('SELECT app_id, AVG(rating) AS avg, COUNT(*) AS n FROM reviews GROUP BY app_id');
  return { apps: Object.fromEntries(rows.map((r) => [r.app_id, { average: Math.round(Number(r.avg) * 10) / 10, count: Number(r.n) }])) };
});

route('GET', '/api/apps/([a-z0-9-]+)/reviews', async (req, m) => {
  const me = await currentUser(req);
  const rows = await q(`SELECT r.*, u.name, u.picture FROM reviews r JOIN users u ON u.id = r.user_id
                        WHERE r.app_id = ? ORDER BY (r.user_id = ?) DESC, r.updated_at DESC LIMIT 200`,
    [m[1], me ? me.id : -1]);
  return { ...(await summary(m[1])), reviews: rows.map((r) => toReview(r, me)) };
});

route('PUT', '/api/apps/([a-z0-9-]+)/review', async (req, m, body) => {
  const u = await requireUser(req);
  rateLimit(`review:${u.id}`, 30, 3600e3);
  if (!(await knownApp(m[1]))) throw httpError(404, 'Unknown app');
  const rating = Number(body.rating);
  if (!Number.isInteger(rating) || rating < 1 || rating > 5) throw httpError(400, 'Pick 1 to 5 stars');
  const t = now();
  await q(`INSERT INTO reviews (app_id, user_id, rating, body, version_name, version_code, device, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT (app_id, user_id) DO UPDATE SET rating = excluded.rating, body = excluded.body,
             version_name = excluded.version_name, version_code = excluded.version_code,
             device = excluded.device, updated_at = excluded.updated_at`,
    [m[1], u.id, rating, cleanText(body.body, 2000), cleanText(body.versionName, 40) || null,
      Number.isFinite(Number(body.versionCode)) ? Number(body.versionCode) : null, cleanText(body.device, 120) || null, t, t]);
  const [r] = await q(`SELECT r.*, u.name, u.picture FROM reviews r JOIN users u ON u.id = r.user_id
                       WHERE r.app_id = ? AND r.user_id = ?`, [m[1], u.id]);
  return { review: toReview(r, u), ...(await summary(m[1])) };
});

route('DELETE', '/api/apps/([a-z0-9-]+)/review', async (req, m) => {
  const u = await requireUser(req);
  await q('DELETE FROM reviews WHERE app_id = ? AND user_id = ?', [m[1], u.id]);
  return { ok: true, ...(await summary(m[1])) };
});

// Developer endpoints (X-Admin-Token)
route('GET', '/api/admin/reviews', async (req) => {
  requireAdmin(req);
  const url = new URL(req.url, 'http://x');
  const since = Number(url.searchParams.get('since') || 0);
  const app = url.searchParams.get('app');
  const rows = await q(`SELECT r.*, u.name, u.email, u.picture FROM reviews r JOIN users u ON u.id = r.user_id
                        WHERE r.updated_at > ? AND (? IS NULL OR r.app_id = ?) ORDER BY r.updated_at DESC LIMIT 500`,
    [since, app, app]);
  return {
    reviews: rows.map((r) => ({ ...toReview(r, null), email: r.email, versionCode: r.version_code, device: r.device })),
    users: Number((await q('SELECT COUNT(*) AS n FROM users'))[0].n),
  };
});

route('POST', '/api/admin/reviews/(\\d+)/reply', async (req, m, body) => {
  requireAdmin(req);
  const reply = cleanText(body.reply, 2000);
  await q('UPDATE reviews SET reply = ?, reply_at = ? WHERE id = ?', [reply || null, reply ? now() : null, Number(m[1])]);
  return { ok: true };
});

// ---- server ----------------------------------------------------------------------
const server = http.createServer(async (req, res) => {
  try {
    const { pathname } = new URL(req.url, 'http://x');
    for (const r of routes) {
      const m = r.method === req.method && pathname.match(r.re);
      if (!m) continue;
      const body = ['POST', 'PUT'].includes(req.method) ? await readJson(req) : {};
      return send(res, 200, await r.fn(req, m, body));
    }
    send(res, 404, { error: 'Not found' });
  } catch (e) {
    if (!e.status) console.error(e);
    send(res, e.status || 500, { error: e.status ? e.message : 'Something went wrong' });
  }
});

migrate().then(() => {
  server.listen(PORT, () => console.log(`Shelf reviews on http://localhost:${PORT}${DEV_LOGIN ? ' (dev sign-in enabled)' : ''}`));
  setInterval(() => q('DELETE FROM sessions WHERE expires_at < ?', [now()]).catch(() => {}), 6 * 3600e3).unref();
}).catch((e) => { console.error('Database setup failed:', e); process.exit(1); });
