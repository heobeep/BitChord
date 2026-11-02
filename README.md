<div align="center">

<br/>

<img src="Logo.png" alt="BitChord Credits app icon" width="150" />

# BitChord Credits

### Your music, with the people behind it.

An independent BitChord community fork that brings **song credits into the listening experience** — so you can look beyond the track title and discover the people who helped make the music.

<br/>

[![Latest release](https://img.shields.io/github/v/release/heobeep/BitChord?style=for-the-badge&labelColor=171923)](https://github.com/heobeep/BitChord/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/heobeep/BitChord/total?style=for-the-badge&labelColor=171923)](https://github.com/heobeep/BitChord/releases)
[![License](https://img.shields.io/github/license/heobeep/BitChord?style=for-the-badge&labelColor=171923)](LICENSE)

<br/>

[**Download**](#-download) · [**What’s different**](#-whats-different) · [**Setup**](#-getting-started) · [**FAQ**](#-faq) · [**Credits & license**](#-credits--license)

<br/>

<img src="Banner.png" alt="BitChord Credits" width="100%" />

</div>

> [!NOTE]
> **This is an independent community fork of [BitChord](https://github.com/kushagrasinghx/BitChord), not the official upstream app.** It keeps the BitChord music-player foundation and adds a native song-credits experience.

---

## ✨ What’s different

### Song credits, built into the app

- **Native credits view** — open a track’s song information and explore the people credited on it.
- **Credits.fm integration** — connect an optional Credits.fm API key from the app’s account and integrations settings.
- **A setting that stays out of your way** — turn song credits on or off from Appearance.
- **Metadata fallback** — supporting metadata can be looked up through the available public music metadata sources. Credits depend on what those catalogues have for a track, so some songs may have limited or no results.

### The BitChord experience is still here

BitChord Credits is built on BitChord, so the fork retains the music-player foundation and its existing features, including:

- YouTube Music search, browsing and playback.
- Playlists, library, local music and offline downloads.
- Lyrics, artwork and dynamic theming.
- Last.fm and ListenBrainz scrobbling.
- Playback controls, queue tools, sleep timer and audio settings.

The exact feature set can evolve with the upstream project and this fork.

---

## 📥 Download

**[Open the download guide](DOWNLOADS.md)** before choosing an APK. It separates the install choice (keep the original or replace it) from the device choice, and explains when an in-place update is actually compatible.

**Not sure what ARM is?** Choose **Keep Original → Universal** from the latest release. It is the easiest option and does not replace your existing BitChord installation.

[![Get the latest BitChord Credits builds](https://img.shields.io/badge/Download-BitChord%20Credits-6e56cf?style=for-the-badge&logo=github)](https://github.com/heobeep/BitChord/releases/latest)

Every release includes **Keep Original** builds for Universal, ARM64, 32-bit ARM and x86_64 phones. **Replace** builds are published only when the automatic check confirms they can update the checked official app without uninstalling it. Important: the current v1.10.8 compatibility report says **MISMATCH**. Do not install its Replace APK over the official BitChord app. Use **Keep Original → Universal** instead.

## 🎼 How song credits work

1. Play a track and open its **Song Info** screen.
2. Open the credits section to see the available contributor information.
3. Add a Credits.fm API key in **Settings → Account & Integrations** when you want to use your own key.
4. Use the **Song credits** option in **Settings → Appearance** to control the feature.

A configured ListenBrainz integration may also be used for supporting metadata lookup. Its token is not sent to Credits.fm. You do not need to configure every integration just to use the player.

**Coverage varies.** Credits are supplied by external music metadata services; missing credits do not necessarily mean a track has no credited contributors.

---

## 🔐 Privacy & data

- API keys and integration settings are handled by the app’s existing account/settings infrastructure.
- Your ListenBrainz token is not forwarded to Credits.fm.
- Song-credit lookups require network access and may send track-identifying metadata (such as title and artist) to the relevant metadata provider.
- BitChord Credits does not host a catalogue of music files.

Use only API credentials you are entitled to use, and review the policies of the external services you connect.

---

## ❔ FAQ

**Will this replace my existing BitChord installation?**  
Not when you choose **Keep Original** — it installs separately and leaves official BitChord untouched. A **Replace** APK is published only when the release's INSTALL-COMPATIBILITY report says **MATCH**. The current v1.10.8 report says **MISMATCH**, so do not use its Replace APK over the official app. Uninstalling the original first can erase its private app data.

**Do I need a Credits.fm API key?**  
The integration supports an optional key. Some metadata may be available through public sources, but results and coverage can vary.

**Why are some tracks missing credits?**  
External catalogues do not have complete contributor data for every recording. Try another version of the track or check again later.

**Which APK should I choose?**  
Choose **Universal** when you do not know your device architecture. Choose **arm64-v8a** for most modern Android phones, **armeabi-v7a** for older 32-bit ARM devices, or **x86_64** for emulators and x86_64 Chromebooks. These are phone/tablet builds; Android TV is separate.

**Where can I get the original BitChord?**  
Visit the [upstream BitChord repository](https://github.com/kushagrasinghx/BitChord).

---

## 🛠️ Development & contributions

This repository contains the BitChord Credits fork and its build workflow. The automated Android build is defined in [`.github/workflows/song-credits-apk.yml`](.github/workflows/song-credits-apk.yml); the native credits patch is applied by [`scripts/build-song-credits/apply.py`](scripts/build-song-credits/apply.py).

Bug reports and suggestions for the Credits-specific functionality are welcome through [GitHub Issues](https://github.com/heobeep/BitChord/issues). Please include your Android version, device architecture, app version, and steps to reproduce the issue. Do not post API keys, passwords, or private account data.

---

## 🙏 Credits & license

BitChord Credits is built on the work of the original [BitChord project](https://github.com/kushagrasinghx/BitChord) and its contributors. Thanks to the upstream developers and to the open music-metadata services that make richer credits possible.

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE) for the full terms. Source changes and redistributed builds must comply with that license.

<div align="center">

**Independent project · Not affiliated with or endorsed by YouTube, Google, Credits.fm, or the upstream BitChord maintainers.**

</div>
