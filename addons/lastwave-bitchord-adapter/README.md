# LastWave → BitChord Adapter

A small HTTP adapter that exposes a normal BitChord addon while using the public
LastWave standalone addon as its upstream.

## What users install in BitChord

After deployment, paste the adapter's root URL, for example:

`https://YOUR-SERVICE.up.railway.app`

BitChord will use:

- `GET /manifest.json`
- `GET /search?q=...&quality=...&atmos=...`
- `GET /stream/{trackId}?quality=...&atmos=...`

The adapter talks to LastWave internally and normalizes its response fields.
BitChord never needs to know about LastWave-specific request headers or URL
shapes.

## Upstream

Default upstream:

`https://l-bitches.clashgram.workers.dev`

Override with `LASTWAVE_BASE_URL` if the public worker moves.

The private `/a/{token}/` URL is intentionally not used. It is a separate
signed endpoint and is not needed for this public-worker adapter.

## Deploy

Deploy this folder as a Node 22 service. Set the service root directory to
`/addons/lastwave-bitchord-adapter` if deploying from the BitChord monorepo.

Environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `3000` | HTTP listen port |
| `LASTWAVE_BASE_URL` | public LastWave worker | Upstream addon root |
| `REQUEST_TIMEOUT_MS` | `15000` | Upstream timeout |
| `RATE_LIMIT_PER_MINUTE` | `60` | Per-IP request limit |

## Safety behavior

- Only the configured upstream is contacted; incoming requests cannot choose a
  remote host.
- Known LastWave honeypot/decoy URL markers are rejected.
- Inline `data:` URLs and unsupported manifest-only responses are not sent to
  the BitChord player as if they were audio files.
- The adapter does not contain the private LastWave signing key.

## Local run

```sh
npm start
```

Then open `http://localhost:3000/manifest.json` or
`http://localhost:3000/health`.
