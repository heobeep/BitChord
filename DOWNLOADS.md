# BitChord Credits downloads

**Not sure what your phone's CPU architecture is? Choose the Universal APK.** It contains the supported Android builds together, so you do not need to know what ARM means.

> [!CAUTION]
> **Important for the current v1.10.8 release:** its `INSTALL-COMPATIBILITY.txt` report says **MISMATCH**. The attached Replace APKs cannot update the official BitChord v1.9 app in place. Do not install them over your existing official app. Choose **Keep Original → Universal** to leave the existing app and its data untouched.
>
> For future releases, Replace APKs will be published only when the automatic check confirms an in-place update is compatible. Otherwise those downloads will be withheld.

## 1. Choose how you want BitChord Credits installed

### Keep original BitChord — recommended

- Installs **BitChord Credits** as a separate app.
- Your original BitChord remains installed and untouched.
- You can keep both apps on the same phone.
- The launcher name is **BitChord Credits**.

Choose one of the APKs whose name begins with `BitChord-Credits-Keep-`.

### Replace original BitChord — only when the report says MATCH

A Replace build uses the original package ID, so Android treats it as the same app.

**Only use a Replace APK when `INSTALL-COMPATIBILITY.txt` says MATCH.** That means the official APK checked during the build has the same signing certificate and a lower version number, allowing Android to update it without uninstalling the original.

- **MATCH:** the build is compatible with the exact official release checked during this build. The update should keep the app's private data.
- **MISMATCH:** Android will reject an in-place update. Uninstalling the original first may erase its private settings, account sessions, offline cache and other app data. We do not recommend this route.
- **UNVERIFIED:** compatibility could not be confirmed. Treat it as unsafe and do not try to install it over the original.

Replace APKs are withheld from future public releases unless the report says MATCH. Do not force an install past an Android signature warning.

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
- Want to replace BitChord? Use **Replace** only when the compatibility report says **MATCH**. The current v1.10.8 release does not pass this check.

Each published APK has a matching SHA-256 checksum file beside it. The checksum can help verify that the download arrived intact.
