package com.agentdeck.desk;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * AgentDeck 桌面屏 —— 以横向摆放的安卓旧手机 / 平板展示正在运行的 agent 与用量。
 * 布局全部用代码构建，无 XML 资源依赖，方便单文件打包。
 */
public class MainActivity extends Activity {
    private static final String TAG = "AgentDeck";
    private static final String PREFS = "agentdeck_prefs";
    private static final int POLL_MS = 2500;
    private static final String ENV_SERVER = "AGENTDECK_SERVER";

    private volatile String serverBase = null;
    private final Handler ui = new Handler();
    private SharedPreferences prefs; // 在 onCreate 中初始化（此时 context 可用）

    // 顶部
    private TextView tvDate, tvTime, tvServers, tvSummary;
    // 中部滚动
    private LinearLayout cardRow;
    // 底部
    private LinearLayout recentList;
    private TextView tvRecentHeader;
    private CheckerView chart;
    private final List<Agent> agents = new ArrayList<>();
    private final List<Agent> cardAgents = new ArrayList<>();
    private final List<RecentRow> recent = new ArrayList<>();
    private JSONArray lastSeries = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, 0);
        buildUi();
        keepScreenOn();
        hideSystemUi();
        tickClock();
        startServerDiscovery();
        ui.post(poller);
        startKeepAlive();
    }

    // ---- 布局 ----
    private void buildUi() {
        int bg = Color.parseColor("#0b0e14");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // 顶部状态条
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tvServers = label("#8b949e", 14, false);
        tvServers.setText("正在连接服务端…");
        tvSummary = label("#8b949e", 14, false);
        tvSummary.setGravity(Gravity.END);

        LinearLayout clockWrap = new LinearLayout(this);
        clockWrap.setOrientation(LinearLayout.VERTICAL);
        tvTime = label("#e6edf3", 26, true);
        tvDate = label("#8b949e", 12, false);
        clockWrap.addView(tvTime);
        clockWrap.addView(tvDate);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(tvServers);
        left.addView(tvSummary);
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f);
        top.addView(left, lpLeft);
        clockWrap.setGravity(Gravity.END);
        top.addView(clockWrap);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        topLp.bottomMargin = dp(10);
        root.addView(top, topLp);

        // 中部：单行 agent 卡片（横向滚动）
        cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        cardRow.setClipToPadding(false);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setFillViewport(true);
        hScroll.addView(cardRow);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hsLp.topMargin = dp(6);
        root.addView(hScroll, hsLp);

        // 中部：最近状况条 + 环形用量图
        chart = new CheckerView(this);
        chart.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(dp(64), dp(64));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ink = new LinearLayout(this);
        ink.setOrientation(LinearLayout.VERTICAL);
        ink.setGravity(Gravity.CENTER_VERTICAL);
        tvRecentHeader = label("#e6edf3", 16, true);
        tvRecentHeader.setText("最近使用");
        tvRecentHeader.setSingleLine(true);
        ink.addView(tvRecentHeader);
        bar.addView(ink, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(chart, chartLp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(6);
        barLp.bottomMargin = dp(4);
        root.addView(bar, barLp);

        // 底部：最近使用滚动列表
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView recentScroll = new HorizontalScrollView(this);
        recentScroll.setHorizontalScrollBarEnabled(false);
        recentScroll.addView(recentList);
        root.addView(recentScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        setContentView(root);
    }

    private TextView label(String color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor(color));
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLineSpacing(0, 0.96f);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private final float density() { return getResources().getDisplayMetrics().density; }

    // ---- 轮询 ----
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            fetchState();
            ui.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable clock = new Runnable() {
        @Override public void run() { tickClock(); ui.postDelayed(this, 1000); }
    };

    private void tickClock() {
        Date now = new Date();
        tvTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now));
        tvDate.setText(new SimpleDateFormat("yyyy/MM/dd EEE", Locale.getDefault()).format(now));
    }

    // ---- 服务器发现 ----
    private void startServerDiscovery() {
        new Thread(() -> {
            // USB 模式：adb reverse 把 127.0.0.1:7777 转发到 Mac（纯 USB 最可靠）
            for (int attempt = 0; attempt < 3; attempt++) {
                if (probe("http://127.0.0.1:7777")) { Log.i(TAG, "USB模式命中 127.0.0.1:7777"); setServer("http://127.0.0.1:7777"); return; }
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            String saved = prefs.getString("server", null);
            if (saved != null && !saved.contains("127.0.0.1") && probe(saved)) { setServer(saved); return; }
            String env = System.getenv(ENV_SERVER);
            if (env != null && env.length() > 0 && probe(env)) { setServer(env); return; }
            String scan = scanLan();
            if (scan != null) setServer(scan);
            else { Log.w(TAG, "未找到任何服务端"); ui.post(() -> tvServers.setText("未找到服务端 · 已配置 adb reverse tcp:7777 后打开 App")); }
        }).start();
    }

    private void setServer(String base) {
        serverBase = base;
        prefs.edit().putString("server", base).apply();
        Log.i(TAG, "已连接服务端: " + base);
        ui.post(() -> tvServers.setText("服务端 " + hostOf(base)));
        ui.post(poller);
    }

    private boolean probe(String base) {
        try {
            URL u = new URL(base + "/api/health");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(1200); c.setReadTimeout(1200);
            int code = c.getResponseCode();
            c.disconnect();
            Log.i(TAG, "probe " + base + " -> " + code);
            return code == 200;
        } catch (Exception e) {
            Log.w(TAG, "probe " + base + " 失败: " + e);
            return false;
        }
    }

    private String scanLan() {
        String ip = localIp();
        if (ip == null) return null;
        String prefix = ip.substring(0, ip.lastIndexOf('.')) + ".";
        int[] ports = {7777, 7000, 8080, 3000};
        for (int port : ports) {
            for (int i = 2; i <= 254; i++) {
                String base = "http://" + prefix + i + ":" + port;
                if (probe(base)) return base;
            }
        }
        return null;
    }

    private static String localIp() {
        try {
            for (Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces(); nis.hasMoreElements();) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (Enumeration<InetAddress> addrs = ni.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String hostOf(String url) { try { return new URL(url).getHost(); } catch (Exception e) { return url; } }

    // ---- 抓取与渲染 ----
    private void fetchState() {
        final String base = serverBase;
        if (base == null) return;
        new Thread(() -> {
            try {
                URL u = new URL(base + "/api/state");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(2500); c.setReadTimeout(2500);
                int code = c.getResponseCode();
                if (code != 200) { c.disconnect(); return; }
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close(); c.disconnect();
                final JSONObject root = new JSONObject(sb.toString());
                ui.post(() -> { try { render(root); } catch (JSONException e) { /* 忽略坏数据 */ } });
            } catch (IOException | JSONException ignored) { /* 保留旧画面 */ }
        }).start();
    }

    private void render(JSONObject root) throws JSONException {
        tickClock();

        JSONObject totals = root.optJSONObject("totals");
        if (totals != null) {
            long total = tokensTotal(totals);
            long today = totals.optLong("todayTokens", 0);
            double cost = totals.optDouble("costUsd", 0);
            JSONArray hosts = root.optJSONArray("hosts");
            int online = 0;
            if (hosts != null) for (int i = 0; i < hosts.length(); i++)
                if (hosts.getJSONObject(i).optBoolean("online", false)) online++;
            tvSummary.setText(String.format(Locale.US, "在线 %d/%d · 总量 %s · 今日 %s · 花费 $%.4f",
                    online, hosts == null ? 0 : hosts.length(), fmt(total), fmt(today), cost));
        }

        // agent 卡片
        agents.clear();
        JSONArray arr = root.optJSONArray("agents");
        if (arr != null) for (int i = 0; i < arr.length(); i++) agents.add(Agent.fromJson(arr.getJSONObject(i)));
        renderCards();
        chart.update(agents);

        // 用量序列图
        lastSeries = root.optJSONArray("series") != null ? root.optJSONArray("series") : new JSONArray();
        chart.invalidate();

        // 最近使用
        recent.clear();
        JSONArray rj = root.optJSONArray("recent");
        if (rj != null) for (int i = 0; i < rj.length(); i++) {
            JSONObject r = rj.getJSONObject(i);
            recent.add(new RecentRow(
                    r.optString("agent", ""), r.optString("agentName", ""), safeColor(r.optString("color", "")),
                    r.optString("title", ""), r.optString("model", ""),
                    r.optLong("lastAt", 0), r.optLong("tokens", 0), r.optString("host", "")));
        }
        renderRecent();
    }

    private long tokensTotal(JSONObject totals) {
        return totals.optJSONObject("tokens") != null
                ? totals.optJSONObject("tokens").optLong("total", 0) : 0;
    }

    private void renderCards() {
        cardRow.removeAllViews();
        final int n = agents.size();
        if (n == 0) return;
        cardAgents.clear();
        cardAgents.addAll(agents);
        // 数量少时按权重铺满整行，多了就固定宽度横向滚动
        final boolean fit = n <= 6;
        final boolean compact = n >= 5;
        for (Agent a : agents) {
            LinearLayout.LayoutParams lp = fit
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    : new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(compact ? 4 : 12));
            final View cv = card(a, compact);
            cv.setOnClickListener(v -> openDetail(a.id));
            cardRow.addView(cv, lp);
        }
    }

    /** 单张 agent 卡片（横向布局，适合宽屏） */
    private View card(Agent a, boolean compact) {
        int accent = safeColor(a.color);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int pad = dp(compact ? 9 : 14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(roundedBg(accent, 0.10f, 1f, dp(12)));

        // 左侧竖条：状态色 + 名称 + 状态
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        int dotColor = a.isActive() ? Color.parseColor("#3fb950") : (a.isOffline() ? Color.parseColor("#f85149") : accent);
        dot.setBackground(roundedBg(dotColor, 1f, 0f, dp(6)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(compact ? 9 : 12), dp(compact ? 9 : 12));
        dotLp.setMargins(0, 0, dp(compact ? 5 : 8), 0);
        nameRow.addView(dot, dotLp);
        TextView name = new TextView(this);
        name.setTextColor(Color.parseColor("#e6edf3"));
        name.setTextSize(compact ? 14 : 20);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setText(a.name);
        nameRow.addView(name);
        left.addView(nameRow);

        TextView status = new TextView(this);
        status.setTextSize(compact ? 11 : 13);
        status.setTextColor(Color.parseColor(a.isActive() ? "#3fb950" : "#8b949e"));
        status.setText(a.statusLabel());
        status.setPadding(0, dp(2), 0, 0);
        left.addView(status);
        left.setPadding(0, 0, dp(compact ? 7 : 14), 0);
        if (compact) {
            card.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            card.addView(left, new LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 分隔线
        if (!compact) {
            View sep = new View(this);
            sep.setBackgroundColor(Color.parseColor("#222b38"));
            card.addView(sep, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 右侧数据
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        if (a.tokensTotal <= 0 && !a.isActive()) {
            TextView none = new TextView(this);
            none.setTextColor(Color.parseColor("#8b949e"));
            none.setTextSize(compact ? 11 : 14);
            none.setSingleLine(true);
            none.setText(a.isMissing() ? "未安装" : (a.sessions > 0 ? "会话 " + a.sessions : "无记录"));
            body.addView(none);
            card.addView(body, compact
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            return card;
        }

        TextView tokenTotal = new TextView(this);
        tokenTotal.setTextColor(accent);
        tokenTotal.setTextSize(compact ? 19 : 30);
        tokenTotal.setSingleLine(true);
        tokenTotal.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tokenTotal.setText(a.tokensTotal > 0 ? fmt(a.tokensTotal) : "—");
        body.addView(tokenTotal);

        TextView sub = new TextView(this);
        sub.setTextColor(Color.parseColor("#8b949e"));
        sub.setTextSize(compact ? 10 : 13);
        TextView today = new TextView(this);
        today.setTextColor(Color.parseColor("#8b949e"));
        today.setTextSize(compact ? 10 : 13);
        if (a.tokensTotal <= 0) {
            sub.setText(statusLine(a));
            body.addView(sub);
        } else {
            sub.setSingleLine(true);
            today.setSingleLine(true);
            sub.setText("会话 " + a.sessions + (a.procCount > 0 ? " · 进程 " + a.procCount : "") + (a.isActive() ? " · 运行中" : ""));
            today.setText("今日 " + fmt(a.todayTokens) + " · $" + String.format(Locale.US, "%.2f", a.costUsd));
            if (compact) {
                String line = a.isActive() ? "运行中" : ("会话 " + a.sessions);
                sub.setText(line);
                body.addView(sub);
            } else {
                body.addView(sub);
                body.addView(today);
            }
        }
        body.setPadding(compact ? 0 : dp(14), compact ? dp(6) : 0, 0, 0);
        card.addView(body, compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        return card;
    }

    private String statusLine(Agent a) {
        if (a.isMissing()) return "未检测到安装";
        if (a.isOffline()) return "服务端离线";
        return "会话 " + a.sessions + (a.isActive() ? " · 运行中" : "");
    }

    private void renderRecent() {
        recentList.removeAllViews();
        int n = Math.min(recent.size(), 10);
        for (int i = 0; i < n; i++) {
            RecentRow r = recent.get(i);
            View rv = recentRow(r, i == 0);
            rv.setOnClickListener(v -> { if (r.agentId != null && !r.agentId.isEmpty()) openDetail(r.agentId); });
            recentList.addView(rv);
        }
        if (n == 0) {
            TextView t = label("#8b949e", 13, false);
            t.setText("暂无最近的 agent 会话");
            recentList.addView(t);
        }
    }

    private void bindRecent(View v, RecentRow r) { /* 保留占位，实际用上面的 setOnClickListener */ }

    // 打开某 agent 的详情页（会话列表 + 走势图）
    private void openDetail(String agentId) {
        Log.i(TAG, "打开详情: " + agentId);
        try {
            Intent i = new Intent(this, AgentDetailActivity.class);
            i.putExtra("agentId", agentId);
            i.putExtra("base", serverBase != null ? serverBase : "");
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "打开详情失败: " + e);
        }
    }

    private View recentRow(RecentRow r, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(r.color, 0.07f, 1f, dp(8)));
        int p = dp(8);
        row.setPadding(p, dp(5), p, dp(5));

        TextView title = new TextView(this);
        title.setTextColor(Color.parseColor("#e6edf3"));
        title.setTextSize(12);
        title.setText(r.title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        meta.setTextColor(r.color);
        meta.setTextSize(10);
        meta.setSingleLine(true);
        meta.setText(r.agentName + " · " + fmt(r.tokens));
        row.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        row.setLayoutParams(lp);
        return row;
    }

    // ---- 辅助方法 ----
    private android.graphics.drawable.Drawable roundedBg(int color, float alpha, float strokeW, int radius) {
        // 返回一个可复用 drawable，这里用简单实现
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        int c = color;
        int a = (int) (alpha * 255);
        int argb = (a << 24) | (c & 0xffffff);
        g.setColor(argb);
        if (strokeW > 0) g.setStroke(dp((int) strokeW), Color.parseColor("#222b38"));
        g.setCornerRadius(radius);
        return g;
    }

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startKeepAlive() {
        try {
            startService(new android.content.Intent(this, KeepAliveService.class));
        } catch (Exception ignored) {}
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(v -> {
            if ((v & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                ui.postDelayed(() -> getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN), 2000);
            }
        });
    }

    private static int safeColor(String c) {
        if (c != null && c.startsWith("#") && c.length() == 7) {
            try { return Color.parseColor(c); } catch (Exception ignored) {}
        }
        return Color.parseColor("#8b949e");
    }

    static String fmt(long n) {
        if (n >= 1_000_000_000) return String.format(Locale.US, "%.2fB", n / 1e9);
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    // ---- 环形用量图 ----
    static class CheckerView extends View {
        final List<Agent> agents;
        private final Paint paint;
        private final RectF rect;

        CheckerView(android.content.Context ctx) {
            super(ctx);
            agents = new ArrayList<>();
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            rect = new RectF();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float total = 0;
            for (Agent a : agents) total += Math.max(0, a.tokensTotal);
            if (total <= 0) {
                paint.setColor(Color.parseColor("#1f2937"));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(h * 0.12f));
                rect.set(h * 0.1f, h * 0.1f, w - h * 0.1f, h - h * 0.1f);
                canvas.drawArc(rect, 0, 360, false, paint);
                return;
            }
            float sw = Math.min(h, w) * 0.14f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            rect.set(sw, sw, w - sw, h - sw);
            float start = -90f;
            for (Agent a : agents) {
                if (a.tokensTotal <= 0) continue;
                float sweep = (a.tokensTotal / total) * 360f;
                paint.setColor(safeColor(a.color));
                canvas.drawArc(rect, start, sweep, false, paint);
                start += sweep;
            }
        }

        void update(List<Agent> list) {
            agents.clear();
            agents.addAll(list);
            invalidate();
        }

        private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }
    }

    static class RecentRow {
        String agentId, agentName, title, model, host;
        int color;
        long lastAt, tokens;
        RecentRow(String ai, String an, int c, String t, String m, long l, long tk, String ho) {
            agentId=ai; agentName=an; color=c; title=t; model=m; lastAt=l; tokens=tk; host=ho;
        }
    }

    // SimpleDateFormat not thread-safe; only called on UI thread
    @Override protected void onStart() { super.onStart(); ui.removeCallbacks(clock); ui.postDelayed(clock, 1000); }
    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(poller); ui.postDelayed(poller, 300); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(poller); ui.removeCallbacks(clock); }
    @Override protected void onDestroy() { super.onDestroy(); ui.removeCallbacks(poller); ui.removeCallbacks(clock); }
}