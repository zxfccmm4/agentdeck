'use strict';
// AgentDeck 权限管理：
// 1. 维护实时待审批的 PermissionRequest（来自 Claude Code hook），支持在手机屏远程点击允许/拒绝
// 2. 维护审批历史记录（最近 60 条）
// 3. 解析会话与项目的权限配置（permissionMode、工具调用统计、allow/deny/ask 规则）
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { HOME, readJsonl, statSafe, shortPath } = require('./util');

const pending = new Map(); // id -> entry
const history = [];        // 最近 60 条已决策或超时的请求
const MAX_HISTORY = 60;
const WAIT_TIMEOUT_MS = 280 * 1000;

let onChange = () => {};
function setOnChange(fn) { onChange = typeof fn === 'function' ? fn : () => {}; }

function summarizeToolInput(toolName, input) {
  if (!input || typeof input !== 'object') return '';
  if (toolName === 'Bash') return String(input.command || input.description || '').slice(0, 240);
  if (toolName === 'Edit' || toolName === 'Write' || toolName === 'Read') {
    return String(input.file_path || input.path || '').slice(0, 240);
  }
  if (toolName === 'WebFetch') return String(input.url || '').slice(0, 240);
  for (const k of ['command', 'file_path', 'path', 'url', 'query', 'prompt', 'description']) {
    if (typeof input[k] === 'string' && input[k]) return `${k}: ${input[k].slice(0, 200)}`;
  }
  try { return JSON.stringify(input).slice(0, 220); } catch { return ''; }
}

function publicView(entry) {
  return {
    id: entry.id,
    agent: entry.agent,
    sessionId: entry.sessionId,
    cwd: entry.cwd,
    shortCwd: shortPath(entry.cwd, 36),
    permissionMode: entry.permissionMode,
    toolName: entry.toolName,
    summary: entry.summary,
    description: entry.description,
    suggestions: entry.suggestions,
    createdAt: entry.createdAt,
    status: entry.status,
    decision: entry.decision || null,
    decidedAt: entry.decidedAt || 0,
  };
}

function getPendingList() {
  return [...pending.values()].sort((a, b) => b.createdAt - a.createdAt).map(publicView);
}

function getHistoryList(sessionId) {
  const list = sessionId ? history.filter((h) => h.sessionId === sessionId) : history;
  return list.slice(0, 30).map(publicView);
}

function pushHistory(entry) {
  history.unshift(entry);
  if (history.length > MAX_HISTORY) history.length = MAX_HISTORY;
}

/**
 * 接收来自 Hook 的 PermissionRequest 并挂起等待用户在手机屏上点击，或客户端断开（用户在终端已处理）。
 */
function holdPermissionRequest(payload, req) {
  return new Promise((resolve) => {
    const id = 'perm_' + Date.now().toString(36) + '_' + crypto.randomBytes(3).toString('hex');
    const toolName = String(payload.tool_name || payload.toolName || 'Tool');
    const toolInput = payload.tool_input || payload.toolInput || {};
    const suggestions = Array.isArray(payload.permission_suggestions) ? payload.permission_suggestions : [];

    const entry = {
      id,
      agent: payload.agent || 'claude',
      sessionId: String(payload.session_id || payload.sessionId || ''),
      transcriptPath: String(payload.transcript_path || ''),
      cwd: String(payload.cwd || ''),
      permissionMode: String(payload.permission_mode || payload.permissionMode || 'default'),
      toolName,
      toolInput,
      summary: summarizeToolInput(toolName, toolInput),
      description: String(toolInput.description || ''),
      suggestions,
      createdAt: Date.now(),
      status: 'pending',
      decision: null,
      decidedAt: 0,
      finish: null,
    };

    let done = false;
    const finish = (hookOutput, status, decisionLabel) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      pending.delete(id);
      entry.status = status;
      entry.decision = decisionLabel;
      entry.decidedAt = Date.now();
      entry.finish = null;
      pushHistory(entry);
      onChange();
      resolve(hookOutput);
    };

    entry.finish = finish;
    const timer = setTimeout(() => {
      finish({}, 'expired', 'timeout');
    }, WAIT_TIMEOUT_MS);

    if (req) {
      req.on('close', () => {
        if (!done) finish({}, 'closed', 'terminal');
      });
    }

    pending.set(id, entry);
    onChange();
  });
}

/**
 * 手机端点击允许/拒绝：
 * action: "allow" | "allow_always" | "auto_mode" | "deny"
 */
function decidePermission(id, action, message) {
  const entry = pending.get(id);
  if (!entry || !entry.finish) return { ok: false, error: 'not_found_or_already_decided' };

  if (action === 'deny') {
    const out = {
      hookSpecificOutput: {
        hookEventName: 'PermissionRequest',
        decision: {
          behavior: 'deny',
          message: message || '已在 AgentDeck 桌面屏上拒绝该操作',
        },
      },
    };
    entry.finish(out, 'denied', 'deny');
    return { ok: true, id, decision: 'deny' };
  }

  const decision = { behavior: 'allow' };
  if (action === 'allow_always' && entry.suggestions.length > 0) {
    decision.updatedPermissions = entry.suggestions;
  } else if (action === 'auto_mode') {
    decision.updatedPermissions = [{ type: 'setMode', mode: 'auto', destination: 'session' }];
  }

  const out = {
    hookSpecificOutput: {
      hookEventName: 'PermissionRequest',
      decision,
    },
  };
  entry.finish(out, 'allowed', action || 'allow');
  return { ok: true, id, decision: action || 'allow' };
}

function readSettingsRules(file) {
  try {
    if (!statSafe(file)) return null;
    const j = JSON.parse(fs.readFileSync(file, 'utf8'));
    const p = j.permissions || {};
    return {
      file: shortPath(file, 42),
      defaultMode: p.defaultMode || j.permissionMode || null,
      allow: Array.isArray(p.allow) ? p.allow : [],
      deny: Array.isArray(p.deny) ? p.deny : [],
      ask: Array.isArray(p.ask) ? p.ask : [],
    };
  } catch { return null; }
}

function collectPermissionRules(cwd) {
  const files = [
    path.join(HOME, '.claude', 'settings.json'),
    path.join(HOME, '.claude', 'settings.local.json'),
  ];
  if (cwd && cwd !== HOME) {
    files.push(path.join(cwd, '.claude', 'settings.json'));
    files.push(path.join(cwd, '.claude', 'settings.local.json'));
  }
  const allow = [], deny = [], ask = [];
  let defaultMode = null;
  for (const f of files) {
    const r = readSettingsRules(f);
    if (!r) continue;
    if (r.defaultMode) defaultMode = r.defaultMode;
    for (const x of r.allow) if (!allow.includes(x)) allow.push(x);
    for (const x of r.deny) if (!deny.includes(x)) deny.push(x);
    for (const x of r.ask) if (!ask.includes(x)) ask.push(x);
  }
  return { defaultMode, allow, deny, ask };
}

/**
 * 解析单个会话文件的授权模式、模式切换历史、工具使用与拒绝记录。
 */
function parseSessionPermissions(file) {
  let permissionMode = 'default';
  let mode = 'normal';
  const modeChanges = [];
  const toolCounts = new Map();
  const recentTools = [];

  if (file && file.endsWith('.jsonl') && statSafe(file)) {
    const rows = readJsonl(file, 4000);
    const toolMap = new Map();
    for (const r of rows) {
      const ts = r.timestamp ? Date.parse(r.timestamp) : 0;
      if (r.type === 'permission-mode' && r.permissionMode) {
        if (r.permissionMode !== permissionMode) {
          modeChanges.push({ mode: r.permissionMode, at: ts });
        }
        permissionMode = r.permissionMode;
      }
      if (r.type === 'mode' && r.mode) mode = r.mode;
      if (r.permissionMode) permissionMode = r.permissionMode;

      const m = r.message;
      if (r.type === 'assistant' && m && Array.isArray(m.content)) {
        for (const c of m.content) {
          if (c && c.type === 'tool_use' && c.name) {
            toolCounts.set(c.name, (toolCounts.get(c.name) || 0) + 1);
            const item = {
              id: c.id || '',
              name: c.name,
              summary: summarizeToolInput(c.name, c.input),
              at: ts,
              status: 'ok',
            };
            if (item.id) toolMap.set(item.id, item);
            recentTools.push(item);
          }
        }
      }
      if (r.type === 'user' && m && Array.isArray(m.content)) {
        for (const c of m.content) {
          if (c && c.type === 'tool_result' && c.tool_use_id) {
            const hit = toolMap.get(c.tool_use_id);
            if (hit && c.is_error) {
              const txt = typeof c.content === 'string' ? c.content : JSON.stringify(c.content || '');
              hit.status = /permission|denied|declined|rejected|拒绝/i.test(txt) ? 'denied' : 'error';
            }
          }
        }
      }
    }
  }

  const tools = [...toolCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([name, count]) => ({ name, count }));

  return {
    permissionMode,
    mode,
    modeChanges: modeChanges.slice(-10),
    tools,
    recentTools: recentTools.slice(-20).reverse(),
  };
}

module.exports = {
  setOnChange,
  getPendingList,
  getHistoryList,
  holdPermissionRequest,
  decidePermission,
  collectPermissionRules,
  parseSessionPermissions,
};
