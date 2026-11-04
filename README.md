# Shelf

A private app store for my own Android apps. The Shelf app reads `index.json` from this repo,
installs apps from their GitHub releases, and keeps them updated.

## On the phone

1. Download the latest `shelf-*.apk` from [Releases](../../releases) and open it.
2. Allow Shelf to install apps when it asks (once).
3. Install apps from the list. Android asks to confirm each first install.

After that, Shelf checks every 15 minutes (and whenever you open it). On Android 12+ apps installed
through Shelf update silently in the background; otherwise you get a notification.
Apps you installed earlier some other way update after one confirmation, then silently from then on.

## Publishing (on the PC)

```bash
node publish.mjs cleantube              # bump version, build, smoke-test on the emulator, release, update catalog
node publish.mjs cleantube --notes "Fixed captions"
node publish.mjs all                    # every app
node publish.mjs --list                 # what's published
node publish.mjs cleantube --dry-run    # everything except uploading
```

Apps are configured in `apps.json` (source folder, build command, APK path, where the version lives).
`publish.mjs` refuses to publish an APK signed with a different key than the last release, since phones
would reject it. Each app's `signing/` folder holds its key: keep backups.

### Testing Shelf itself against a local catalog

```bash
node publish.mjs <app> --local --skip-test   # writes local-feed/
node tools/serve.mjs                         # serves it on :8080
```

Then in Shelf → Settings set the catalog URL to `http://10.0.2.2:8080/index.json` (emulator).

## Accounts, ratings and reviews

`server/` is a small review server (Node + Turso, deployed on Render from `render.yaml`).
People sign in with Google in Shelf, rate apps 1–5 stars, write reviews and see developer replies.

Read feedback and reply (needs `shelf/.env` with `SHELF_REVIEWS_URL` and `SHELF_ADMIN_TOKEN`):

```bash
node feedback.mjs              # everything
node feedback.mjs --new        # only what changed since last time
node feedback.mjs cleantube    # one app
node feedback.mjs --reply 12 "Fixed in 1.4, thanks!"
```

Local testing: `cd server && DEV_LOGIN=1 ADMIN_TOKEN=test node server.js`, then in Shelf → Settings set
Reviews server to `http://10.0.2.2:5191` and use "Test sign-in".

### One-time setup

1. **Turso**: create a database `shelf`; note its URL and an auth token.
2. **Google Cloud** (the CleanTube project is fine):
   - OAuth consent screen → *Publish app* (basic sign-in scopes need no review), so friends can sign in.
   - Credentials → OAuth client ID → **Web application** ("Shelf server"): copy the client ID.
   - Credentials → OAuth client ID → **Android**: package `com.personal.shelf`,
     SHA-1 `2C:21:CD:39:11:8A:0C:F7:38:40:40:D1:0D:E1:47:1F:B8:56:F3:07`.
3. **Render**: New → Blueprint → this repo. Fill `TURSO_DATABASE_URL`, `TURSO_AUTH_TOKEN`,
   `GOOGLE_CLIENT_IDS` (the Web client ID). `ADMIN_TOKEN` is generated: put it in `shelf/.env`.
4. Set `REVIEWS_URL` and `GOOGLE_WEB_CLIENT_ID` in `android/app/build.gradle.kts`, then `node publish.mjs shelf`.
