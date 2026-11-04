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
