package com.agentdeck.desk;

import java.util.ArrayList;
import java.util.List;

/** 单个 agent 的展示数据（来自服务端 /api/state 的 agents[] 项）。 */
public class Agent {
    public String id = "";
    public String name = "";
    public String color = "#888888";
    public String status = "idle";
    public int sessions;
    public int activeSessions;
    public int procCount;
    public long turns;
    public long todayTokens;
    public long weekTokens;
    public long lastAt;
    public long tokensTotal;
    /** token 构成：输入 / 输出 / 缓存读 / 缓存写 */
    public long tokIn, tokOut, tokCacheR, tokCacheW;
    public double costUsd;
    public int share;
    /** 近 12 小时逐小时 token，旧 -> 新 */
    public long[] series = new long[0];
    /** 最近一条会话的标题、模型、ID 与授权模式（服务端 top[0]） */
    public String topTitle = "", topModel = "", topSessionId = "", topPermMode = "";
    /** 运行中进程里最长的运行时长（秒），无进程为 0 */
    public long uptimeSec;

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_IDLE = "idle";
    public static final String STATE_OFFLINE = "offline";
    public static final String STATE_MISSING = "missing";

    public boolean isActive() {
        return STATE_ACTIVE.equals(status) || activeSessions > 0 || procCount > 0;
    }

    public boolean isOffline() {
        return STATE_OFFLINE.equals(status);
    }

    public boolean isMissing() {
        return STATE_MISSING.equals(status);
    }

    public String statusLabel() {
        if (isMissing()) return "未安装";
        if (isOffline()) return "离线";
        if (isActive()) return "运行中";
        return "空闲";
    }

    public static Agent fromJson(org.json.JSONObject o, org.json.JSONArray series) {
        Agent a = new Agent();
        if (series != null) {
            a.series = new long[series.length()];
            for (int i = 0; i < series.length(); i++) a.series[i] = series.optLong(i, 0);
        }
        a.id = o.optString("id", a.id);
        a.name = o.optString("name", a.id);
        a.color = matchColor(o.optString("color", "#888888"));
        a.status = o.optString("status", "idle");
        a.sessions = o.optInt("sessions", 0);
        a.activeSessions = o.optInt("activeSessions", 0);
        a.procCount = o.optInt("procCount", 0);
        a.turns = o.optLong("turns", 0);
        a.todayTokens = o.optLong("todayTokens", 0);
        a.weekTokens = o.optLong("weekTokens", 0);
        a.lastAt = o.optLong("lastAt", 0);
        a.share = o.optInt("share", 0);
        a.costUsd = o.optDouble("costUsd", 0);
        org.json.JSONArray top = o.optJSONArray("top");
        if (top != null && top.length() > 0) {
            org.json.JSONObject s = top.optJSONObject(0);
            if (s != null) {
                a.topTitle = s.optString("title", "");
                a.topModel = s.optString("model", "");
                a.topSessionId = s.optString("sessionId", "");
                a.topPermMode = s.optString("permissionMode", "");
            }
        }
        org.json.JSONArray procs = o.optJSONArray("processes");
        if (procs != null) for (int i = 0; i < procs.length(); i++) {
            org.json.JSONObject p = procs.optJSONObject(i);
            if (p != null) a.uptimeSec = Math.max(a.uptimeSec, p.optLong("uptimeSec", 0));
        }
        final org.json.JSONObject t = o.optJSONObject("tokens");
        if (t != null) {
            a.tokensTotal = t.optLong("total", 0);
            a.tokIn = t.optLong("input", 0);
            a.tokOut = t.optLong("output", 0);
            a.tokCacheR = t.optLong("cacheRead", 0);
            a.tokCacheW = t.optLong("cacheWrite", 0);
        }
        return a;
    }

    private static final List<String[]> PALETTE = new ArrayList<>();
    static {
        PALETTE.add(new String[]{"#D97757", "claude"});
        PALETTE.add(new String[]{"#8AB4F8", "codex"});
        PALETTE.add(new String[]{"#6EE7B7", "pi"});
        PALETTE.add(new String[]{"#A78BFA", "opencode"});
        PALETTE.add(new String[]{"#F472B6", "omo"});
        PALETTE.add(new String[]{"#4D6BFE", "dsh"});
    }
    private static final List<String> FALLBACK = new ArrayList<>();
    static {
        FALLBACK.add("#F472B6"); FALLBACK.add("#22D3EE"); FALLBACK.add("#FACC15");
        FALLBACK.add("#A3E635"); FALLBACK.add("#FB923C"); FALLBACK.add("#818CF8");
    }

    private static String matchColor(String c) {
        if (c != null && c.startsWith("#")) {
            for (String[] p : PALETTE) {
                if (p[1].equals(c)) return p[0];
            }
            // 服务端我配的颜色就是上面这组，未知颜色直接用
            try { if (c.length() == 7 && Integer.parseInt(c.substring(1), 16) >= 0) return c; } catch (Exception ignored) {}
        }
        final int hash = c.hashCode() & Integer.MAX_VALUE;
        return FALLBACK.get(hash % FALLBACK.size());
    }
}