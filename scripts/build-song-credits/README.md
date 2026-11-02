# BitChord Credits build

This branch builds two phone install modes from one source tree:

- **Keep original:** package com.music.bitchord.songcredits.benchmark, so it can coexist with the official com.music.bitchord app and with older Credits installs.
- **Replace original:** package com.music.bitchord, built with the persistent Credits signing key. CI compares that certificate and versionCode with the latest official upstream APK. The generated compatibility report determines whether it can update in place or requires uninstalling the original first.

Each mode is built as four installable APKs: universal, arm64-v8a, armeabi-v7a, and x86_64. Universal includes all three supported ABIs and is the recommended choice when a user does not know their device architecture.

The build runs shared metadata tests and Credits app unit tests, checks package IDs and signing fingerprints for every APK, checks the official signing certificate, calculates compatibility, and uploads all APKs plus DOWNLOADS.md and INSTALL-COMPATIBILITY.txt.

Workflow: Actions → Build BitChord Credits APK. Successful runs are published automatically by publish-credits-release.yml, provided the run still corresponds to the current branch head.
