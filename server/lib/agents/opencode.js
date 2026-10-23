'use strict';
// opencode / omo adapter: ~/.local/share/opencode/opencode.db
// session 表直接带 cost / tokens_* 汇总；message.data 里还有逐条 message 的 usage。
// omo（oh-my-openagent）复用 opencode 的存储，用 session 的 agent 字段或路径区分。
const fs = require('fs');
const path = require('path');
const { HOME, statSafe, shortPath } = require('../util');

const DB_CANDIDATES = [
  path.join(HOME, '.local', 'share', 'opencode', 'opencode.db'),
  path.join(HOME, '.local', 'share', 'omo', 'opencode.db'),
];

let cachedDb = null;
let cachedPath = null;

function openDb() {
  const dbPath = DB_CANDIDATES.find((p) => statSafe(p));
  if (!dbPath) return null;
  if (cachedDb && cachedPath === dbPath) return cachedDb;
  try {
    const { DatabaseSync } = require('node:sqlite');
    cachedDb = new DatabaseSync(dbPath, { readOnly: true });
    cachedPath = dbPath;
    return cachedDb;
  } catch { return null; }
}

function messageUsage(db, sessionId) {
  // 最近一条 assistant message 的 usage（用于上下文占用和模型名）
  try {
    const rows = db.prepare(
      `select data from message where session_id = ? order by time_updated desc limit 40`
    ).all(sessionId);
    for (const r of rows) {
      let d;
      try { d = JSON.parse(r.data); } catch { continue; }
      const u = d.tokens || (d.usage && d.usage.tokens) || d.usage;
      if (d.role === 'assistant' && u) {
        return {
          model: d.modelID ? `${d.providerID || ''}${d.providerID ? '/' : ''}${d.modelID}` : '',
          context: (u.input || 0) + (u.cache && u.cache.read ? u.cache.read : 0) + (u.output || 0),
        };
      }
    }
  } catch { /* ignore */ }
  return { model: '', context: 0 };
}

function collect(sinceMs) {
  const db = openDb();
  if (!db) return [];
  let rows;
  try {
    rows = db.prepare(
      `select id, title, directory, cost, tokens_input, tokens_output, tokens_reasoning,
              tokens_cache_read, tokens_cache_write, agent, model, time_created, time_updated
         from session
        where time_updated >= ?
        order by time_updated desc limit 60`
    ).all(Math.floor(sinceMs || Date.now() - 90 * 864e5));
  } catch { return []; }

  const out = [];
  for (const r of rows) {
    const total = (r.tokens_input || 0) + (r.tokens_output || 0) + (r.tokens_cache_read || 0) + (r.tokens_cache_write || 0);
    const fresh = Date.now() - (r.time_updated || 0) < 24 * 3600 * 1000;
    if (!total && !fresh) continue;
    const extra = messageUsage(db, r.id);
    let model = extra.model || r.model || '';
    if (model.startsWith('{')) {
      try {
        const m = JSON.parse(model);
        model = m.id || m.modelID || m.name || '';
      } catch { model = ''; }
    }
    const isOmo = /omo/i.test(r.directory || '') || /omo/i.test(r.agent || '');
    out.push({
      agent: isOmo ? 'omo' : 'opencode',
      sessionId: r.id,
      file: DB_CANDIDATES[0],
      title: r.title || shortPath(r.directory, 48) || 'opencode 会话',
      cwd: r.directory || '',
      model: model || (isOmo ? 'omo' : 'opencode'),
      firstAt: r.time_created || 0,
      lastAt: r.time_updated || 0,
      tokens: {
        input: r.tokens_input || 0,
        output: r.tokens_output || 0,
        cacheRead: r.tokens_cache_read || 0,
        cacheWrite: r.tokens_cache_write || 0,
        reasoning: r.tokens_reasoning || 0,
        total,
      },
      events: [],
      costUsd: r.cost || 0,
      turns: 0,
      contextUsed: extra.context,
      indexOnly: true,
    });
  }
  return out;
}

function available() {
  return DB_CANDIDATES.some((p) => statSafe(p));
}

module.exports = {
  id: 'opencode',
  name: 'opencode',
  color: '#A78BFA',
  available,
  collect,
};
