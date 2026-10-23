'use strict';
// 运行中进程探测：判断哪些 agent 现在真的在跑（而不仅是历史会话）。
const { execFile } = require('child_process');

const PATTERNS = [
  { agent: 'claude', re: /(^|\/|\s)(claude)(\s|$)/i, exclude: /claude-hook|claude-rtk/i },
  { agent: 'codex', re: /(^|\/|\s)(codex)(\s|$)/i, exclude: /codex-threadripper|chrome-extension|extension-host|Codex\.app|codex-lsp|app-server/i },
  { agent: 'pi', re: /(^|\/)(pi|pi-coding-agent)(\s|$)/i, exclude: /pip\d|piper|piapp|spike/i },
  { agent: 'opencode', re: /(^|\/)(opencode|opencode2)(\s|$)/i, exclude: /node_modules/i },
  { agent: 'omo', re: /(^|\/)(omo)(\s|$)/i, exclude: /node_modules|omo\.jsonc/i },
  { agent: 'dsh', re: /dsh-desktop|@deepseek-ai/i, exclude: /GPU|Cache|Helper \(Renderer\)/i },
  // 只认主进程，Electron 的 Helper 子进程不重复计数
  { agent: 'kiro', re: /Kiro\.app\/Contents\/MacOS\/|(^|\/)kiro-cli(\s|$)/, exclude: /Helper|crashpad/i },
  { agent: 'antigravity', re: /Antigravity\.app\/Contents\/MacOS\/|antigravity-cli\/bin\//, exclude: /Helper|crashpad|language_server/i },
  { agent: 'gemini', re: /(^|\/|\s)gemini(\s|$)|@google\/gemini-cli/, exclude: /gemini-web|magpie|node_modules\/\.bin/i },
  { agent: 'codebuddy', re: /CodeBuddy\.app\/Contents\/MacOS\/|(^|\/|\s)codebuddy(\s|$)|@tencent-ai\/codebuddy/, exclude: /Helper|crashpad|Extension/i },
  { agent: 'copilot', re: /(^|\/|\s)copilot(\s|$)|@github\/copilot\b/, exclude: /copilot-language-server|Helper|CopilotForXcode/i },
  { agent: 'cursor', re: /Cursor\.app\/Contents\/MacOS\/|(^|\/)cursor-agent(\s|$)/, exclude: /Helper|crashpad|CursorUIViewService/i },
  { agent: 'workbuddy', re: /WorkBuddy( AI)?\.app\/Contents\/MacOS\/|\.workbuddy\/binaries\//, exclude: /Helper|crashpad/i },
];

function parseRows(stdout) {
  const lines = stdout.split('\n').slice(1).filter(Boolean);
  const rows = [];
  for (const line of lines) {
    const m = line.match(/^\s*(\d+)\s+(\S+)\s+([\d.]+)\s+(\d+)\s+(.*)$/);
    if (!m) continue;
    rows.push({ pid: Number(m[1]), etime: m[2], cpu: Number(m[3]), rssKb: Number(m[4]), command: m[5] });
  }
  return rows;
}

// etime: [[dd-]hh:]mm:ss -> seconds
function etimeToSeconds(etime) {
  let days = 0;
  let rest = etime;
  if (rest.includes('-')) { const p = rest.split('-'); days = Number(p[0]); rest = p[1]; }
  const parts = rest.split(':').map(Number);
  let s = 0;
  if (parts.length === 3) s = parts[0] * 3600 + parts[1] * 60 + parts[2];
  else if (parts.length === 2) s = parts[0] * 60 + parts[1];
  else s = parts[0] || 0;
  return days * 86400 + s;
}

function classify(command) {
  for (const p of PATTERNS) {
    if (p.re.test(command) && !(p.exclude && p.exclude.test(command))) return p.agent;
  }
  return null;
}

function probe() {
  return new Promise((resolve) => {
    execFile('/bin/ps', ['-Ao', 'pid,etime,%cpu,rss,command'], { maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
      if (err) return resolve([]);
      const procs = [];
      for (const r of parseRows(stdout)) {
        const agent = classify(r.command);
        if (!agent) continue;
        // 过滤明显的短命子进程噪音
        const secs = etimeToSeconds(r.etime);
        if (secs < 3 && r.cpu < 5) continue;
        procs.push({
          agent,
          pid: r.pid,
          uptimeSec: secs,
          cpu: r.cpu,
          rssMb: Math.round((r.rssKb / 1024) * 10) / 10,
          command: r.command.slice(0, 160),
        });
      }
      resolve(procs);
    });
  });
}

module.exports = { probe, etimeToSeconds };
