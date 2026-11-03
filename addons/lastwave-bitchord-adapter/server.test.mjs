import test from "node:test";
import assert from "node:assert/strict";
import { normalizeStream, normalizeTrack } from "./server.mjs";
import { assembleHybrid } from "./hybrid.mjs";

test("normalizes LastWave stream URL aliases to the BitChord url field", () => {
  for (const field of ["directUrl", "streamUrl", "downloadUrl", "mediaUrl", "dataUrl"]) {
    const result = normalizeStream({ [field]: "https://cdn.example/audio.flac" });
    assert.equal(result.url, "https://cdn.example/audio.flac");
  }
});

test("unwraps LastWave stream/data response envelopes", () => {
  assert.equal(
    normalizeStream({ stream: { directUrl: "https://cdn.example/a.flac" } }).url,
    "https://cdn.example/a.flac",
  );
  assert.equal(
    normalizeStream({ data: { url: "https://cdn.example/b.flac" } }).url,
    "https://cdn.example/b.flac",
  );
});

test("rejects known LastWave honeypot and inline data URLs", () => {
  assert.equal(normalizeStream({ url: "https://pranks-cdn.example/fake.mp3" }), null);
  assert.equal(normalizeStream({ directUrl: "https://cdn.example/definatelynagato/fake.mp3" }), null);
  assert.equal(normalizeStream({ dataUrl: "data:application/dash+xml;base64,AAAA" }), null);
});

test("normalizes search tracks and strips decoy direct stream links", () => {
  const clean = normalizeTrack({
    id: 123,
    title: "Example",
    artist: "Artist",
    directUrl: "https://cdn.example/audio.flac",
  });
  assert.equal(clean.id, "123");
  assert.equal(clean.streamURL, "https://cdn.example/audio.flac");

  const decoy = normalizeTrack({
    id: "bad",
    title: "Decoy",
    streamURL: "https://pranks-cdn.example/fake.mp3",
  });
  assert.equal(decoy.streamURL, undefined);
});

test("rejects malformed hybrid-v1 plans instead of returning fake audio", async () => {
  await assert.rejects(() => assembleHybrid({}), /Invalid LastWave hybrid-v1 plan/);
});
