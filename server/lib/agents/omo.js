'use strict';
// omo（oh-my-openagent）任务视图：~/.omo/tasks/*.json
// omo 的会话主体与 opencode 共用存储（见 opencode.js 的 agent 分类），
// 这里补充它自己维护的 task 列表，用来显示"正在做什么"。
const path = require('path');
const fs = require('fs');
const { HOME, statSafe, shortPath } = require('../util');

const ROOT = path.join(HOME, '.omo', 'tasks');

function collect() {
  if (!statSafe(ROOT)) return [];
  let names;
  try { names = fs.readdirSync(ROOT).filter((n) => n.endsWith('.json')); } catch { return []; }
  const out = [];
  for (const n of names) {
    const file = path.join(ROOT, n);
    const st = statSafe(file);
    if (!st) continue;
    let j;
    try { j = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { continue; }
    const status = String(j.status || '').toLowerCase();
    if (status === 'completed' || status === 'cancelled') continue;
    out.push({
      agent: 'omo',
      sessionId: j.threadID || j.id,
      taskId: j.id,
      file,
      title: j.subject || j.activeForm || 'omo 任务',
      cwd: '',
      model: 'omo',
      firstAt: st.birthtimeMs || st.ctimeMs,
      lastAt: st.mtimeMs,
      tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
      events: [],
      costUsd: 0,
      turns: 0,
      contextUsed: 0,
      taskStatus: status || 'pending',
      indexOnly: true,
    });
  }
  return out;
}

module.exports = {
  id: 'omo',
  name: 'omo',
  color: '#F472B6',
  available: () => statSafe(ROOT) !== null,
  collect,
};
