# Repository agent guidance

Before changing Android packages, signing, or release automation, read the relevant profile under `.github/agents/`.

## BitChord Credits distribution invariants

- The Keep install uses the dedicated Credits package and must never replace official BitChord.
- The Replace install uses exactly `com.music.bitchord`; do not claim it can update an installed original unless CI verifies the same signing certificate and a newer versionCode.
- Supported phone ABIs are `arm64-v8a`, `armeabi-v7a`, and `x86_64`. A Universal APK must contain native libraries for all three. If a user does not know their ABI, recommend Universal.
- Keep phone and Android TV packages separate.
- Never commit a signing key, password, API key, or other secret. Use the existing GitHub Actions secrets and verify the permanent signing fingerprint before upload.
- Keep versionCode strictly increasing.
- Release only from the current `song-credits-apk` branch head; stale build runs must not publish.
- Update `DOWNLOADS.md`, release assets, and installation compatibility notes whenever the install matrix changes.
- CI success is not a claim of physical-device testing.
