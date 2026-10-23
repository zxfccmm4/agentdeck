'use strict';
// 只靠进程探测的 agent：本机没有可解析的会话/用量文件，运行时在主屏出现，退出后隐藏。
// 匹配规则在 processes.js 的 PATTERNS 里，id 与这里保持一致。
const fs = require('fs');
const path = require('path');
const { HOME } = require('../util');

const has = (...ps) => ps.some((p) => { try { fs.accessSync(p); return true; } catch { return false; } });

function make(id, name, color, paths) {
  return { id, name, color, processOnly: true, available: () => has(...paths), collect: () => [] };
}

module.exports = [
  make('kiro', 'Kiro', '#9046FF', ['/Applications/Kiro.app', path.join(HOME, '.kiro')]),
  make('antigravity', 'Antigravity', '#4F8DF7', ['/Applications/Antigravity.app', path.join(HOME, '.gemini', 'antigravity')]),
  make('gemini', 'Gemini CLI', '#8E75FF', [path.join(HOME, '.gemini')]),
  make('codebuddy', 'CodeBuddy', '#6C5CE7', ['/Applications/CodeBuddy.app', path.join(HOME, '.codebuddy')]),
  make('copilot', 'Copilot CLI', '#E6EDF3', [path.join(HOME, '.copilot')]),
  make('cursor', 'Cursor', '#E6EDF3', ['/Applications/Cursor.app', path.join(HOME, '.cursor')]),
];
