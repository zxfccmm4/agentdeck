'use strict';
// 把各 agent 适配器的输出聚合成桌面屏需要的仪表盘数据。
const path = require('path');
const { startOfToday, hourlySeries, pct, bucketedSeries } = require('./util');
const { probe } = require('./processes');

const AGENTS = [
  require('./agents/claude'),
  require('./agents/codex'),
  require('./agents/pi'),
  require('./agents/opencode'),
  require('./agents/omo'),
  require('./agents/dsh'),
];

const AGENT_META = AGENTS.map((a) => ({ id: a.id, name: a.name, color: a.color }));

const ACTIVE_MS = 10 * 60 * 1000; // 10 分钟内有过活动 = 活跃

function signedCost(usd) {
  return Math.round(usd * 10000) / 10000;
}

function summarize(agent, sessions) {
  const today = startOfToday();
  const tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 };
  let costUsd = 0;
  let todayTokens = 0;
  let lastAt = 0;
  let activeSessions = 0;
  let turns = 0;
  const events = [];

  for (const s of sessions) {
    for (const k of Object.keys(tokens)) plusTokens(tokens, s.tokens, k);
    costUsd += s.costUsd || 0;
    turns += s.turns || 0;
    lastAt = Math.max(lastAt, s.lastAt || 0);
    if (s.lastAt && Date.now() - s.lastAt < ACTIVE_MS) activeSessions++;
    for (const ev of s.events || []) {
      events.push(ev);
      if (ev.at >= today) todayTokens += ev.tokens || 0;
    }
    // 没有逐条事件时（opencode/omo），用会话时间粗略归入今日
    if (!(s.events || []).length && s.lastAt >= today) todayTokens += s.tokens.total || 0;
  }

  const now = Date.now();
  const dayEvents = events.filter((e) => e.at >= today);
  const weekEvents = events.filter((e) => e.at >= today - 6 * 864e5);

  return {
    id: agent.id,
    status: activeSessions > 0 ? 'active' : (sessions.length ? 'idle' : (agent.available() ? 'empty' : 'missing')),
    sessions: sessions.length,
    activeSessions,
    turns,
    tokens,
    costUsd: signedCost(costUsd),
    todayTokens,
    weekTokens: weekEvents.reduce((a, e) => a + (e.tokens || 0), 0),
    lastAt,
    series: hourlySeries(dayEvents.length ? events : [], 12, now),
    top: sessions.slice(0, 6).map((s) => ({
      title: s.title,
      model: s.model,
      cwd: s.cwd,
      tokens: s.tokens.total,
      costUsd: signedCost(s.costUsd || 0),
      lastAt: s.lastAt,
      active: s.lastAt && now - s.lastAt < ACTIVE_MS,
      indexOnly: !!s.indexOnly,
      taskStatus: s.taskStatus || null,
    })),
  };
}

function plusTokens(acc, t, k) {
  if (!t) return;
  acc[k] = (acc[k] || 0) + (t[k] || 0);
}

async function buildDashboard({ host } = {}) {
  const procs = await probe();
  const procByAgent = new Map();
  for (const p of procs) {
    const list = procByAgent.get(p.agent) || [];
    list.push(p);
    procByAgent.set(p.agent, list);
  }

  const agents = [];
  let allEvents = [];
  const totals = {
    tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 },
    costUsd: 0, todayTokens: 0, sessions: 0, activeSessions: 0, turns: 0,
  };

  for (const mod of AGENTS) {
    let sessions = [];
    try { sessions = mod.collect(Date.now() - 30 * 864e5) || []; } catch (e) { sessions = []; }
    // 运行中的进程数也算活跃信号
    const procsForAgent = procByAgent.get(mod.id) || [];
    const summary = summarize(mod, sessions);
    if (procsForAgent.length && summary.status !== 'active') summary.status = 'active';
    summary.processes = procsForAgent.map((p) => ({
      pid: p.pid, uptimeSec: p.uptimeSec, cpu: p.cpu, rssMb: p.rssMb,
    }));
    summary.procCount = procsForAgent.length;
    summary.color = mod.color;
    summary.name = mod.name;

    for (const k of Object.keys(totals.tokens)) totals.tokens[k] += summary.tokens[k] || 0;
    totals.costUsd += summary.costUsd || 0;
    totals.todayTokens += summary.todayTokens || 0;
    totals.sessions += summary.sessions;
    totals.activeSessions += summary.activeSessions;
    totals.turns += summary.turns;
    allEvents = allEvents.concat(...sessions.map((s) => s.events || []));

    agents.push(summary);
  }

  // 各 agent 用量占比（环形图）
  const grand = totals.tokens.total || 1;
  for (const a of agents) a.share = pct(a.tokens.total, grand);

  const today = startOfToday();
  const series = hourlySeries(allEvents.filter((e) => e.at >= today), 12);

  const recent = agents
    .flatMap((a) => a.top.map((t) => ({ ...t, agent: a.id, agentName: a.name, color: a.color })))
    .sort((x, y) => (y.lastAt || 0) - (x.lastAt || 0))
    .slice(0, 8);

  return {
    host: host || 'local',
    generatedAt: Date.now(),
    agents,
    totals: {
      ...totals,
      costUsd: signedCost(totals.costUsd),
      activeAgents: agents.filter((a) => a.status === 'active').length,
      knownAgents: agents.filter((a) => a.status !== 'missing').length,
    },
    series,
    recent,
    processes: procs.length,
  };
}

// 单个 agent 的会话详情 + 走势数据（供 App 详情页 / 走势图使用）
function agentDetail(id) {
  const mod = AGENTS.find((m) => m.id === id);
  if (!mod) return null;
  let sessions = [];
  try { sessions = mod.collect(0) || []; } catch (e) { sessions = []; }
  const now = Date.now();

  const sorted = sessions.slice().sort((a, b) => b.lastAt - a.lastAt);
  const events = sessions.flatMap((s) => s.events || []);
  const today = startOfToday();

  const hourly = hourlySeries(events.filter((e) => e.at >= today - 6 * 864e5), 72, now); // 近 72 小时
  const daily = bucketedSeries(events, 24 * 3600 * 1000, 30, now);                       // 近 30 天

  const list = sorted.slice(0, 40).map((s) => ({
    sessionId: s.sessionId,
    title: s.title,
    model: s.model,
    cwd: s.cwd,
    file: s.file,
    tokens: s.tokens,
    costUsd: signedCost(s.costUsd || 0),
    turns: s.turns,
    firstAt: s.firstAt,
    lastAt: s.lastAt,
    active: s.lastAt && now - s.lastAt < ACTIVE_MS,
    taskStatus: s.taskStatus || null,
    indexOnly: !!s.indexOnly,
  }));
  list.forEach((s) => { s.costUsd = signedCost(s.costUsd); });

  const t = list.reduce((acc, s) => {
    for (const k of Object.keys(acc.tokens)) acc.tokens[k] += s.tokens[k] || 0;
    acc.costUsd += s.costUsd || 0;
    acc.sessions++;
    return acc;
  }, { tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, total: 0 }, costUsd: 0, sessions: 0 });

  return {
    id: mod.id,
    name: mod.name,
    color: mod.color,
    available: mod.available(),
    sessions: list,
    series: {
      hourly: { labels: labels(hourly.length, 3600 * 1000, now), data: hourly },
      daily: { labels: labels(daily.length, 24 * 3600 * 1000, now), data: daily },
    },
    totals: { ...t, costUsd: signedCost(t.costUsd) },
  };
}

// 生成时间桶标签（旧->新）
function labels(n, bucketMs, now = Date.now()) {
  const end = Math.floor(now / bucketMs) * bucketMs;
  const out = [];
  const d = new Date();
  const isHour = bucketMs < 24 * 3600 * 1000;
  for (let i = 0; i < n; i++) {
    const t = end - (n - 1 - i) * bucketMs;
    d.setTime(t);
    if (isHour) {
      const h = d.getHours();
      if (h === 0) out.push(`${d.getMonth() + 1}/${d.getDate()}`);
      else if (h % 6 === 0) out.push(String(h));
      else out.push('');
    } else {
      out.push(`${d.getMonth() + 1}/${d.getDate()}`);
    }
  }
  return out;
}

module.exports = { buildDashboard, AGENT_META, AGENTS, agentDetail };
