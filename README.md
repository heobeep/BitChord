<div align="center">

<br>

<img src="Logo.png" alt="BitChord Credits icon" width="112">

# BitChord Credits

### Discover the people behind the music.

An independent community fork of **[BitChord](https://github.com/kushagrasinghx/BitChord)** with a native song-credits experience — bringing performers, musicians, writers, producers and more into the listening experience.

<br>

[![APK build](https://img.shields.io/github/actions/workflow/status/heobeep/BitChord/song-credits-apk.yml?branch=song-credits-apk&style=for-the-badge&label=APK%20build)](https://github.com/heobeep/BitChord/actions/workflows/song-credits-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/heobeep/BitChord?style=for-the-badge)](https://github.com/heobeep/BitChord/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/heobeep/BitChord/total?style=for-the-badge)](https://github.com/heobeep/BitChord/releases)
[![License](https://img.shields.io/github/license/heobeep/BitChord?style=for-the-badge)](LICENSE)

<br>

[**Interactive download guide ↗**](https://heobeep.github.io/BitChord/) · [**Latest releases**](https://github.com/heobeep/BitChord/releases) · [**Source code**](https://github.com/heobeep/BitChord)

<br>

<img src="Banner.png" alt="BitChord Credits banner" width="100%">

</div>

> [!NOTE]
> **BitChord Credits is an independent community fork, not the official upstream app.** It builds on the BitChord music-player project and adds a native song-credits experience. It is not affiliated with or endorsed by the upstream maintainers.

---

## Start here

**Just want to install it?** Open the [interactive download guide](https://heobeep.github.io/BitChord/). It checks the latest published release, lets you choose an install mode and device, and only offers downloads that actually exist in that release.

**Not sure what ARM means?** Choose **Keep Original → Universal**. It is the easiest option and installs separately from your existing BitChord.

### Choose your install

| Option | What happens | Recommendation |
|---|---|---|
| **Keep Original** | Installs BitChord Credits as a separate app. Your official BitChord stays installed and untouched. | **Recommended** |
| **Replace Original** | Uses the original package ID and can update the official app only when the compatibility report confirms an in-place update is safe. | Use only on an exact **MATCH** |

Replace downloads are deliberately withheld when compatibility is a mismatch or cannot be verified. Android checks the app's signing certificate as well as its package and version. Never force an install past a signature warning or uninstall your original app just to try this fork; uninstalling can erase private app data.

### Choose your device build

| Build | Intended device | Choose it when… |
|---|---|---|
| **Universal** | ARM64, older 32-bit ARM and x86_64 Android devices | You are unsure. This is the default recommendation. |
| **arm64-v8a** | Most modern Android phones and tablets | You know your device is 64-bit ARM and want a smaller download. |
| **armeabi-v7a** | Older 32-bit ARM Android devices | You know your device uses 32-bit ARM. |
| **x86_64** | Android emulators and x86_64 Chromebooks | You are using an x86_64 Android environment. |

These are phone/tablet builds. Android TV has its own separate app and release path.

---

## What makes Credits different?

### Native song credits

Open a track's **Song Info** and explore the contributor information available for that recording. Depending on the catalogue, this can include performers, musicians, songwriters, composers, producers, arrangers, engineers, publishers and labels.

### Optional metadata integrations

- **Credits.fm** — connect an optional API key through the app's account and integrations settings.
- **Supporting music metadata** — public music metadata sources can help enrich the information available for a track.
- **ListenBrainz** — a connected integration may be used for supporting metadata lookup. Its token is not forwarded to Credits.fm.

Credits depend on what external catalogues have for each recording. Missing results do not mean a track has no credited contributors.

### The BitChord foundation stays familiar

The fork retains the wider music-player foundation, including YouTube Music search and playback, playlists, library, local music, lyrics, artwork, dynamic theming, Last.fm and ListenBrainz scrobbling, queue tools, sleep timer and audio settings. The exact feature set can evolve with upstream and this fork.

---

## Privacy & data

- Song-credit lookups require network access and may send track-identifying metadata, such as title and artist, to the relevant metadata provider.
- Your ListenBrainz token is not forwarded to Credits.fm.
- API keys and integration settings are handled through the app's existing account/settings infrastructure.
- BitChord Credits does not host a catalogue of music files.

Use only credentials you are entitled to use and review the policies of the external services you connect.

---

## Frequently asked questions

<details>
<summary><strong>Will BitChord Credits replace my existing BitChord?</strong></summary>

Not when you choose **Keep Original**. It installs separately and leaves the official BitChord app and its data in place. Replace is offered only when the release compatibility report confirms an exact MATCH for the official APK checked by CI.

</details>

<details>
<summary><strong>Why might Replace be missing from a release?</strong></summary>

The official app and the Credits build must have a compatible package ID, signing certificate and version. If any check fails or cannot be completed, Replace files are removed from the release. This protects users from an Android signature rejection or a forced uninstall.

</details>

<details>
<summary><strong>Do I need a Credits.fm API key?</strong></summary>

No. The integration supports an optional key. Some supporting metadata may be available through public sources, but results and coverage can vary.

</details>

<details>
<summary><strong>Why do some songs have no credits?</strong></summary>

External music catalogues are incomplete. Try another recording/version of the track or check again later.

</details>

<details>
<summary><strong>Is BitChord Credits the official BitChord app?</strong></summary>

No. It is an independent community fork based on [upstream BitChord](https://github.com/kushagrasinghx/BitChord).

</details>

---

## For contributors

This repository contains the Android app, shared modules, backend, separate TV and Wear OS components, web app, and the Credits release tooling.

| Area | Location |
|---|---|
| Android app | `app/`, `shared/`, `sharedUi/` |
| Android TV | `tv/` |
| Wear OS | `wearos/` |
| Web app | `web/` |
| Backend | `backend/` |
| Credits patch and build helpers | `scripts/build-song-credits/` |
| Android APK workflow | [`.github/workflows/song-credits-apk.yml`](.github/workflows/song-credits-apk.yml) |
| Permanent release publisher | [`.github/workflows/publish-credits-release.yml`](.github/workflows/publish-credits-release.yml) |
| Interactive project site | `site/` |

Bug reports and Credits-specific suggestions can be raised through [GitHub Issues](https://github.com/heobeep/BitChord/issues) when issue tracking is enabled. Include your Android version, device type, app version and steps to reproduce. **Never post API keys, passwords, signing material or private account data.**

Before opening a pull request, read [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md). Do not claim physical-device testing from CI results alone.

---

## Credits & license

BitChord Credits builds on the work of the original [BitChord project](https://github.com/kushagrasinghx/BitChord) and its contributors. Thank you to the upstream developers and the open music-metadata services that make richer credits possible.

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE) for the full terms. Source changes and redistributed builds must comply with that license.

<div align="center">

**Independent project · Not affiliated with or endorsed by YouTube, Google, Credits.fm or the upstream BitChord maintainers.**

</div>
