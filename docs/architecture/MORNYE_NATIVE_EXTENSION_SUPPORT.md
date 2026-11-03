# Native Mornye extension support in BitChord

Status: implementation RFC / proof-of-concept plan  
Target branch: `feat/mornye-native-runtime-poc`

## Goal

Run compatible Mornye extensions inside BitChord on Android, using BitChord's existing QuickJS dependency and native Media3 playback. Mornye support must be a distinct source/runtime type; it must not change the behavior of existing BitChord server add-ons or legacy Spine modules.

## Repository findings

BitChord already has:

- A `MusicSource` abstraction and `SourceRegistry` for source ordering and lifecycle.
- A QuickJS-based JavaScript executor used by the legacy module system.
- Native HTTP infrastructure and a playback resolver that ultimately supplies streams to the existing player.

The current module executor is not a Mornye host. It evaluates modules into a Spine/CommonJS-style export object and calls named exports such as `searchTracks` and `getTrackStreamUrl`. Mornye extensions register a structured extension object through `registerExtension()` and expect a host API. Treating a Mornye package as a normal module would therefore be incorrect.

## Proposed design

### 1. Isolated package/runtime subsystem

Introduce a separate package namespace and manager (proposed names: `data/sources/mornye/`, `MornyePackageManager`, `MornyeRuntime`). It should:

- Discover registry entries and package metadata.
- Download packages to a temporary location.
- Validate archive structure, manifest identity/version, declared entrypoint and SHA-256 when the registry provides one.
- Reject path traversal, duplicate/ambiguous entrypoints, oversized archives and unsupported manifest versions.
- Install atomically into an app-private directory; preserve the previous working version until the new package validates.
- Support enable/disable, update, rollback and uninstall without touching other source types.

Do not assume one package suffix or registry schema. Implement against the current official package/manifest specification and add fixtures for each supported archive form.

### 2. Mornye runtime contract

Use QuickJS, but do not reuse the Spine export wrapper. A Mornye runtime must:

- Evaluate the extension entrypoint in a fresh, isolated JS context.
- Install the Mornye-compatible bootstrap before evaluating extension code.
- Capture and validate `registerExtension()` exactly once (or reject ambiguous multiple registrations).
- Expose only documented host capabilities through native bindings.
- Keep extension state alive across its calls, while serializing access to each interpreter.
- Bound execution time, memory/stack, response sizes and concurrent work.
- Close the runtime and cancel outstanding work when an extension is disabled, replaced or removed.

Do not share a mutable QuickJS context concurrently. The current module engine pool's behavior and Mornye's stateful registration semantics need separate lifecycle decisions.

### 3. Host API and security boundary

Implement host APIs incrementally from the official Mornye contract, not from assumptions based on a handful of packages. Expected capability groups include:

- HTTP/fetch with bounded response sizes, timeouts, redirect checks and domain policy.
- Per-extension key/value storage.
- App-private file staging only; no arbitrary filesystem paths.
- Credential/authentication callbacks that never expose other extensions' secrets.
- Crypto/encoding helpers only where the specification requires them.
- Progress and cancellation callbacks.

Enforce network restrictions on every redirect and resolved address. Deny loopback, link-local and private-network destinations unless a narrowly scoped, documented exception is explicitly required. Never log credentials, signed URLs, tokens or raw authorization headers.

### 4. Native source adapter

Add a dedicated Mornye adapter that maps extension capabilities to BitChord's existing source/playback abstractions:

- Search and metadata results become normalized BitChord track models.
- A direct, validated stream URL can become a native stream result with required headers.
- Download-based extensions must download to a unique app-private staging file, report progress, validate non-empty output and inspect the resulting media before playback.
- Decryption and container conversion must be explicit, allow-listed operations with strict validation. Never execute arbitrary shell commands supplied by an extension.
- Growing-file playback is out of scope for the first milestone. It can be added later only for tested formats and providers.

Metadata-only and lyrics-only extensions must not be registered as audio sources; they should attach to the appropriate native subsystem.

### 5. Native UI

Add an Extensions screen after the runtime is proven. It should show package name, publisher, version, permissions, enabled state, update availability and actionable errors. Installation must display requested permissions before activation.

## Milestones

### M0 — Contract and fixtures

- Confirm current official Mornye manifest, package/archive and host API specifications.
- Add small, deterministic fixtures: valid registration, missing registration, duplicate registration, malformed manifest, unsupported permission, and throwing extension.
- Document the license and attribution boundaries. Lokal-mobile is a reference implementation, not a source to copy wholesale.

### M1 — Runtime proof of concept

- Load a local test package into a fresh QuickJS context.
- Bootstrap `registerExtension()`.
- Invoke initialize and one metadata/search operation.
- Verify JSON results and error propagation.
- Verify isolation, timeout, cancellation and clean shutdown.

### M2 — Native playback proof

- Add a minimal Mornye-backed `MusicSource`.
- Resolve one test track to a direct stream or a completed staged file.
- Play through the existing BitChord player without changing the player or queue implementation.
- Test failure fallback and source disable/uninstall.

### M3 — Package manager and UI

- Add registry browsing, install/update/rollback, permissions and extension settings.
- Preserve all existing Addon and legacy Module behavior.

### M4 — Compatibility expansion

- Add download workflows, authentication, lyrics, album/artist metadata, progress and supported post-processing.
- Build a repeatable compatibility suite against a curated set of official extensions.

## Acceptance criteria

The first user-facing release is not ready until:

1. A valid official-format package installs and its hash/manifest are verified.
2. A Mornye extension registers successfully in an isolated runtime.
3. Initialization and search work with state retained between calls.
4. The returned track is normalized correctly.
5. One track plays through BitChord's existing player.
6. Existing Addon and legacy module tests remain green.
7. Malformed packages, forbidden network destinations, runtime exceptions and timeouts fail safely.
8. The app can disable/uninstall the extension and release its runtime and temporary files.

## Important non-goals for M1

- Replacing the current player.
- Rewriting BitChord in Flutter/Rust.
- Copying Lokal-mobile's implementation wholesale.
- Claiming universal compatibility with every extension.
- Supporting arbitrary extension-supplied native code.
