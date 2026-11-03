# Mornye authentication and signed-session compatibility

Status: implementation plan for `feat/mornye-auth-session-support`.

## Why this is a separate milestone

The existing BitChord Mornye runtime can load an extension and make ordinary HTTP requests, but it does not yet provide the authentication/session host APIs expected by the official Mornye extension guide or by the current community extension registry. An extension can therefore install successfully and still fail when its provider needs cookies, OAuth, a signed session, or human verification.

This document is based on the current Mornye extension guide and the source-backed provider manifests/runtime in `mornyeapp/Mornye` and `mornyeapp/Mornye-Extension`. It is not a claim that these features have shipped in BitChord.

## Compatibility surfaces to implement

### 1. Per-extension HTTP cookie jar

- Keep cookies isolated by extension ID.
- Persist cookies between app launches.
- Honor domain, path, secure, expiry, and same-site rules using the platform HTTP cookie implementation.
- Capture `Set-Cookie` and send matching cookies on subsequent requests.
- Implement `http.clearCookies()` as a scoped clear, not a global clear.
- Never expose cookie values in logs or diagnostics.

### 2. Encrypted extension credentials

Implement the documented `credentials` API:

- `credentials.store(key, value)`
- `credentials.get(key)`
- `credentials.has(key)`
- `credentials.remove(key)`

Use Android Keystore-backed encryption and keep each extension's credential namespace separate from its ordinary JSON storage. Uninstalling an extension must remove its credentials and session state.

### 3. OAuth and browser callbacks

Implement the documented `auth` API and PKCE helpers:

- `auth.openAuthUrl(authUrl, callbackUrl)`
- `auth.getAuthCode()`
- `auth.setAuthCode(tokens)`
- `auth.isAuthenticated()`
- `auth.getTokens()`
- `auth.clearAuth()`
- `auth.generatePKCE()`
- `auth.getPKCE()`
- `auth.startOAuthWithPKCE(config)`
- `auth.exchangeCodeWithPKCE(config)`

Validate HTTPS authorization/token endpoints and callback ownership. Use short-lived, single-use state/nonces, reject mismatched callbacks, and never put secrets or authorization codes in logs. Browser return handling must survive Activity recreation and app process restart where possible.

### 4. Signed-session v1 host API

The current community registry includes manifests declaring runtime features such as:

- `signedSession@1`
- `sessionGrant@1`
- `preparedContext@1`

A signed-session manifest scopes a session by namespace, HTTPS base URL, app version, and platform. The host must implement:

- A persistent per-scope install ID and encrypted session record.
- Bootstrap against the manifest's `/bootstrap` endpoint.
- Verification challenge URL construction and callback state.
- Session-grant exchange against `/session/exchange`.
- Optional session refresh.
- HMAC-SHA256 request signing with a time-windowed rolling key, timestamp, nonce, body hash, session ID, app version, and platform.
- `session.signedFetch(method, path, body, headers)`, `session.status()`, `session.clear()`, and `session.completeGrant(grant)`.
- Shared in-flight bootstrap/refresh/exchange coordination so parallel requests do not create duplicate challenges or invalidate newer sessions.
- Correct error contracts, including `needsVerification`, `auth_url`, retry mode, and retry-after.

The callback must be owned by BitChord, not hard-coded to the Mornye app. The registry deliberately omits a fixed callback URL for signed-session providers so the installed host can supply its own callback.

### 5. Verification UX and resume behavior

- Show which extension/provider needs verification.
- Open the validated challenge in a browser or Custom Tab.
- Accept only a callback matching the pending provider and state.
- Exchange the one-use grant, save the new session securely, then resume/retry the interrupted provider operation once.
- Coalesce concurrent verification requests for the same session scope.
- Keep unrelated providers and playback usable while one provider is waiting.
- If a provider expires or rejects its session later, ask for verification again rather than pretending a session lasts forever.

## Important boundaries

- CAPTCHA is completed by the user on the provider's legitimate verification page. BitChord must not attempt to solve or bypass it.
- A generic browser CAPTCHA and an OAuth login are different flows. Both need explicit handling.
- A provider's session ID and secret are not the user's music-service password. They are scoped host credentials and must be encrypted at rest.
- The signed-session gateway protocol is distinct from the public Mornye OAuth API. Supporting OAuth alone does not make signed-session providers compatible.
- Never install or silently run a provider requiring a runtime feature BitChord does not implement. Until the feature is complete, report the missing feature clearly.

## Acceptance tests

1. Two extensions cannot read each other's cookies, credentials, or signed-session records.
2. Cookies survive runtime recreation and app restart; clearCookies clears only the selected extension.
3. OAuth state mismatch, expired state, replayed callback, invalid callback scheme, and non-HTTPS auth URL are rejected.
4. PKCE verifier survives browser handoff and is consumed only by the matching callback.
5. Signed-session bootstrap can return either a usable session or a verification challenge.
6. A valid grant exchanges once; duplicate callbacks share one exchange; stale callbacks cannot overwrite a newer session.
7. HMAC headers match deterministic fixtures for path, method, body, timestamp, nonce, and session scope.
8. Expired, invalid, blocked, and refreshed sessions behave correctly.
9. Parallel provider calls share bootstrap/refresh work and preserve cancellation.
10. A real current community extension can be installed, request verification, return to BitChord, search, resolve a stream, and play it on phone and Android TV.

## Release gate

Do not mark Mornye compatibility complete, publish a release APK, or merge the parent Mornye integration PR until the acceptance tests above pass and at least one signed-session community provider has been exercised end to end on a real Android device.
