# BitChord Credits build

The BitChord Credits phone builds are produced from the current `main` branch. A pull-request build checks changes before they are merged; a push to `main` that changes the app, shared code, native libraries, Wear OS app, build settings or Credits build scripts starts the release build.

Two phone install modes are prepared:

- **Keep original:** a separate BitChord Credits app that can coexist with the official BitChord installation.
- **Replace original:** uses the official package ID. The workflow checks the actual official APK's signing certificate and version before it can be offered as an in-place update. If the result is not `MATCH`, the public release workflow withholds those Replace downloads.

Each mode is built as four installable APKs: universal, arm64-v8a, armeabi-v7a, and x86_64. Universal contains all three supported ABIs and is the recommended choice when a user does not know their device architecture.

The build runs shared metadata tests and Credits app unit tests, checks package IDs and signing fingerprints for every APK, verifies native-library contents, checks the official signing certificate, and uploads the APKs plus installation guidance and the compatibility report.

The publisher runs only for a successful push build from `main`, skips stale commits, and creates a permanent GitHub Release with SHA-256 checksum files. Pull-request builds are checks only and never publish downloads.
