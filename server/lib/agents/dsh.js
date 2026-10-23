'use strict';
// dsh（DeepSeek 桌面 Agent）适配器。
// 目前 dsh 是 Electron 桌面端，数据落在
//   ~/Library/Application Support/@deepseek-ai/dsh-desktop/
// 没有稳定的 token/usage 导出文件，因此这里只做两件事：
//   1. 判断本机是否装了 dsh（给出 available 状态）
//   2. 若存在可解析的 usage 缓存（例如用户自己导出的 usage.json），则读取
// 真实用量请通过 DSH_USAGE_FILE 环境变量指向导出的 JSON：
//   [{"title":"...","model":"...","input":1,"output":2,"cost":0.1,"updatedAt":"..."}]
const path = require('path');
const fs = require('fs');
const { HOME, statSafe } = require('../util');

const APP_SUPPORT = path.join(HOME, 'Library', 'Application Support', '@deepseek-ai', 'dsh-desktop');
const USAGE_FILE = process.env.DSH_USAGE_FILE || path.join(HOME, '.dsh', 'usage.json');

function available() {
  return statSafe(APP_SUPPORT) !== null || statSafe(USAGE_FILE) !== null || !!process.env.DSH_USAGE_FILE;
}

function collect() {
  const out = [];
  const file = statSafe(USAGE_FILE) ? USAGE_FILE : null;
  if (file) {
    let rows;
    try { rows = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { rows = null; }
    if (Array.isArray(rows)) {
      for (const r of rows) {
        const input = Number(r.input || r.inputTokens || 0);
        const output = Number(r.output || r.outputTokens || 0);
        const cacheRead = Number(r.cacheRead || 0);
        const total = input + output + cacheRead;
        if (!total && !r.title) continue;
        out.push({
          agent: 'dsh',
          sessionId: r.id || r.sessionId || r.title || 'dsh',
          file,
          title: String(r.title || r.sessionId || 'dsh 会话').slice(0, 90),
          cwd: r.cwd || '',
          model: r.model || 'deepseek',
          firstAt: r.createdAt ? Date.parse(r.createdAt) : 0,
          lastAt: r.updatedAt ? Date.parse(r.updatedAt) : Date.now(),
          tokens: { input, output, cacheRead, cacheWrite: 0, reasoning: Number(r.reasoning || 0), total },
          events: [],
          costUsd: Number(r.cost || r.costUsd || 0),
          turns: Number(r.turns || 0),
          contextUsed: input + cacheRead + output,
          indexOnly: true,
        });
      }
    }
  }
  if (!out.length && statSafe(APP_SUPPORT)) {
    const st = statSafe(APP_SUPPORT);
    out.push({
      agent: 'dsh',
      sessionId: 'dsh-desktop',
      file: APP_SUPPORT,
      title: 'dsh 桌面端（无用量导出）',
      cwd: '',
      model: 'deepseek',
      firstAt: st.ctimeMs,
      lastAt: st.mtimeMs,
      tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
      events: [],
      costUsd: 0,
      turns: 0,
      contextUsed: 0,
      note: true,
      indexOnly: true,
    });
  }
  return out;
}

module.exports = {
  id: 'dsh',
  name: 'dsh',
  color: '#4D6BFE',
  available,
  collect,
};
