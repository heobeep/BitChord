import { createDecipheriv } from "node:crypto";

const PROTECTION_BOXES = new Set(["senc", "saiz", "saio"]);
const SAMPLE_ENTRY_HEADER = 36;

function u16(b, o) { return b.readUInt16BE(o); }
function u32(b, o) { return b.readUInt32BE(o); }
function u64(b, o) {
  const n = b.readBigUInt64BE(o);
  if (n > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error("MP4 offset too large");
  return Number(n);
}
function flags24(b, o) { return (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]; }
function typeAt(b, o) { return b.toString("latin1", o, o + 4); }
function writeType(b, o, value) { b.write(value, o, 4, "latin1"); }

function boxes(buf, start = 0, end = buf.length) {
  const out = [];
  let off = start;
  while (off + 8 <= end) {
    let size = u32(buf, off);
    const type = typeAt(buf, off + 4);
    let header = 8;
    if (size === 1) {
      if (off + 16 > end) break;
      size = u64(buf, off + 8);
      header = 16;
    } else if (size === 0) {
      size = end - off;
    }
    if (size < header || off + size > end) break;
    out.push({ type, start: off, contentStart: off + header, end: off + size });
    off += size;
  }
  return out;
}

function findBox(buf, start, end, type) {
  return boxes(buf, start, end).find(box => box.type === type) || null;
}

function findPath(buf, start, end, path) {
  let s = start, e = end, found = null;
  for (const type of path) {
    found = findBox(buf, s, e, type);
    if (!found) return null;
    s = found.contentStart;
    e = found.end;
  }
  return found;
}

function parseTenc(buf, box) {
  const o = box.contentStart;
  if (o + 24 > box.end) throw new Error("Truncated tenc box");
  const protectedFlag = buf[o + 6];
  const ivSize = buf[o + 7];
  let constantIv = Buffer.alloc(0);
  if (ivSize === 0 && protectedFlag !== 0) {
    const at = o + 24;
    if (at < box.end) {
      const n = buf[at];
      if (at + 1 + n <= box.end) constantIv = buf.subarray(at + 1, at + 1 + n);
    }
  }
  return { ivSize, constantIv };
}

function patchInit(buf) {
  const moov = findBox(buf, 0, buf.length, "moov");
  if (!moov) throw new Error("Hybrid init has no moov box");
  const stsd = findPath(buf, moov.contentStart, moov.end, ["trak", "mdia", "minf", "stbl", "stsd"]);
  if (!stsd) throw new Error("Hybrid init has no audio sample description");

  let info = null;
  for (const entry of boxes(buf, stsd.contentStart + 8, stsd.end)) {
    const childStart = entry.start + SAMPLE_ENTRY_HEADER;
    if (childStart >= entry.end) continue;
    const sinf = findBox(buf, childStart, entry.end, "sinf");
    if (!sinf) continue;
    const frma = findBox(buf, sinf.contentStart, sinf.end, "frma");
    if (frma) writeType(buf, entry.start + 4, typeAt(buf, frma.contentStart));
    const schm = findBox(buf, sinf.contentStart, sinf.end, "schm");
    if (schm && typeAt(buf, schm.contentStart + 4) !== "cenc") {
      throw new Error("Unsupported hybrid protection scheme");
    }
    const tenc = findPath(buf, sinf.contentStart, sinf.end, ["schi", "tenc"]);
    if (tenc) info = parseTenc(buf, tenc);
    writeType(buf, sinf.start + 4, "free");
  }
  if (!info) throw new Error("Hybrid init has no tenc protection metadata");
  return info;
}

function importKey(value) {
  const clean = String(value || "").trim().replace(/^0x/i, "").replace(/[^0-9a-f]/gi, "");
  if (clean.length !== 32) throw new Error("Hybrid content key is not 16-byte hex");
  return Buffer.from(clean, "hex");
}

function normalizeIv(iv, constantIv) {
  const value = iv.length ? iv : constantIv;
  const out = Buffer.alloc(16);
  value.copy(out, 0, 0, Math.min(16, value.length));
  return out;
}

function parseSenc(buf, box, ivSize) {
  const flags = flags24(buf, box.contentStart);
  const count = u32(buf, box.contentStart + 4);
  let off = box.contentStart + 8;
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (off + ivSize > box.end) throw new Error("Truncated senc IV");
    const iv = ivSize ? buf.subarray(off, off + ivSize) : Buffer.alloc(0);
    off += ivSize;
    const subsamples = [];
    if (flags & 0x000002) {
      if (off + 2 > box.end) throw new Error("Truncated senc subsample count");
      const n = u16(buf, off); off += 2;
      for (let j = 0; j < n; j++) {
        if (off + 6 > box.end) throw new Error("Truncated senc subsample");
        subsamples.push({ clear: u16(buf, off), encrypted: u32(buf, off + 2) });
        off += 6;
      }
    }
    entries.push({ iv, subsamples });
  }
  return entries;
}

function decryptSample(buf, position, size, iv, subsamples, key) {
  const spans = [];
  if (!subsamples.length) {
    spans.push({ offset: position, length: size });
  } else {
    let off = position;
    for (const sub of subsamples) {
      off += sub.clear;
      if (sub.encrypted > 0) spans.push({ offset: off, length: sub.encrypted });
      off += sub.encrypted;
    }
  }
  const total = spans.reduce((sum, span) => sum + span.length, 0);
  if (!total) return;
  const packed = Buffer.concat(spans.map(span => buf.subarray(span.offset, span.offset + span.length)), total);
  const decipher = createDecipheriv("aes-128-ctr", key, iv);
  const plain = Buffer.concat([decipher.update(packed), decipher.final()]);
  let read = 0;
  for (const span of spans) {
    plain.copy(buf, span.offset, read, read + span.length);
    read += span.length;
  }
}

function processTraf(buf, traf, moofStartInFile, segmentStart, ivSize, constantIv, key) {
  const tfhd = findBox(buf, traf.contentStart, traf.end, "tfhd");
  if (!tfhd) return;
  const flags = flags24(buf, tfhd.contentStart);
  let off = tfhd.contentStart + 8;
  let baseDataOffset = null;
  let defaultSize = 0;
  if (flags & 0x000001) { baseDataOffset = u64(buf, off); off += 8; }
  if (flags & 0x000002) off += 4;
  if (flags & 0x000008) off += 4;
  if (flags & 0x000010) { defaultSize = u32(buf, off); off += 4; }

  const senc = findBox(buf, traf.contentStart, traf.end, "senc");
  if (!senc) return;
  const entries = parseSenc(buf, senc, ivSize);
  if (!entries.length) return;
  const base = baseDataOffset ?? moofStartInFile;
  let sampleIndex = 0;

  for (const trun of boxes(buf, traf.contentStart, traf.end).filter(b => b.type === "trun")) {
    const trunFlags = flags24(buf, trun.contentStart);
    const count = u32(buf, trun.contentStart + 4);
    let p = trun.contentStart + 8;
    let dataOffset = 0;
    if (trunFlags & 0x000001) { dataOffset = buf.readInt32BE(p); p += 4; }
    if (trunFlags & 0x000004) p += 4;
    let pos = base + dataOffset;

    for (let i = 0; i < count; i++) {
      let sampleSize = defaultSize;
      if (trunFlags & 0x000100) p += 4;
      if (trunFlags & 0x000200) { sampleSize = u32(buf, p); p += 4; }
      if (trunFlags & 0x000400) p += 4;
      if (trunFlags & 0x000800) p += 4;
      const entry = entries[sampleIndex++];
      if (entry && (entry.iv.length || constantIv.length)) {
        const bufferPos = pos - segmentStart;
        if (bufferPos >= 0 && bufferPos + sampleSize <= buf.length) {
          decryptSample(buf, bufferPos, sampleSize, normalizeIv(entry.iv, constantIv), entry.subsamples, key);
        }
      }
      pos += sampleSize;
    }
  }

  for (const box of boxes(buf, traf.contentStart, traf.end)) {
    if (PROTECTION_BOXES.has(box.type)) {
      writeType(buf, box.start + 4, "free");
    } else if (box.type === "sbgp" || box.type === "sgpd") {
      if (typeAt(buf, box.contentStart + 4) === "seig") writeType(buf, box.start + 4, "free");
    }
  }
}

function decryptSegment(buf, key, ivSize, constantIv, segmentStart) {
  let pendingMoof = null;
  for (const box of boxes(buf)) {
    if (box.type === "moof") { pendingMoof = box; continue; }
    if (box.type === "mdat" && pendingMoof) {
      const moofStartInFile = segmentStart + pendingMoof.start;
      for (const child of boxes(buf, pendingMoof.contentStart, pendingMoof.end)) {
        if (child.type === "traf") {
          processTraf(buf, child, moofStartInFile, segmentStart, ivSize, constantIv, key);
        }
      }
      pendingMoof = null;
    }
  }
}

async function fetchRange(cdnUrl, range, timeoutMs) {
  const response = await fetch(cdnUrl, {
    headers: { range: `bytes=${range.start}-${range.end}` },
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) throw new Error(`Hybrid CDN range failed (HTTP ${response.status})`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const expected = range.end - range.start + 1;
  if (bytes.length !== expected) {
    throw new Error(`Hybrid CDN returned ${bytes.length} bytes; expected ${expected}`);
  }
  return bytes;
}

/** Fetches, decrypts and assembles a LastWave hybrid-v1 fragmented MP4 plan. */
export async function assembleHybrid(plan, { timeoutMs = 20000, maxBytes = 160 * 1024 * 1024 } = {}) {
  if (!plan || typeof plan.cdnUrl !== "string" || !plan.cdnUrl.startsWith("https://") ||
      !plan.initRange || !Array.isArray(plan.ranges) || !plan.ranges.length) {
    throw new Error("Invalid LastWave hybrid-v1 plan");
  }
  const allRanges = [plan.initRange, ...plan.ranges];
  const totalBytes = allRanges.reduce((n, r) => n + r.end - r.start + 1, 0);
  if (totalBytes <= 0 || totalBytes > maxBytes) throw new Error("Hybrid stream exceeds adapter size limit");

  const chunks = [];
  for (let i = 0; i < allRanges.length; i += 6) {
    const batch = await Promise.all(allRanges.slice(i, i + 6).map(r => fetchRange(plan.cdnUrl, r, timeoutMs)));
    chunks.push(...batch);
  }

  let init = Buffer.from(chunks[0]);
  let ivSize = Number(plan.ivSize || 16);
  let constantIv = plan.constantIv ? Buffer.from(plan.constantIv, "base64") : Buffer.alloc(0);
  let key = Buffer.alloc(0);
  if (plan.encrypted === true) {
    key = importKey(plan.key);
    const tenc = patchInit(init);
    ivSize = tenc.ivSize;
    if (tenc.constantIv.length) constantIv = tenc.constantIv;
  }
  const output = [init];
  let segmentStart = init.length;
  for (let i = 0; i < plan.ranges.length; i++) {
    const segment = Buffer.from(chunks[i + 1]);
    if (plan.encrypted === true) decryptSegment(segment, key, ivSize, constantIv, plan.ranges[i].start);
    output.push(segment);
    segmentStart += segment.length;
  }
  return Buffer.concat(output, totalBytes);
}
