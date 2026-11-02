# BitChord Credits website

The project site follows the same visual language as the repository: a midnight-plum record sleeve, editorial liner-note typography, warm ivory copy, lavender ink and mint accents.

## Source files

- `index.html` — semantic page structure, social preview metadata and accessible controls.
- `styles.css` — responsive layout, themes, record-inspired artwork and motion preferences.
- `app.js` — latest-release lookup, device selection, checksum links and fail-closed Replace availability.
- `assets/credits-cover.svg` — original vector artwork used by both the site and repository README.

The site is static and has no framework, analytics SDK, package installation or backend requirement.

## Download safety

The page offers a specific APK only when the exact release asset exists. Universal is selected by default.

Replace remains disabled unless all four Replace APKs, the compatibility report and the release publisher's explicit `**Compatibility check: MATCH**` marker are present. This avoids relying on cross-origin reading of the report and fails closed when release data is incomplete. The currently published v1.10.8 release has a signer MISMATCH and still contains legacy Replace assets; do not install those over the official app. The publisher change in the pending PR removes those files on the next validated release refresh.

## GitHub Pages

Repository Pages must be enabled once by an administrator:

1. Open **Settings → Pages** in `heobeep/BitChord`.
2. Set **Build and deployment → Source** to **GitHub Actions**.
3. Save the setting.

The workflow `.github/workflows/pages.yml` validates the site on pull requests into `song-credits-apk`, and deploys after validated pushes to that branch.

Expected URL: [https://heobeep.github.io/BitChord/](https://heobeep.github.io/BitChord/).
