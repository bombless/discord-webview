# Discord WebView

A developer-oriented Android WebView shell with a single hot-swappable JavaScript entrypoint.

## Architecture

- Android Java hosts a WebView and a loopback-only TCP/HTTP script server.
- All app UI and interaction logic lives in one file: `app.js`.
- Each accepted script update is syntax-checked, stored as a version snapshot, then applied to the WebView.
- The desktop editor is served by the same server.

## Requirements

- JDK 17+
- Android SDK with API 36
- Gradle Wrapper 9.1.0

## Build

The included bootstrap launcher downloads and runs Gradle 9.1.0 (requires curl and unzip):

```bash
gradle wrapper --gradle-version 9.1.0
sh gradlew assembleDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.bombless.discordwebview/.MainActivity
```

## Desktop live editing

Forward the device's loopback port:

```bash
adb forward tcp:8765 tcp:8765
```

Open **http://127.0.0.1:8765/** on your PC. Edit the single `app.js` script and press **Apply**.

The editor also exposes a JSON API:

- `GET /api/state` — active version and server status
- `GET /api/script` — current script
- `PUT /api/script` — validate, save a new version, and apply
- `GET /api/versions` — version history
- `POST /api/rollback/{versionId}` — restore a prior version

Example:

```bash
curl http://127.0.0.1:8765/api/state
curl -X PUT http://127.0.0.1:8765/api/script \
  -H 'Content-Type: application/javascript' \
  --data-binary @app.js
```

## Safety

The server binds to `127.0.0.1` only. Use ADB port forwarding; do not expose this development endpoint to an untrusted network. The endpoint intentionally has no authentication because it is loopback-only. Do not put Discord user tokens or other secrets in scripts. This is a development shell, not an official Discord client or an implementation of Discord's private APIs.
