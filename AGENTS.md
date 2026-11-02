# Repository agent guidance

Before changing Android packages, signing, build automation or release publishing, read the relevant profile under `.github/agents/`.

## BitChord Credits distribution rules

- Keep Original is the default recommendation. It must use a package ID distinct from `com.music.bitchord` and must leave an installed official BitChord untouched.
- Replace must use exactly `com.music.bitchord`. Never publish Replace APKs unless the CI compatibility report says `MATCH` for the official release checked during that build.
- A mismatch or unknown result means publish Keep Original only. Never ask users to uninstall their existing app just to try Credits.
- Supported phone ABIs are `arm64-v8a`, `armeabi-v7a`, and `x86_64`. Universal must contain all three. If a user does not know their device type, recommend Universal.
- Keep phone and Android TV packages separate. Do not advertise phone builds as TV builds.
- Never commit a signing key, password, API key or other secret. Use the existing GitHub Actions secret and verify the permanent signing fingerprint before upload.
- Keep versionCode strictly increasing.
- Permanent releases must be created only by `.github/workflows/publish-credits-release.yml` after a successful build from the current `song-credits-apk` branch head. Stale builds must never publish.
- The publisher must verify four Keep APKs and either four Replace APKs on an exact MATCH, or zero Replace APKs otherwise. Generate SHA-256 files for every published asset.
- CI success is not a claim of physical-device testing. Do not describe an APK as device-tested unless a real device test was performed and recorded.
- Update `DOWNLOADS.md`, release notes and compatibility guidance whenever the install matrix changes.
