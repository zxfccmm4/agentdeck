#!/usr/bin/env node
'use strict';
// 将 AgentDeck PermissionRequest Hook 写入 ~/.claude/settings.json
const fs = require('fs');
const path = require('path');

const settingsPath = path.join(process.env.HOME || '', '.claude', 'settings.json');
const hookCmd = `node ${path.join(__dirname, 'hook-permission.js')}`;

let data = {};
try {
  data = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
} catch (e) {
  console.error('读取 ~/.claude/settings.json 失败:', e.message);
  process.exit(1);
}

data.hooks = data.hooks || {};
data.hooks.PermissionRequest = Array.isArray(data.hooks.PermissionRequest) ? data.hooks.PermissionRequest : [];

const exists = data.hooks.PermissionRequest.some(
  (entry) => Array.isArray(entry.hooks) && entry.hooks.some((h) => h.command === hookCmd)
);

if (!exists) {
  fs.copyFileSync(settingsPath, settingsPath + '.agentdeck.bak');
  data.hooks.PermissionRequest.push({
    matcher: '*',
    hooks: [
      {
        type: 'command',
        command: hookCmd,
        timeout: 300,
        statusMessage: 'Waiting for AgentDeck or terminal approval...',
      },
    ],
  });
  fs.writeFileSync(settingsPath, JSON.stringify(data, null, 2) + '\n', 'utf8');
  console.log('✓ 已将 AgentDeck PermissionRequest Hook 写入 ~/.claude/settings.json');
} else {
  console.log('✓ ~/.claude/settings.json 中已包含 AgentDeck PermissionRequest Hook');
}
