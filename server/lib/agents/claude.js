'use strict';
// Claude Code adapter: ~/.claude/projects/<slug>/<session>.jsonl
// Assistant messages carry per-turn usage; cost-state lines carry cumulative cost.
const path = require('path');
const { HOME, walk, readJsonl, statSafe, shortPath } = require('../util');

const ROOT = path.join(HOME, '.claude', 'projects');
const cache = new Map();

function parseSession(file, mtime) {
  const cached = cache.get(file);
  if (cached && cached.mtime === mtime) return cached.value;

  const rows = readJsonl(file, 6000);
  let title = '';
  let cwd = '';
  let model = '';
  let sessionId = path.basename(file, '.jsonl');
  let firstAt = 0, lastAt = 0;
  const tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 };
  const events = [];
  let costUsd = 0;
  let contextUsed = 0;
  let turns = 0;
  let permissionMode = 'default';
  const toolCounts = new Map();

  for (const r of rows) {
    const ts = r.timestamp ? Date.parse(r.timestamp) : 0;
    if (ts) {
      if (!firstAt) firstAt = ts;
      lastAt = Math.max(lastAt, ts);
    }
    if (r.cwd && !cwd) cwd = r.cwd;
    if (r.sessionId) sessionId = r.sessionId;
    if (r.type === 'permission-mode' && r.permissionMode) permissionMode = r.permissionMode;
    if (r.permissionMode) permissionMode = r.permissionMode;
    if (r.type === 'last-prompt' && r.lastPrompt) title = String(r.lastPrompt).replace(/\s+/g, ' ').slice(0, 90);
    if (r.type === 'summary' && r.summary) title = String(r.summary).slice(0, 90);

    const m = r.message;
    if (r.type === 'assistant' && m) {
      if (m.model) model = m.model;
      if (r.requestedModel) model = r.requestedModel;
      if (Array.isArray(m.content)) {
        for (const c of m.content) {
          if (c && c.type === 'tool_use' && c.name) {
            toolCounts.set(c.name, (toolCounts.get(c.name) || 0) + 1);
          }
        }
      }
      const u = m.usage;
      if (u) {
        const out = u.output_tokens || 0;
        const think = (u.output_tokens_details && u.output_tokens_details.thinking_tokens) || 0;
        const inTok = u.input_tokens || 0;
        const cr = u.cache_read_input_tokens || 0;
        const cw = u.cache_creation_input_tokens || 0;
        tokens.input += inTok;
        tokens.output += out;
        tokens.cacheRead += cr;
        tokens.cacheWrite += cw;
        tokens.reasoning += think;
        turns++;
        contextUsed = inTok + cr + cw + out;
        const at = ts || lastAt;
        if (at) events.push({ at, tokens: inTok + out + cr + cw });
      }
    }
    if (r.type === 'cost-state' && typeof r.totalCostUSD === 'number') {
      costUsd = Math.max(costUsd, r.totalCostUSD);
    }
  }

  if (!title) title = cwd ? shortPath(cwd, 48) : 'Claude 会话';
  tokens.total = tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;

  const tools = [...toolCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, count]) => ({ name, count }));

  const value = {
    agent: 'claude', sessionId, file, title, cwd, model: model || 'claude',
    firstAt, lastAt, tokens, events, costUsd: costUsd || 0, turns, contextUsed,
    permissionMode, tools,
  };
  cache.set(file, { mtime, value });
  return value;
}

function collect() {
  const files = walk(ROOT, '.jsonl', 0);
  const sessions = [];
  for (const f of files) {
    const st = statSafe(f.path);
    const rec = parseSession(f.path, st.mtimeMs);
    if (rec.tokens.total > 0 || Date.now() - st.mtimeMs < 30 * 60 * 1000) sessions.push(rec);
  }
  sessions.sort((a, b) => b.lastAt - a.lastAt);
  return sessions;
}

module.exports = {
  id: 'claude',
  name: 'Claude Code',
  color: '#D97757',
  available: () => statSafe(ROOT) !== null,
  collect,
};
