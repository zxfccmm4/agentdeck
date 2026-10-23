#!/usr/bin/env node
'use strict';
// Claude Code PermissionRequest Hook -> AgentDeck Server
// 当 Claude Code 需要工具授权时，将请求发给本机 AgentDeck 服务端（127.0.0.1:7777），
// 手机桌面屏即可弹出审批卡片。若服务端未运行或未在手机上点击，返回 {} 让终端走原有弹窗。
const http = require('http');

const PORT = Number(process.env.AGENTDECK_PORT || 7777);
let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', () => {
  if (!raw.trim()) {
    process.stdout.write('{}\n');
    return;
  }
  const req = http.request({
    hostname: '127.0.0.1',
    port: PORT,
    path: '/api/permissions/hook',
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'content-length': Buffer.byteLength(raw),
    },
    timeout: 290 * 1000,
  }, (res) => {
    let body = '';
    res.setEncoding('utf8');
    res.on('data', (c) => { body += c; });
    res.on('end', () => {
      if (res.statusCode === 200 && body.trim().startsWith('{')) {
        process.stdout.write(body.trim() + '\n');
      } else {
        process.stdout.write('{}\n');
      }
    });
  });

  req.on('error', () => { process.stdout.write('{}\n'); });
  req.on('timeout', () => { req.destroy(); process.stdout.write('{}\n'); });
  req.write(raw);
  req.end();
});
