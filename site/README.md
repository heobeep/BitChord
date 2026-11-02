# BitChord Credits website

This is a dependency-free static site. It is intentionally separate from the Android app and does not need Node, a bundler, analytics, or a backend.

## Files

- `index.html` — page structure and accessible controls.
- `styles.css` — responsive theme and layout.
- `app.js` — release lookup, device picker, checksum link and fail-closed Replace gate.

## Publish with GitHub Pages

The repository currently has GitHub Pages disabled. A repository admin must enable it once:

1. Open **Settings → Pages** in `heobeep/BitChord`.
2. Under **Build and deployment**, set **Source** to **GitHub Actions**.
3. Save. The Pages workflow deploys this folder after changes are merged to `song-credits-apk`.

The workflow is `.github/workflows/pages.yml`. It validates the required files, uploads only this folder and deploys it. It does not run for pull requests.

Expected project-site URL: [https://heobeep.github.io/BitChord/](https://heobeep.github.io/BitChord/).

## Download safety

The page never invents APK names or download URLs. It reads the latest public GitHub release and offers a file only when the exact release asset exists.

Replace remains disabled unless **all four Replace APKs**, the compatibility report, and the publisher-written `Compatibility check: MATCH` release-note marker are present. This is intentionally fail-closed. Keep Original → Universal remains the default recommendation.

The only browser preference stored locally is the visitor's light/dark theme choice.
