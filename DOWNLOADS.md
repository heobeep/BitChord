# BitChord Credits downloads

**Not sure what your phone's CPU architecture is? Choose the Universal APK.** It contains the supported Android builds together, so you do not need to know what ARM means.

## 1. Choose how you want BitChord Credits installed

### Keep original BitChord — recommended

- Installs **BitChord Credits** as a separate app.
- Your original BitChord remains installed and untouched.
- You can keep both apps on the same phone.
- The launcher name is **BitChord Credits**.

Choose one of the APKs whose name begins with BitChord-Credits-Keep-.

### Replace original BitChord

This build uses the original package ID, com.music.bitchord, so Android sees it as the same app.

**Check INSTALL-COMPATIBILITY.txt before installing it.**

- **MATCH:** the replacement was signed with the same certificate as the official upstream APK checked during this build, and has a higher versionCode. It should install as an update over that checked release and keep the app's data.
- **MISMATCH:** Android will reject an install over the official app. To switch, uninstall original BitChord first, then install the Replace APK. Uninstalling an app can erase its private settings, account sessions, offline cache and other app data.
- **UNVERIFIED:** the official signature could not be checked. Treat this as a mismatch and uninstall first.

The compatibility result applies to the official release checked during this build. An unofficial, beta or newer installation can have a different signature or higher versionCode. Android's installer is the final authority; never force an update over a signature warning.

Choose one of the APKs whose name begins with BitChord-Credits-Replace-.

## 2. Choose a device build

| File ending | Intended device | Which should I choose? |
|---|---|---|
| Universal APK (no ABI suffix; Keep ends in `-universal.apk`, Replace in `_universal.apk`) | ARM64 phones, older 32-bit ARM devices, and x86_64 Android devices | **Choose this if unsure.** It is larger but includes all supported ABIs. |
| `_arm64-v8a.apk` | Most modern Android phones and tablets | Best smaller download for most recent phones. |
| `_armeabi-v7a.apk` | Older 32-bit ARM phones | Use only for older 32-bit ARM devices. |
| `_x86_64.apk` | Android emulators and x86_64 Chromebooks | Use for x86_64 devices, not ordinary ARM phones. |

All APKs in this release target Android 8.0 (API 26) or newer. These are **phone/tablet builds**; Android TV is a separate app and is not part of this download set.

## Quick recommendation

- Want to keep your existing BitChord? **Keep → Universal**.
- Want the smallest download on a modern phone? **Keep → arm64-v8a**.
- Want to replace BitChord? Check INSTALL-COMPATIBILITY.txt first, then choose **Replace**.

Each APK is signed with the persistent BitChord Credits signing key. The SHA-256 checksum files beside the APKs can be used to verify the downloads.
