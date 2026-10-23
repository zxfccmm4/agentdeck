'use strict';
// pi adapter: ~/.pi/agent/sessions/<slug>/<ts>_<id>.jsonl
// message events carry usage {input, output, cacheRead, cacheWrite, reasoning, totalTokens}
const path = require('path');
const { HOME, walk, readJsonl, statSafe, shortPath } = require('../util');

const ROOT = path.join(HOME, '.pi', 'agent', 'sessions');
const cache = new Map();

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    const t = content.find((c) => c && c.type === 'text');
    return t ? t.text : '';
  }
  return '';
}

function parseSession(file, mtime) {
  const cached = cache.get(file);
  if (cached && cached.mtime === mtime) return cached.value;

  const rows = readJsonl(file, 4000);
  let sessionId = path.basename(file, '.jsonl').split('_').pop();
  let cwd = '';
  let model = '';
  let provider = '';
  let title = '';
  let firstAt = 0, lastAt = 0;
  const tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 };
  const events = [];
  let costUsd = 0;
  let contextUsed = 0;
  let turns = 0;

  for (const r of rows) {
    const ts = r.timestamp ? Date.parse(r.timestamp) : 0;
    if (ts) { if (!firstAt) firstAt = ts; lastAt = Math.max(lastAt, ts); }
    if (r.type === 'session') { if (r.id) sessionId = r.id; if (r.cwd) cwd = r.cwd; }
    if (r.type === 'model_change') { provider = r.provider || provider; model = r.modelId || model; }
    if (r.type === 'message' && r.message) {
      const msg = r.message;
      if (msg.role === 'user' && !title) {
        const t = textOf(msg.content).replace(/\s+/g, ' ').trim();
        if (t && !t.startsWith('<')) title = t.slice(0, 90);
      }
      const u = msg.usage;
      if (u) {
        const inTok = u.input || 0;
        const out = u.output || 0;
        const cr = u.cacheRead || 0;
        const cw = u.cacheWrite || 0;
        tokens.input += inTok; tokens.output += out;
        tokens.cacheRead += cr; tokens.cacheWrite += cw;
        tokens.reasoning += u.reasoning || 0;
        if (u.cost && u.cost.total) costUsd += u.cost.total;
        contextUsed = inTok + cr + cw + out;
        turns++;
        events.push({ at: ts || lastAt, tokens: inTok + out + cr + cw });
      }
    }
  }

  if (!title) title = cwd ? shortPath(cwd, 48) : 'pi 会话';
  tokens.total = tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
  const value = {
    agent: 'pi', sessionId, file, title, cwd, model: provider && model ? `${provider}/${model}` : (model || 'pi'),
    firstAt, lastAt, tokens, events, costUsd, turns, contextUsed,
  };
  cache.set(file, { mtime, value });
  return value;
}

function collect() {
  const files = walk(ROOT, '.jsonl', 0);
  const sessions = [];
  for (const f of files) {
    const st = statSafe(f.path);
    if (!st) continue;
    const rec = parseSession(f.path, st.mtimeMs);
    if (rec.tokens.total > 0 || Date.now() - st.mtimeMs < 30 * 60 * 1000) sessions.push(rec);
  }
  sessions.sort((a, b) => b.lastAt - a.lastAt);
  return sessions;
}

module.exports = {
  id: 'pi',
  name: 'pi',
  color: '#6EE7B7',
  available: () => statSafe(ROOT) !== null,
  collect,
};
