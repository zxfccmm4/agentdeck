'use strict';
// WorkBuddy（腾讯桌面 Agent）适配器。
//   ~/.workbuddy/workbuddy.db       sessions 表：标题 / 模型 / 状态 / 时间
//   ~/.workbuddy/traces/<pid>/*.json 每次 Agent workflow 一份 trace，trace.modelInfo 带
//                                   totalInputTokens / totalOutputTokens / totalCachedTokens，
//                                   trace.sessionId 关联会话，endedAt 作为用量事件时间。
// 进程探测在 processes.js（WorkBuddy.app / WorkBuddy AI.app）。
const fs = require('fs');
const path = require('path');
const { HOME, statSafe, shortPath } = require('../util');

const ROOT = path.join(HOME, '.workbuddy');
const DB = path.join(ROOT, 'workbuddy.db');
const TRACES = path.join(ROOT, 'traces');

let db = null;
function openDb() {
  if (db) return db;
  if (!statSafe(DB)) return null;
  try {
    const { DatabaseSync } = require('node:sqlite');
    db = new DatabaseSync(DB, { readOnly: true });
    return db;
  } catch { return null; }
}

// trace 文件写完就不再变，按路径 + mtime 缓存解析结果
const traceCache = new Map();
function readTraces() {
  const out = [];
  let dirs = [];
  try { dirs = fs.readdirSync(TRACES); } catch { return out; }
  for (const d of dirs) {
    let files = [];
    try { files = fs.readdirSync(path.join(TRACES, d)); } catch { continue; }
    for (const f of files) {
      if (!f.endsWith('.json')) continue;
      const p = path.join(TRACES, d, f);
      const st = statSafe(p);
      if (!st) continue;
      const hit = traceCache.get(p);
      if (hit && hit.mtime === st.mtimeMs) { if (hit.rec) out.push(hit.rec); continue; }
      let rec = null;
      try {
        const t = JSON.parse(fs.readFileSync(p, 'utf8')).trace || {};
        const mi = t.modelInfo;
        if (t.sessionId && mi) {
          const input = mi.totalInputTokens || 0;
          const cached = Math.min(input, mi.totalCachedTokens || 0);
          rec = {
            sessionId: t.sessionId,
            at: Date.parse(t.endedAt || t.startedAt) || st.mtimeMs,
            model: (mi.models || [])[0] || '',
            input: input - cached,
            cacheRead: cached,
            output: mi.totalOutputTokens || 0,
            calls: mi.callCount || 0,
          };
        }
      } catch { /* 正在写入的 trace */ }
      traceCache.set(p, { mtime: st.mtimeMs, rec });
      if (rec) out.push(rec);
    }
  }
  return out;
}

function collect(sinceMs) {
  const conn = openDb();
  if (!conn) return [];
  let rows;
  try {
    rows = conn.prepare(
      `select id, title, custom_title, cwd, status, model, created_at, updated_at, last_activity_at
         from sessions
        where deleted_at is null and last_activity_at >= ?
        order by last_activity_at desc limit 60`
    ).all(Math.floor(sinceMs || Date.now() - 90 * 864e5));
  } catch { return []; }

  const bySession = new Map();
  for (const t of readTraces()) {
    const list = bySession.get(t.sessionId) || [];
    list.push(t);
    bySession.set(t.sessionId, list);
  }

  return rows.map((r) => {
    const traces = (bySession.get(r.id) || []).sort((a, b) => a.at - b.at);
    const tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 };
    const events = [];
    let turns = 0;
    for (const t of traces) {
      tokens.input += t.input;
      tokens.output += t.output;
      tokens.cacheRead += t.cacheRead;
      turns += t.calls;
      events.push({ at: t.at, tokens: t.input + t.output + t.cacheRead });
    }
    tokens.total = tokens.input + tokens.output + tokens.cacheRead;
    const lastTrace = traces[traces.length - 1];
    const model = (lastTrace && lastTrace.model) || (r.model || '').replace(/^custom-local:/, '');
    return {
      agent: 'workbuddy',
      sessionId: r.id,
      file: DB,
      title: r.custom_title || r.title || shortPath(r.cwd, 48) || 'WorkBuddy 会话',
      cwd: r.cwd || '',
      model,
      firstAt: r.created_at || 0,
      lastAt: r.last_activity_at || r.updated_at || 0,
      tokens,
      events,
      costUsd: 0,
      turns,
      taskStatus: r.status || null,
      indexOnly: !traces.length,
    };
  });
}

module.exports = {
  id: 'workbuddy',
  name: 'WorkBuddy',
  color: '#3B82F6',
  available: () => statSafe(ROOT) !== null,
  collect,
};
