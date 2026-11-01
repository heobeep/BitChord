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

<div align="center">

### **BitChord Credits v1.9 · Android ARM64**

[![Get it on GitHub Releases](https://img.shields.io/badge/GET%20THE%20APK-GitHub%20Releases-6e56cf?style=for-the-badge&logo=github)](https://github.com/heobeep/BitChord/releases/latest)

**Current package:** optimized ARM64 phone APK · approximately 83 MB

</div>

Download the APK from the **Assets** section of the latest release. Choose the file ending in `arm64-v8a.apk`.

> [!IMPORTANT]
> The published APK is for **64-bit ARM Android devices** (the common architecture on modern Android phones). It is not a universal APK and is not intended for x86 devices or 32-bit-only ARM devices.

### Getting started

1. Open the [latest release](https://github.com/heobeep/BitChord/releases/latest).
2. Download the **BitChord Credits ARM64 APK** from the release assets.
3. When Android asks, allow your browser or file manager to install unknown apps.
4. Open the downloaded APK and follow Android’s install prompt.

**Keep your original BitChord:** the Credits build uses a separate application ID, so it is designed to install alongside the original app rather than replace it.

### A note about this build

The current APK is an optimized CI build signed with a GitHub Actions debug signing key. It is suitable for sideloading, but it is **not signed with a permanent public release key**. Because CI debug keys can change between build runs, a future APK may not install as an in-place update over this one; Android may require the previous Credits build to be uninstalled first. Uninstalling an app can remove its local app data.

The build workflow completed its shared metadata tests and verified that it produced exactly one ARM64 APK. It has not yet been validated on a broad range of physical devices.

---

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
No. The Credits build is packaged as a separate app so it can coexist with the original BitChord.

**Do I need a Credits.fm API key?**  
The integration supports an optional key. Some metadata may be available through public sources, but results and coverage can vary.

**Why are some tracks missing credits?**  
External catalogues do not have complete contributor data for every recording. Try another version of the track or check again later.

**Which devices are supported by the current APK?**  
The current release asset is ARM64-only and is intended for modern 64-bit ARM Android phones. See the release notes for the exact build details.

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
