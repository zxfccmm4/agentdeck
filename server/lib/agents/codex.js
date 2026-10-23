'use strict';
// Codex adapter.
// Codex CLI 早期把会话存成 ~/.codex/sessions/****/rollout-*.jsonl；
// 新版桌面端把索引搬进 ~/.codex/sqlite/codex-dev.db（local_thread_catalog）。
// 两者都支持：优先读 rollout 文件拿 token，缺失时退回 SQLite 索引只显示标题/时间。
const path = require('path');
const { HOME, walk, readJsonl, statSafe, shortPath } = require('../util');

const ROLLOUT_ROOT = path.join(HOME, '.codex', 'sessions');
const DB = path.join(HOME, '.codex', 'sqlite', 'codex-dev.db');
const cache = new Map();

function loadSqlite() {
  try {
    const { DatabaseSync } = require('node:sqlite');
    return new DatabaseSync(DB, { readOnly: true });
  } catch { return null; }
}

function rowsFromDb() {
  const db = loadSqlite();
  if (!db) return [];
  try {
    return db.prepare(
      `select display_title as title, cwd, model_provider as provider,
              source_created_at as created, source_updated_at as updated, thread_id as id
         from local_thread_catalog order by source_updated_at desc limit 40`
    ).all();
  } catch { return []; } finally { try { db.close(); } catch {} }
}

function parseRollout(file, mtime) {
  const cached = cache.get(file);
  if (cached && cached.mtime === mtime) return cached.value;

  const rows = readJsonl(file, 8000);
  let sessionId = (path.basename(file, '.jsonl').match(/[0-9a-f-]{36}/) || [])[0] || path.basename(file);
  let cwd = '', model = '', title = '';
  let firstAt = 0, lastAt = 0;
  const tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 };
  const events = [];
  let contextUsed = 0, turns = 0;

  for (const r of rows) {
    const p = r.payload || r;
    const ts = r.timestamp ? Date.parse(r.timestamp) : (p.timestamp ? Date.parse(p.timestamp) : 0);
    if (ts) { if (!firstAt) firstAt = ts; lastAt = Math.max(lastAt, ts); }
    if (p.cwd) cwd = p.cwd;
    if (p.model) model = p.model;
    if (p.type === 'session_meta' && p.id) sessionId = p.id;

    const info = p.info || {};
    const usage = info.total_token_usage || info.last_token_usage || p.usage;
    if (usage && (usage.input_tokens || usage.output_tokens)) {
      const inTok = usage.input_tokens || 0;
      const cachedTok = usage.cached_input_tokens || usage.cache_read_input_tokens || 0;
      const out = usage.output_tokens || 0;
      const reason = usage.reasoning_output_tokens || 0;
      tokens.input += inTok;
      tokens.cacheRead += cachedTok;
      tokens.output += out;
      tokens.reasoning += reason;
      contextUsed = inTok + cachedTok + out;
      turns++;
      events.push({ at: ts || lastAt, tokens: inTok + cachedTok + out });
    }

    if (!title) {
      const msg = p.message || p.text;
      if (typeof msg === 'string' && p.type === 'user_message') {
        title = msg.replace(/\s+/g, ' ').slice(0, 90);
      }
    }
  }

  if (!title) {
    const m = path.basename(file).match(/rollout-(\d{4})-(\d{2})-(\d{2})T/);
    title = m ? `Codex ${m[1]}-${m[2]}-${m[3]}` : shortPath(cwd || file, 48);
  }
  tokens.total = tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
  const value = {
    agent: 'codex', sessionId, file, title, cwd, model: model || 'gpt-5-codex',
    firstAt, lastAt, tokens, events, costUsd: 0, turns, contextUsed,
  };
  cache.set(file, { mtime, value });
  return value;
}

function collect() {
  const sessions = [];
  const seen = new Set();

  for (const f of walk(ROLLOUT_ROOT, '.jsonl', 0)) {
    const st = statSafe(f.path);
    if (!st) continue;
    const rec = parseRollout(f.path, st.mtimeMs);
    seen.add(rec.sessionId);
    sessions.push(rec);
  }

  // SQLite 索引里额外的线程（无 token 明细，只给标题和时间，便于看到桌面端会话）
  for (const r of rowsFromDb()) {
    if (seen.has(r.id)) continue;
    const updated = (r.updated || 0) * 1000;
    if (!updated) continue;
    sessions.push({
      agent: 'codex', sessionId: r.id, file: DB,
      title: r.title || shortPath(r.cwd, 48) || 'Codex 线程',
      cwd: r.cwd || '', model: r.provider || 'codex',
      firstAt: (r.created || 0) * 1000, lastAt: updated,
      tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
      events: [], costUsd: 0, turns: 0, contextUsed: 0, indexOnly: true,
    });
  }

  sessions.sort((a, b) => b.lastAt - a.lastAt);
  return sessions;
}

module.exports = {
  id: 'codex',
  name: 'Codex',
  color: '#8AB4F8',
  available: () => statSafe(ROLLOUT_ROOT) !== null || statSafe(DB) !== null,
  collect,
};
