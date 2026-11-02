# Native Mornye extension compatibility

This document describes the BitChord-native compatibility layer being introduced
on the `feat/mornye-native-extension-runtime` branch.

## Scope of this first slice

- Parse the public Mornye `registry.json` format.
- Validate and install ZIP-based `.sflx` / `.spotiflac-ext` packages.
- Keep package extraction inside app-private storage and enforce compressed,
  expanded, entry-count and script-size limits.
- Verify registry-provided SHA-256 digests and package identity/version.
- Run one extension per persistent QuickJS runtime using Mornye's
  `registerExtension()` contract instead of BitChord's Spine module exports.
- Serialize calls into each runtime and expose a single native host bridge.

## Not yet enabled in the playback source list

This change is a runtime and package-management foundation. It does not yet
register Mornye extensions as `MusicSource` instances or hand their results to
Media3. In particular, the native bridge is an interface: the next integration
slice must enforce each manifest's network, storage and file permissions,
implement the host APIs with per-extension isolation, and map Mornye's
`download()` result into a validated temporary audio file or a permitted
direct stream.

Until that work is merged and exercised through `SourceResolver`, this branch
must not be represented as shipping functional Mornye playback.

## Compatibility reference

The package format, extension lifecycle and host-call shape are based on the
public Mornye extension format. Lokal Mobile is a useful independent
compatibility reference; BitChord keeps its own Kotlin / QuickJS runtime and
player architecture rather than importing Lokal's Flutter or Rust playback
stack.
