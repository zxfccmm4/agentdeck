'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

const HOME = os.homedir();

function exists(p) {
  try { fs.accessSync(p); return true; } catch { return false; }
}

function statSafe(p) {
  try { return fs.statSync(p); } catch { return null; }
}

// Recursively collect files matching ext under dir, newer than minMtime.
function walk(dir, ext, minMtime = 0, out = [], depth = 0) {
  if (depth > 6) return out;
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      walk(p, ext, minMtime, out, depth + 1);
    } else if (e.isFile() && (!ext || e.name.endsWith(ext))) {
      const st = statSafe(p);
      if (st && st.mtimeMs >= minMtime) out.push({ path: p, mtime: st.mtimeMs, size: st.size });
    }
  }
  return out;
}

// Read a JSONL file line by line; skip malformed lines.
function readJsonl(file, limit = Infinity) {
  const rows = [];
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch { return rows; }
  const lines = text.split('\n');
  const start = Math.max(0, lines.length - limit);
  for (let i = start; i < lines.length; i++) {
    const line = lines[i];
    if (!line || line.length < 2) continue;
    try { rows.push(JSON.parse(line)); } catch { /* partial write */ }
  }
  return rows;
}

// Cheap head/tail read for very large logs.
function readTail(file, maxBytes) {
  const st = statSafe(file);
  if (!st) return '';
  const start = Math.max(0, st.size - maxBytes);
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const buf = Buffer.alloc(st.size - start);
    fs.readSync(fd, buf, 0, buf.length, start);
    return buf.toString('utf8');
  } catch { return ''; } finally { if (fd !== undefined) try { fs.closeSync(fd); } catch {} }
}

function basename(p) { return path.basename(p || ''); }

function shortPath(p, max = 34) {
  if (!p) return '';
  let s = String(p).replace(HOME, '~');
  if (s.length <= max) return s;
  const parts = s.split('/').filter(Boolean);
  if (parts.length <= 2) return s.slice(0, max - 1) + '…';
  return '…/' + parts.slice(-2).join('/').slice(0, max - 2);
}

function startOfToday(now = Date.now()) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function pct(part, total) {
  if (!total) return 0;
  return Math.max(0, Math.min(100, Math.round((part / total) * 100)));
}

// Last N hour buckets (oldest first) of token counts.
function hourlySeries(events, hours = 12, now = Date.now()) {
  return bucketedSeries(events, 3600 * 1000, hours, now);
}

// 通用分桶：把事件填入 buckets 个定宽时间桶（桶宽 bucketMs），返回旧->新数组。
function bucketedSeries(events, bucketMs, buckets, now = Date.now()) {
  const out = new Array(buckets).fill(0);
  const end = Math.floor(now / bucketMs) * bucketMs;
  for (const ev of events) {
    const t = ev.at;
    if (!t) continue;
    const idx = buckets - 1 - Math.floor((end + bucketMs - t) / bucketMs);
    if (idx >= 0 && idx < buckets) out[idx] += ev.tokens || 0;
  }
  return out;
}

module.exports = {
  HOME, exists, statSafe, walk, readJsonl, readTail, basename, shortPath,
  startOfToday, pct, hourlySeries, bucketedSeries,
};
