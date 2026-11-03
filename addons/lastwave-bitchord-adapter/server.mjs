import http from "node:http";
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";
import { assembleHybrid } from "./hybrid.mjs";

const DEFAULT_UPSTREAM = "https://l-bitches.clashgram.workers.dev";
const PORT = Number(process.env.PORT || 3000);
const UPSTREAM = normalizeBase(process.env.LASTWAVE_BASE_URL || DEFAULT_UPSTREAM);
const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 15000);
const RATE_WINDOW_MS = 60_000;
const RATE_LIMIT = Number(process.env.RATE_LIMIT_PER_MINUTE || 60);
const hits = new Map();
const mediaCache = new Map();
const PUBLIC_BASE_URL = process.env.PUBLIC_BASE_URL || "";

function normalizeBase(value) {
  const url = new URL(value);
  if (!["http:", "https:"].includes(url.protocol)) {
    throw new Error("LASTWAVE_BASE_URL must use HTTP or HTTPS");
  }
  return url.toString().replace(/\/+$/, "");
}

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store",
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, OPTIONS",
    "access-control-allow-headers": "Accept, Content-Type",
  });
  res.end(payload);
}

function limited(req) {
  const ip = req.headers["x-forwarded-for"]?.split(",")[0]?.trim() ||
    req.socket.remoteAddress || "unknown";
  const now = Date.now();
  const entry = hits.get(ip);
  if (!entry || now - entry.start >= RATE_WINDOW_MS) {
    hits.set(ip, { start: now, count: 1 });
    return false;
  }
  entry.count += 1;
  return entry.count > RATE_LIMIT;
}

function isDecoy(value) {
  if (typeof value !== "string") return false;
  const lower = value.toLowerCase();
  return lower.includes("pranks-cdn") || lower.includes("definatelynagato");
}

function extractTracks(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== "object") return [];
  for (const key of ["tracks", "results", "items"]) {
    if (Array.isArray(payload[key])) return payload[key];
  }
  if (Array.isArray(payload.data)) return payload.data;
  if (payload.data && typeof payload.data === "object") {
    return extractTracks(payload.data);
  }
  return [];
}

export function normalizeTrack(track) {
  if (!track || typeof track !== "object") return null;
  const id = track.id ?? track.trackId ?? track.videoId;
  const title = track.title ?? track.name;
  if (id == null || !String(id).trim() || !title) return null;
  const streamURL = track.streamURL ?? track.streamUrl ?? track.directUrl ??
    track.downloadUrl ?? track.mediaUrl ?? track.dataUrl;
  const {
    streamUrl: _streamUrl,
    directUrl: _directUrl,
    downloadUrl: _downloadUrl,
    mediaUrl: _mediaUrl,
    dataUrl: _dataUrl,
    ...safeTrack
  } = track;
  return {
    ...safeTrack,
    id: String(id),
    title: String(title),
    artist: String(track.artist ?? track.artists ?? "Unknown Artist"),
    ...(track.album != null ? { album: String(track.album) } : {}),
    ...(track.duration != null ? { duration: Number(track.duration) } : {}),
    ...(track.artworkURL || track.artwork
      ? { artworkURL: track.artworkURL ?? track.artwork } : {}),
    ...(typeof streamURL === "string" && /^https?:\/\//i.test(streamURL) && !isDecoy(streamURL)
      ? { streamURL } : { streamURL: undefined }),
  };
}

function extractStream(payload) {
  if (!payload || typeof payload !== "object") return null;
  if (payload.stream && typeof payload.stream === "object") return payload.stream;
  if (payload.data && typeof payload.data === "object" && !Array.isArray(payload.data)) {
    return payload.data;
  }
  return payload;
}

export function normalizeStream(payload) {
  const stream = extractStream(payload);
  if (!stream) return null;
  const candidates = [
    stream.url,
    stream.directUrl,
    stream.streamUrl,
    stream.downloadUrl,
    stream.mediaUrl,
    stream.dataUrl,
  ];
  const url = candidates.find(value =>
    typeof value === "string" &&
    /^https?:\/\//i.test(value) &&
    !isDecoy(value)
  );
  if (!url) return null;
  return {
    ...stream,
    url,
    ...(stream.format ? { format: stream.format } : {}),
  };
}

async function upstreamGet(path, query = {}) {
  const target = new URL(UPSTREAM + path);
  for (const [key, value] of Object.entries(query)) {
    if (value != null && value !== "") target.searchParams.set(key, String(value));
  }
  const response = await fetch(target, {
    method: "GET",
    headers: {
      accept: "application/json",
      "user-agent": "LastWave-Player/1.0",
      "x-lw-intent": "stream",
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`LastWave returned non-JSON (HTTP ${response.status})`);
  }
  if (!response.ok) {
    const error = new Error(body?.error || `LastWave returned HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return body;
}

async function handle(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET, OPTIONS",
      "access-control-allow-headers": "Accept, Content-Type",
    });
    return res.end();
  }
  if (req.method !== "GET") return json(res, 405, { error: "GET only" });

  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname === "/health") {
    return json(res, 200, {
      ok: true,
      addon: "LastWave → BitChord adapter",
      upstream: UPSTREAM,
    });
  }
  if (url.pathname === "/manifest.json") {
    return json(res, 200, {
      id: "lastwave-bitchord-adapter",
      name: "LastWave (BitChord Adapter)",
      version: "1.0.0",
      description: "BitChord-compatible access to the LastWave music catalogue.",
      resources: ["search", "stream"],
      allowDownloads: true,
      checkValidLossless: false,
    });
  }
  if (limited(req)) {
    return json(res, 429, { error: "Adapter rate limit reached. Try again shortly." });
  }

  try {
    if (url.pathname === "/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (!q) return json(res, 200, { tracks: [] });
      const payload = await upstreamGet("/search", {
        q,
        quality: (url.searchParams.get("quality") || "lossless").toLowerCase(),
        atmos: url.searchParams.get("atmos") || "none",
      });
      const tracks = extractTracks(payload).map(normalizeTrack).filter(Boolean);
      return json(res, 200, { tracks });
    }

    const mediaMatch = url.pathname.match(/^\\/media\\/([a-f0-9-]+)\\.m4a$/i);
    if (mediaMatch) {
      const cached = mediaCache.get(mediaMatch[1]);
      if (!cached || cached.expiresAt < Date.now()) {
        mediaCache.delete(mediaMatch[1]);
        return json(res, 404, { error: "Assembled stream expired. Request the track again." });
      }
      const range = req.headers.range;
      let start = 0, end = cached.buffer.length - 1, status = 200;
      if (range) {
        const match = range.match(/^bytes=(\\d*)-(\\d*)$/);
        if (!match) return json(res, 416, { error: "Invalid byte range" });
        if (match[1] === "") {
          const suffix = Number(match[2]);
          if (!suffix) return json(res, 416, { error: "Invalid byte range" });
          start = Math.max(0, cached.buffer.length - suffix);
        } else {
          start = Number(match[1]);
          if (match[2]) end = Math.min(end, Number(match[2]));
        }
        if (!Number.isSafeInteger(start) || start < 0 || start >= cached.buffer.length || end < start) {
          res.writeHead(416, { "content-range": "bytes */" + cached.buffer.length });
          return res.end();
        }
        status = 206;
      }
      const body = cached.buffer.subarray(start, end + 1);
      res.writeHead(status, {
        "content-type": "audio/mp4",
        "content-length": body.length,
        "accept-ranges": "bytes",
        "cache-control": "private, max-age=900",
        ...(status === 206 ? { "content-range": "bytes " + start + "-" + end + "/" + cached.buffer.length } : {}),
      });
      return res.end(body);
    }
    const streamMatch = url.pathname.match(/^\/stream\/(.+)$/);
    if (streamMatch) {
      const id = decodeURIComponent(streamMatch[1]);
      if (!id || id.length > 512) return json(res, 400, { error: "Invalid track id" });
      const payload = await upstreamGet("/stream/" + encodeURIComponent(id), {
        quality: (url.searchParams.get("quality") || "lossless").toLowerCase(),
        atmos: url.searchParams.get("atmos") || "none",
      });
      const rawStream = extractStream(payload);
      const stream = normalizeStream(payload);
      if (stream) return json(res, 200, stream);

      // LastWave hybrid-v1: assemble and decrypt into a normal range-capable MP4.
      if (rawStream?.hybrid) {
        const assembled = await assembleHybrid(rawStream.hybrid, { timeoutMs: REQUEST_TIMEOUT_MS });
        const mediaId = randomUUID();
        mediaCache.set(mediaId, { buffer: assembled, expiresAt: Date.now() + 15 * 60_000 });
        const base = PUBLIC_BASE_URL.replace(/\\/+$/, "") ||
          ((req.headers["x-forwarded-proto"] || "https") + "://" + req.headers.host);
        return json(res, 200, {
          url: base + "/media/" + mediaId + ".m4a",
          format: "m4a",
          container: "mp4",
          codec: rawStream.codec || "flac",
          quality: rawStream.quality || rawStream.audioQuality || "lossless",
          manifest: "none",
          encrypted: false,
          sampleRate: rawStream.sampleRate,
          bitDepth: rawStream.bitDepth,
          bitrate: rawStream.bitrate,
          audioMode: rawStream.audioMode,
          audioModes: rawStream.audioModes,
          atmos: rawStream.atmos,
        });
      }

      return json(res, 502, {
        error: "LastWave returned no playable direct URL or supported hybrid-v1 plan. Decoys and unsupported manifests are blocked.",
      });
    }

    return json(res, 404, { error: "Not found" });
  } catch (error) {
    const status = error.status === 429 ? 429 : 502;
    return json(res, status, {
      error: error.message || "LastWave upstream request failed",
    });
  }
}

export function createServer() {
  return http.createServer((req, res) => {
    handle(req, res).catch(error => {
      json(res, 500, { error: error.message || "Unexpected adapter error" });
    });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createServer().listen(PORT, "0.0.0.0", () => {
    console.log(`LastWave BitChord adapter listening on :${PORT}`);
    console.log(`Upstream: ${UPSTREAM}`);
  });
}
