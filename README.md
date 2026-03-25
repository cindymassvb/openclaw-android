# OpenClaw Android Client

Android chat app that talks to an OpenClaw-compatible server over WebSocket, supports streaming assistant replies, attachments, local history, and model selection.

## Features
- WebSocket streaming chat (`chat.send` / `agent` / `chat` events).
- File/image attachments (base64 in-stream).
- Local-only conversation history (JSON files under app private storage).
- Device identity + Ed25519 signing; default seed is generated at runtime (no hardcoded key).
- Settings screen for server URL, API token, and model list fetched from server.

## Requirements
- Android Studio Iguana or later.
- Android Gradle Plugin 8.x (uses Gradle wrapper in repo).
- JDK 17 (bundled with Android Studio is fine).
- Min SDK 24, target/compile SDK 34.

## Quick start (developer)
```bash
./gradlew assembleDebug
# or install to device
./gradlew installDebug
```

## App configuration (runtime)
1. Launch app -> Settings.
2. Fill:
   - **Server URL**: e.g. `https://your-openclaw-server.example.com/`
   - **API Token** (optional, sent as `?token=` and in connect auth).
   - **Model**: pick from the fetched list or type manually.
3. Save and return to main screen to chat.

### Device key
- `HARDCODED_SEED` is intentionally empty to avoid shipping a static private key.
- On first run the app generates an Ed25519 keypair, saves base64 in `SharedPreferences`, and derives `deviceId` as SHA-256(publicKey).
- If you *must* pin a device ID, you can set `HARDCODED_SEED` (base64url, 32 bytes) in `ApiClient.java`; do this only for internal builds.

## Data & logging
- Conversations are stored locally in app private storage: `getFilesDir()/convs/{id}.json`.
- Nothing is uploaded unless your server logs requests/responses; the client itself does not sync history.

## Code map
- `app/src/main/java/com/openclaw/app/ApiClient.java` — WebSocket logic, signing, retries.
- `MainActivity` — chat UI, streaming updates, attachments.
- `ConversationStore` — local history persistence.
- `SettingsActivity` — server/model configuration, device ID display.
- `ChatForegroundService` — keeps process alive during streaming.

## Build variants
- **debug**: no minify/shrink.
- **release**: R8 shrink + ProGuard rules in `proguard-rules.pro`.

## Troubleshooting
- Cannot connect: verify server URL includes scheme; check token; server must speak the same protocol version (min/max 3).
- Pairing required: server may enforce pairing on `connect` challenge; see server logs.
- Attachments over 10 MB are rejected client-side.

## Contributing
- Fork & PRs welcome. Keep line endings LF in commits; repo may show CRLF warnings on Windows but Git will normalize.
- Please avoid committing secrets (tokens, seeds, keystores).

## License
Not specified yet. If you plan to distribute, add an OSI license (e.g., MIT/Apache-2.0).

## Security & compliance notes (please review before public release)
- TLS trust: current `ApiClient` installs a trust-all `X509TrustManager` and disables hostname verification to ease self-signed testing. For production, switch back to system trust anchors and enable hostname checks to avoid MITM.
- Cleartext: `network_security_config` only permits HTTP for private LAN ranges (10.x/192.168.x/localhost); public domains remain HTTPS-only. Keep it that way for production.
- Secrets: `HARDCODED_SEED` is intentionally empty; do not commit real seeds or API tokens. Prefer runtime config or env/remote config.
- Privacy: the client stores history locally and does not upload it. If your server logs conversations, disclose that in your privacy policy and comply with data retention/consent requirements.
- Third-party licenses: include notices for OkHttp, Markwon, BouncyCastle, AndroidX (Apache-2.0/MIT family). Add a project license (MIT/Apache-2.0 recommended) before public release or app store submission.
- Store listing/DSR: if publishing (e.g., Play Store), provide a Privacy Policy URL and fill Data Safety accordingly (collects text input if you log server-side).
