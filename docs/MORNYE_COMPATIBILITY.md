# Native Mornye extension support

BitChord now has a separate native compatibility layer for Mornye download-provider extensions. It keeps BitChord's Kotlin/Android application, QuickJS engine, source registry, queue and Media3 player.

## Included in this branch

- Browse the official Mornye extension registry from **Settings → Sources → Mornye extensions**.
- Install, update and remove supported download-provider packages.
- Validate `.sflx` / `.spotiflac-ext` package archives, manifest identity, network permissions and optional SHA-256 registry digests.
- Store extension packages in app-private storage and keep writable extension data in a separate directory.
- Execute `registerExtension()` packages in their own persistent QuickJS runtime.
- Provide a native host for HTTPS requests restricted to each extension's declared domains, DNS-pinned public destinations, encrypted per-extension key/value storage, credentials, and sandboxed file/download operations.
- Register installed extensions as ordinary BitChord sources. Search results become native `Song` rows; download-provider results are inspected with Android's media extractor and handed to the existing Media3 playback path as local files (or as direct stream URLs when the extension returns one).

The original BitChord addon server protocol, legacy Spine module runtime, YouTube Music source, source ordering and player are left in place.

## Compatibility limits

This is a first native compatibility implementation, not a guarantee that every package in the Mornye registry works unchanged.

- The first-party browser currently lists registry entries in the `download` category. Metadata-only and lyrics-only extensions need adapters for their corresponding native interfaces.
- OAuth login screens and Mornye signed gateway sessions are not implemented yet. Host calls for those features fail explicitly.
- Download progress callbacks are placeholders in this first adapter. Extensions that require host-to-JavaScript callback re-entry may not work.
- Advanced provider-specific decryption, patterned-block transforms, FFmpeg post-processing and DRM are not implemented. Packages requiring them are not compatible yet.
- Segmented downloads support basic segment concatenation; provider-specific manifests or encryption require dedicated implementations.
- Playback is based on a completed, validated file for download providers. Growing-file playback is not included in this first version.

## Security model

Each extension runs in its own QuickJS instance. Package files, writable files and credentials are separated per extension. The native host checks declared network domains on every request and redirect, rejects non-public DNS results, pins the checked DNS answers to the request, disables implicit redirects and limits HTTP/archive/file sizes. File paths are confined to that extension's app-private directory.

The JS bootstrap is not treated as a security boundary: permission checks live on the native side. Unsupported host operations return an error rather than a success-shaped placeholder.

## Reference implementation

Lokal Mobile's Mornye implementation was used as a compatibility reference for the package layout, registration contract and host API shape. BitChord retains its own Kotlin/QuickJS runtime and existing player architecture rather than importing Lokal's Flutter or Rust playback stack.
