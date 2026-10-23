'use strict';
// AgentDeck 采集与推送服务。
// 用途：给横向摆放的安卓桌面屏提供"正在运行的 agent + 用量"数据。
//
// 用法：
//   node server.js                 # 监听 0.0.0.0:7777，端口可用 PORT 覆盖
//   node server.js --port 7788
//   node server.js --remote        # 额外聚合配置里的远端主机
//
// 接口：
//   GET /api/state            完整快照（JSON）
//   GET /api/stream           SSE，每秒推送一次变化
//   GET /api/health           健康检查
//   GET /                     简易 HTML 预览（浏览器直接看）
let http = require('http');
let path = require('path');
let fs = require('fs');
const os = require('os');
const { buildDashboard, AGENT_META, agentDetail, sessionPermissionDetail } = require('./lib/dashboard');
const {
  setOnChange,
  getPendingList,
  getHistoryList,
  holdPermissionRequest,
  decidePermission,
} = require('./lib/permissions');

const PORT = Number(process.env.PORT || argValue('--port') || 7777);
const HOST = process.env.HOST || '0.0.0.0';
const CONFIG = loadConfig();
const REMOTES = Array.isArray(CONFIG.remotes) ? CONFIG.remotes : [];

function argValue(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : null;
}

function loadConfig() {
  const candidates = [
    path.join(process.env.HOME || '', '.agentdeck', 'config.json'),
    path.join(__dirname, 'config.json'),
  ];
  for (const c of candidates) {
    try { return JSON.parse(fs.readFileSync(c, 'utf8')); } catch { /* next */ }
  }
  return {};
}

function hostLabel() {
  return CONFIG.hostName || os.hostname().replace(/\.local$/, '');
}

// ---- 远端主机：抓对方 /api/state，失败则标记离线 ----
async function fetchRemote(remote) {
  const url = remote.url.replace(/\/$/, '') + '/api/state';
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), remote.timeoutMs || 2500);
  try {
    const res = await fetch(url, { signal: ctrl.signal, headers: { 'x-agentdeck': '1' } });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const json = await res.json();
    return { ...json, host: remote.name || json.host || url, remote: true, online: true };
  } catch (e) {
    return {
      host: remote.name || url,
      remote: true,
      online: false,
      error: String(e.message || e),
      generatedAt: Date.now(),
      agents: AGENT_META.map((m) => ({
        ...m, status: 'offline', sessions: 0, activeSessions: 0, turns: 0,
        tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
        costUsd: 0, todayTokens: 0, weekTokens: 0, lastAt: 0, series: new Array(12).fill(0),
        top: [], processes: [], procCount: 0, share: 0,
      })),
      totals: {
        tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
        costUsd: 0, todayTokens: 0, sessions: 0, activeSessions: 0, turns: 0,
        activeAgents: 0, knownAgents: 0,
      },
      series: new Array(12).fill(0),
      recent: [],
    };
  } finally {
    clearTimeout(timer);
  }
}

let snapshot = null;
let snapshotAt = 0;
let building = null;
const sseClients = new Set();

function mergeHosts(local, remotes) {
  const hosts = [local, ...remotes];
  const totals = {
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
    costUsd: 0, todayTokens: 0, sessions: 0, activeSessions: 0, turns: 0,
    activeAgents: 0, knownAgents: 0,
  };
  const series = new Array(12).fill(0);
  const recent = [];
  const agents = new Map();

  for (const h of hosts) {
    if (!h) continue;
    for (const k of Object.keys(totals.tokens)) totals.tokens[k] += (h.totals?.tokens?.[k] || 0);
    totals.costUsd += h.totals?.costUsd || 0;
    totals.todayTokens += h.totals?.todayTokens || 0;
    totals.sessions += h.totals?.sessions || 0;
    totals.activeSessions += h.totals?.activeSessions || 0;
    totals.turns += h.totals?.turns || 0;
    totals.activeAgents += h.online === false ? 0 : (h.totals?.activeAgents || 0);
    totals.knownAgents += h.online === false ? 0 : (h.totals?.knownAgents || 0);
    (h.series || []).forEach((v, i) => { if (i < series.length) series[i] += v; });
    for (const r of h.recent || []) recent.push({ ...r, host: h.host });
    for (const a of h.agents || []) {
      const cur = agents.get(a.id) || {
        id: a.id, name: a.name, color: a.color,
        status: a.status || 'missing', sessions: 0, activeSessions: 0, turns: 0,
        tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
        costUsd: 0, todayTokens: 0, weekTokens: 0, lastAt: 0,
        series: new Array(12).fill(0), top: [], procCount: 0, processes: [], hosts: [],
      };
      for (const k of Object.keys(cur.tokens)) cur.tokens[k] += a.tokens?.[k] || 0;
      cur.sessions += a.sessions || 0;
      cur.activeSessions += a.activeSessions || 0;
      cur.turns += a.turns || 0;
      cur.costUsd += a.costUsd || 0;
      cur.todayTokens += a.todayTokens || 0;
      cur.weekTokens += a.weekTokens || 0;
      cur.procCount += a.procCount || 0;
      // 带上进程运行时长，App 卡片显示「已运行 xx」
      for (const p of a.processes || []) cur.processes.push({ ...p, host: h.host });
      cur.lastAt = Math.max(cur.lastAt, a.lastAt || 0);
      (a.series || []).forEach((v, i) => { if (i < cur.series.length) cur.series[i] += v; });
      if (a.status === 'active') cur.status = 'active';
      else if (cur.status !== 'active' && a.status && a.status !== 'missing') cur.status = a.status;
      cur.hosts.push({ host: h.host, online: h.online !== false, status: a.status, procCount: a.procCount || 0 });
      if ((a.top || []).length) cur.top = cur.top.concat(a.top.map((t) => ({ ...t, host: h.host })));
      agents.set(a.id, cur);
    }
  }

  const list = [...agents.values()];
  const grand = totals.tokens.total || 1;
  for (const a of list) {
    a.share = Math.max(0, Math.min(100, Math.round((a.tokens.total / grand) * 100)));
    a.top = a.top.sort((x, y) => (y.lastAt || 0) - (x.lastAt || 0)).slice(0, 8);
    a.costUsd = Math.round(a.costUsd * 10000) / 10000;
  }
  recent.sort((x, y) => (y.lastAt || 0) - (x.lastAt || 0));

  return {
    generatedAt: Date.now(),
    hosts: hosts.filter(Boolean).map((h) => ({
      host: h.host, remote: !!h.remote, online: h.online !== false,
      activeAgents: h.totals?.activeAgents || 0,
      todayTokens: h.totals?.todayTokens || 0,
      costUsd: h.totals?.costUsd || 0,
    })),
    agents: list.sort((a, b) => b.tokens.total - a.tokens.total),
    totals: { ...totals, costUsd: Math.round(totals.costUsd * 10000) / 10000 },
    series,
    recent: recent.slice(0, 10),
    agentMeta: AGENT_META,
    pendingPermissions: getPendingList(),
    permissionHistory: getHistoryList().slice(0, 8),
  };
}

async function refresh() {
  const local = await buildDashboard({ host: hostLabel() });
  const remotes = REMOTES.length ? await Promise.all(REMOTES.map(fetchRemote)) : [];
  snapshot = mergeHosts(local, remotes);
  snapshotAt = Date.now();
  return snapshot;
}

function ensureFresh(maxAgeMs = 900) {
  if (snapshot && Date.now() - snapshotAt < maxAgeMs) return Promise.resolve(snapshot);
  if (!building) building = refresh().finally(() => { building = null; });
  return building;
}

function broadcast() {
  if (!sseClients.size || !snapshot) return;
  const data = `data: ${JSON.stringify(snapshot)}\n\n`;
  for (const res of sseClients) {
    try { res.write(data); } catch { sseClients.delete(res); }
  }
}

// 每秒推一次
setInterval(async () => {
  try { await refresh(); broadcast(); } catch (e) { console.error('[agentdeck] refresh failed', e.message); }
}, 1000);

setOnChange(() => {
  refresh().then(() => broadcast()).catch(() => {});
});

function readBodyJson(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.setEncoding('utf8');
    req.on('data', (c) => {
      raw += c;
      if (raw.length > 512 * 1024) { req.destroy(); reject(new Error('payload too large')); }
    });
    req.on('end', () => {
      if (!raw.trim()) return resolve({});
      try { resolve(JSON.parse(raw)); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

const PREVIEW = `<!doctype html><html><head><meta charset="utf-8"><title>AgentDeck</title>
<style>body{background:#0b0e14;color:#e6edf3;font:14px/1.5 -apple-system,sans-serif;margin:0;padding:24px}
h1{font-size:18px;font-weight:600;margin:0 0 16px}.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}
.c{background:#151a23;border:1px solid #222b38;border-radius:10px;padding:14px}
.c h2{font-size:14px;margin:0 0 8px;display:flex;justify-content:space-between}
.s{font-size:12px;color:#8b949e}pre{white-space:pre-wrap;font-size:12px;color:#8b949e}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block}</style></head>
<body><h1>AgentDeck · <span id="host"></span></h1><div class="g" id="grid"></div>
<script>
const es = new EventSource('/api/stream');
es.onmessage = (e) => {
  const s = JSON.parse(e.data);
  document.getElementById('host').textContent = (s.hosts||[]).map(h=>h.host+(h.online?'':' (离线)')).join(' · ');
  document.getElementById('grid').innerHTML = s.agents.map(a => \`
    <div class="c"><h2><span>\${a.name}</span><span class="dot" style="background:\${a.status==='active'?'#3fb950':'#30363d'}"></span></h2>
    <div class="s">状态 \${a.status} · 会话 \${a.sessions} · 进程 \${a.procCount}</div>
    <div class="s">Token \${(a.tokens.total||0).toLocaleString()}</div>
    <div class="s">今日 \${(a.todayTokens||0).toLocaleString()} · 花费 $\${(a.costUsd||0).toFixed(4)}</div></div>\`).join('');
};
</script></body></html>`;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body, type = 'application/json; charset=utf-8') => {
    res.writeHead(code, {
      'content-type': type,
      'access-control-allow-origin': '*',
      'cache-control': 'no-store',
    });
    res.end(body);
  };

  if (url.pathname === '/api/health') {
    return send(200, JSON.stringify({ ok: true, host: hostLabel(), uptime: process.uptime() }));
  }

  if (url.pathname === '/api/state') {
    try { return send(200, JSON.stringify(await ensureFresh())); }
    catch (e) { return send(500, JSON.stringify({ error: e.message })); }
  }

  if (url.pathname.startsWith('/api/agents/')) {
    const parts = url.pathname.split('/').filter(Boolean); // ['api', 'agents', id, ...]
    const id = decodeURIComponent(parts[2] || '');
    if (parts[3] === 'sessions' && parts[4]) {
      const sid = decodeURIComponent(parts[4]);
      const sDetail = sessionPermissionDetail(id, sid);
      if (!sDetail) return send(404, JSON.stringify({ error: 'session not found' }));
      return send(200, JSON.stringify(sDetail));
    }
    const detail = agentDetail(id);
    if (!detail) return send(404, JSON.stringify({ error: 'unknown agent: ' + id }));
    return send(200, JSON.stringify(detail));
  }

  if (url.pathname === '/api/permissions' && req.method === 'GET') {
    return send(200, JSON.stringify({
      pending: getPendingList(),
      history: getHistoryList(),
    }));
  }

  if (url.pathname === '/api/permissions/hook' && req.method === 'POST') {
    try {
      const payload = await readBodyJson(req);
      const hookOut = await holdPermissionRequest(payload, req);
      return send(200, JSON.stringify(hookOut || {}));
    } catch (e) {
      return send(200, JSON.stringify({}));
    }
  }

  if (url.pathname === '/api/permissions/decide' && req.method === 'POST') {
    try {
      const body = await readBodyJson(req);
      const id = String(body.id || url.searchParams.get('id') || '');
      const action = String(body.action || url.searchParams.get('action') || 'allow');
      const result = decidePermission(id, action, body.message);
      return send(result.ok ? 200 : 404, JSON.stringify(result));
    } catch (e) {
      return send(400, JSON.stringify({ ok: false, error: e.message }));
    }
  }

  if (url.pathname === '/api/stream') {
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'access-control-allow-origin': '*',
    });
    res.write(': connected\n\n');
    sseClients.add(res);
    try { res.write(`data: ${JSON.stringify(await ensureFresh())}\n\n`); } catch {}
    req.on('close', () => sseClients.delete(res));
    return;
  }

  if (url.pathname === '/' || url.pathname === '/index.html') {
    return send(200, PREVIEW, 'text/html; charset=utf-8');
  }

  send(404, JSON.stringify({ error: 'not found' }));
});

server.listen(PORT, HOST, () => {
  const addrs = Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`[agentdeck] 监听 http://${HOST}:${PORT}`);
  for (const a of addrs) console.log(`[agentdeck]   http://${a}:${PORT}`);
  if (REMOTES.length) console.log(`[agentdeck] 远端主机 ${REMOTES.length} 台: ${REMOTES.map((r) => r.name || r.url).join(', ')}`);
});
