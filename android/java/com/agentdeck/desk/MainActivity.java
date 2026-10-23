package com.agentdeck.desk;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AgentDeck 桌面屏 —— 以横向摆放的安卓旧手机 / 平板展示正在运行的 agent 与用量。
 * 布局全部用代码构建；卡片按 agent id 复用，轮询只更新数据，动效才能连续。
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
    private TextView tvTime, tvDate, tvConn;
    private PulseDot connDot;
    private CountText kpiTotal, kpiToday, kpiCost, kpiActive;
    // 中部
    private LinearLayout cardRow;
    private final Map<String, Card> cards = new HashMap<>();
    private LinearLayout dock;
    private TextView dockLabel, idleHint;
    private View idlePane;
    private DonutView donut;
    private LinearLayout legend;
    // 底部
    private LinearLayout recentList;
    private String recentKey = "";
    private GlowBackground glow;
    private boolean firstRender = true;

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
        FrameLayout frame = new FrameLayout(this);
        glow = new GlowBackground(this);
        frame.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(14), pad, dp(14));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        View header = buildHeader();
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View kpis = buildKpis();
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(-1, dp(46));
        kLp.topMargin = dp(8);
        root.addView(kpis, kLp);

        // 中部：agent 卡片 + 右侧占比环
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        cardRow = new LinearLayout(this);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setFillViewport(true);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hScroll.addView(cardRow, new FrameLayout.LayoutParams(-2, -1));
        FrameLayout stack = new FrameLayout(this);
        stack.addView(hScroll, new FrameLayout.LayoutParams(-1, -1));
        idlePane = buildIdlePane();
        stack.addView(idlePane, new FrameLayout.LayoutParams(-1, -1));
        mid.addView(stack, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        mLp.topMargin = dp(10);
        root.addView(mid, mLp);

        // 底部：最近会话
        LinearLayout recentHead = new LinearLayout(this);
        recentHead.setGravity(Gravity.CENTER_VERTICAL);
        recentHead.addView(sectionTitle("最近会话"), new LinearLayout.LayoutParams(0, -2, 1f));
        dockLabel = Theme.text(this, Theme.FAINT, 10.5f, Theme.MEDIUM);
        dockLabel.setLetterSpacing(0.08f);
        recentHead.addView(dockLabel);
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        recentHead.addView(dock, new LinearLayout.LayoutParams(-2, dp(30)));
        LinearLayout.LayoutParams rhLp = new LinearLayout.LayoutParams(-1, -2);
        rhLp.topMargin = dp(10);
        rhLp.bottomMargin = dp(6);
        root.addView(recentHead, rhLp);

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView recentScroll = new HorizontalScrollView(this);
        recentScroll.setHorizontalScrollBarEnabled(false);
        recentScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recentScroll.addView(recentList);
        root.addView(recentScroll, new LinearLayout.LayoutParams(-1, dp(48)));

        setContentView(frame);

        Anim.enter(header, 0, -dp(10));
        Anim.enter(kpis, 90, dp(14));
        Anim.enter(recentHead, 340, dp(14));
    }

    private View buildHeader() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LogoView logo = new LogoView(this);
        top.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        TextView name = Theme.text(this, Theme.TEXT, 20, Theme.BOLD);
        name.setText("AgentDeck");
        name.setLetterSpacing(0.02f);
        brand.addView(name);

        LinearLayout conn = new LinearLayout(this);
        conn.setGravity(Gravity.CENTER_VERTICAL);
        conn.setPadding(0, dp(4), 0, 0);
        connDot = new PulseDot(this);
        connDot.setState(Theme.MUTED, true);
        conn.addView(connDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        tvConn = Theme.text(this, Theme.MUTED, 12, null);
        tvConn.setText("正在连接服务端…");
        tvConn.setPadding(dp(4), 0, 0, 0);
        tvConn.setSingleLine(true);
        conn.addView(tvConn);
        brand.addView(conn);
        top.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(buildShare(), new LinearLayout.LayoutParams(-2, dp(46)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        tvTime = Theme.text(this, Theme.TEXT, 28, Theme.LIGHT);
        tvTime.setFontFeatureSettings("tnum");
        tvTime.setLetterSpacing(0.03f);
        tvDate = Theme.text(this, Theme.MUTED, 12, null);
        tvDate.setPadding(0, dp(3), dp(2), 0);
        clock.addView(tvTime);
        clock.addView(tvDate);
        top.addView(clock);
        return top;
    }

    private View buildKpis() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        kpiTotal = new CountText(this);
        kpiToday = new CountText(this);
        kpiCost = new CountText(this);
        kpiCost.setFmt(Theme::money);
        kpiActive = new CountText(this);
        kpiActive.setFmt(v -> String.valueOf(Math.round(v)));
        addKpi(row, "总 TOKEN", kpiTotal, Theme.BRAND_A, true);
        addKpi(row, "今日", kpiToday, Theme.BRAND_B, true);
        addKpi(row, "花费", kpiCost, 0xFFFACC15, true);
        addKpi(row, "运行中", kpiActive, Theme.GREEN, false);
        return row;
    }

    private void addKpi(LinearLayout row, String label, CountText value, int accent, boolean gap) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        box.setBackground(Theme.panel(this, accent, 0.10f, 14));

        LinearLayout lr = new LinearLayout(this);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(Theme.pill(this, accent, 1f));
        lr.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(10)));
        TextView l = Theme.text(this, Theme.MUTED, 10.5f, Theme.MEDIUM);
        l.setText(label);
        l.setLetterSpacing(0.08f);
        l.setPadding(dp(6), 0, 0, 0);
        lr.addView(l);
        box.addView(lr);

        value.setTextColor(Theme.TEXT);
        value.setTextSize(18);
        value.setTypeface(Theme.BOLD);
        value.setPadding(0, dp(4), 0, 0);
        value.setPlaceholder("—");
        box.addView(value);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (gap) lp.rightMargin = dp(10);
        row.addView(box, lp);
    }

    private View buildShare() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        donut = new DonutView(this);
        box.addView(donut, new LinearLayout.LayoutParams(dp(42), dp(42)));
        legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(dp(10), 0, 0, 0);
        box.addView(legend, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }

    private View buildIdlePane() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Theme.panel(this, Theme.BRAND_B, 0.05f, 20));
        LogoView logo = new LogoView(this);
        box.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = Theme.text(this, Theme.TEXT, 16, Theme.BOLD);
        t.setText("没有正在运行的 agent");
        t.setPadding(0, dp(12), 0, 0);
        box.addView(t);
        idleHint = Theme.text(this, Theme.MUTED, 12, null);
        idleHint.setPadding(0, dp(6), 0, 0);
        box.addView(idleHint);
        box.setVisibility(View.GONE);
        return box;
    }

    private TextView sectionTitle(String s) {
        TextView t = Theme.text(this, Theme.MUTED, 11, Theme.MEDIUM);
        t.setText(s);
        t.setLetterSpacing(0.1f);
        return t;
    }

    private int dp(float v) { return Theme.dp(this, v); }

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

    // SimpleDateFormat not thread-safe; only called on UI thread
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd EEE", Locale.getDefault());

    private void tickClock() {
        Date now = new Date();
        tvTime.setText(timeFmt.format(now));
        tvDate.setText(dateFmt.format(now));
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
            else {
                Log.w(TAG, "未找到任何服务端");
                ui.post(() -> setConn(false, "未找到服务端 · 请先 adb reverse tcp:7777 tcp:7777"));
            }
        }).start();
    }

    private void setServer(String base) {
        serverBase = base;
        prefs.edit().putString("server", base).apply();
        Log.i(TAG, "已连接服务端: " + base);
        ui.post(() -> setConn(true, hostOf(base)));
        ui.post(poller);
    }

    private void setConn(boolean ok, String text) {
        connDot.setState(ok ? Theme.GREEN : Theme.RED, ok);
        tvConn.setText(text);
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
                ui.post(() -> {
                    try { render(root); } catch (JSONException e) { Log.w(TAG, "坏数据: " + e); }
                });
            } catch (IOException | JSONException e) {
                // 保留旧画面，只把连接状态标红
                ui.post(() -> setConn(false, hostOf(base) + " · 连接中断，重试中"));
            }
        }).start();
    }

    private void render(JSONObject root) throws JSONException {
        JSONObject totals = root.optJSONObject("totals");
        JSONArray hosts = root.optJSONArray("hosts");
        int online = 0, hostCount = hosts == null ? 0 : hosts.length();
        for (int i = 0; i < hostCount; i++) if (hosts.getJSONObject(i).optBoolean("online", false)) online++;
        if (serverBase != null) setConn(online > 0, hostOf(serverBase) + " · 主机 " + online + "/" + hostCount);

        List<Agent> agents = new ArrayList<>();
        JSONArray arr = root.optJSONArray("agents");
        if (arr != null) for (int i = 0; i < arr.length(); i++) agents.add(Agent.fromJson(arr.getJSONObject(i), arr.getJSONObject(i).optJSONArray("series")));

        int active = 0;
        for (Agent a : agents) if (a.isActive()) active++;
        if (totals != null) {
            JSONObject tok = totals.optJSONObject("tokens");
            kpiTotal.setValue(tok != null ? tok.optLong("total", 0) : 0);
            kpiToday.setValue(totals.optLong("todayTokens", 0));
            kpiCost.setValue(totals.optDouble("costUsd", 0));
        }
        kpiActive.setValue(active);

        renderCards(agents);
        if (firstRender) { final List<Agent> again = agents; cardRow.post(() -> renderCards(again)); }
        renderShare(agents);
        renderRecent(root.optJSONArray("recent"));
        firstRender = false;
    }

    // ---- 卡片 ----
    /**
     * 只给运行中的 agent 发大卡片；其余已安装的 agent 收进底部 dock（去色小图标），
     * 状态变化时在两处之间带动画迁移。一个都没在跑时显示空闲占位。
     */
    private void renderCards(List<Agent> agents) {
        List<Agent> live = new ArrayList<>();
        List<Agent> idle = new ArrayList<>();
        for (Agent a : agents) {
            if (a.isActive()) live.add(a);
            else if (a.sessions > 0 || a.tokensTotal > 0) idle.add(a);
        }
        // 运行中的按今日用量排，最忙的在最左
        java.util.Collections.sort(live, (x, y) -> Long.compare(y.todayTokens, x.todayTokens));

        int n = live.size();
        int gap = dp(10);
        int avail = ((View) cardRow.getParent()).getWidth();
        if (avail <= 0) avail = getResources().getDisplayMetrics().widthPixels - dp(36);
        // 不超过 4 张时均分铺满；更多则固定宽度横向滚动。
        // HorizontalScrollView 以 UNSPECIFIED 测量子 View，weight 不生效，所以直接算像素宽度。
        int cardW = n == 0 ? avail : n <= 4 ? (avail - gap * (n - 1)) / n : dp(230);
        boolean wide = n <= 2;

        List<String> seen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Agent a = live.get(i);
            seen.add(a.id);
            Card c = cards.get(a.id);
            if (c == null) {
                c = new Card(a.id);
                cards.put(a.id, c);
                cardRow.addView(c.root, new LinearLayout.LayoutParams(cardW, -1));
                Anim.enter(c.root, (firstRender ? 160 : 0) + i * 80L, dp(28));
            }
            if (cardRow.indexOfChild(c.root) != i) {
                cardRow.removeView(c.root);
                cardRow.addView(c.root, i);
            }
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.root.getLayoutParams();
            lp.width = cardW;
            lp.rightMargin = i < n - 1 ? gap : 0;
            c.root.setLayoutParams(lp);
            c.width = cardW;
            c.setWide(wide);
            c.bind(a);
        }
        for (String id : new ArrayList<>(cards.keySet())) {
            if (seen.contains(id)) continue;
            final View v = cards.remove(id).root;
            // 退出：缩小淡出后再移除
            v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(320)
                    .withEndAction(() -> cardRow.removeView(v)).start();
        }

        idlePane.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n == 0) {
            long last = 0;
            String lastName = "";
            for (Agent a : agents) if (a.lastAt > last) { last = a.lastAt; lastName = a.name; }
            idleHint.setText(last > 0 ? "最近一次是 " + lastName + "，" + Theme.ago(last) : "启动任意 agent 后会出现在这里");
        }
        renderDock(idle);
    }

    private String dockKey = "";

    private void renderDock(List<Agent> idle) {
        StringBuilder key = new StringBuilder();
        for (Agent a : idle) key.append(a.id).append(',');
        if (key.toString().equals(dockKey)) return;
        dockKey = key.toString();
        dock.removeAllViews();
        for (int i = 0; i < idle.size(); i++) {
            Agent a = idle.get(i);
            int accent = Theme.color(a.color, Theme.MUTED);
            AgentIcon ic = new AgentIcon(this);
            ic.setAgent(a.id, a.name, accent);
            ic.setDim(true);
            ic.setClickable(true);
            ic.setOnClickListener(v -> openDetail(a.id, accent));
            Anim.pressable(ic);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.leftMargin = dp(6);
            dock.addView(ic, lp);
            Anim.enter(ic, (firstRender ? 500 : 0) + i * 40L, dp(6));
        }
        dockLabel.setText(idle.isEmpty() ? "" : "空闲 " + idle.size());
    }

    /** 一张运行中 agent 的卡片：保持同一组 View，数据变化时只做过渡。 */
    private final class Card {
        final LinearLayout root;
        final AgentIcon icon;
        final TextView name, model, task, uptime, sub;
        final CountText today;
        final SparkView spark;
        int lastAccent;
        int width;
        boolean wide = true;

        final LinearLayout left, right;

        Card(String id) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(dp(16), dp(12), dp(16), dp(12));
            root.setClickable(true);
            root.setOnClickListener(v -> openDetail(id, lastAccent));
            Anim.pressable(root);

            // 左栏：图标 + 名称 / 模型 + 当前任务
            left = new LinearLayout(MainActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon = new AgentIcon(MainActivity.this);
            icon.setLive(true);
            head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout nc = new LinearLayout(MainActivity.this);
            nc.setOrientation(LinearLayout.VERTICAL);
            nc.setPadding(dp(10), 0, 0, 0);
            name = Theme.text(MainActivity.this, Theme.TEXT, 18, Theme.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            nc.addView(name);
            model = Theme.text(MainActivity.this, Theme.MUTED, 10.5f, null);
            model.setSingleLine(true);
            model.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            model.setPadding(0, dp(4), 0, 0);
            nc.addView(model);
            head.addView(nc, new LinearLayout.LayoutParams(0, -2, 1f));
            uptime = Theme.text(MainActivity.this, Theme.GREEN, 10.5f, Theme.MEDIUM);
            uptime.setFontFeatureSettings("tnum");
            uptime.setPadding(dp(8), dp(3), dp(8), dp(3));
            uptime.setBackground(Theme.pill(MainActivity.this, Theme.GREEN, 0.14f));
            head.addView(uptime);
            left.addView(head);

            task = Theme.text(MainActivity.this, Theme.TEXT, 12.5f, null);
            task.setMaxLines(2);
            task.setEllipsize(TextUtils.TruncateAt.END);
            task.setLineSpacing(dp(2), 1f);
            task.setAlpha(0.85f);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
            tLp.topMargin = dp(10);
            left.addView(task, tLp);

            // 右栏：今日用量 + 累计 / 会话 + 走势线
            right = new LinearLayout(MainActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            LinearLayout foot = new LinearLayout(MainActivity.this);
            foot.setGravity(Gravity.BOTTOM);
            LinearLayout tc = new LinearLayout(MainActivity.this);
            tc.setOrientation(LinearLayout.VERTICAL);
            TextView tl = Theme.text(MainActivity.this, Theme.MUTED, 10, Theme.MEDIUM);
            tl.setText("今日");
            tl.setLetterSpacing(0.08f);
            tc.addView(tl);
            today = new CountText(MainActivity.this);
            today.setTextSize(26);
            today.setTypeface(Theme.BOLD);
            today.setPadding(0, dp(3), 0, 0);
            tc.addView(today);
            foot.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
            sub = Theme.text(MainActivity.this, Theme.MUTED, 10.5f, null);
            sub.setGravity(Gravity.END);
            sub.setLineSpacing(dp(3), 1f);
            foot.addView(sub);
            right.addView(foot);
            spark = new SparkView(MainActivity.this);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            spLp.topMargin = dp(6);
            right.addView(spark, spLp);

            root.addView(left);
            root.addView(right);
            wide = false;
            setWide(true);
        }

        /** 宽：左右两栏；窄：上下排，隐藏任务标题。 */
        void setWide(boolean on) {
            if (on == wide) return;
            wide = on;
            root.setOrientation(on ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if (on) {
                left.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.15f));
                LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -1, 1f);
                r.leftMargin = dp(18);
                right.setLayoutParams(r);
            } else {
                left.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(-1, 0, 1f);
                r.topMargin = dp(8);
                right.setLayoutParams(r);
            }
            task.setVisibility(on ? View.VISIBLE : View.GONE);
            today.setTextSize(on ? 26 : 20);
            name.setTextSize(on ? 18 : 14.5f);
            ViewGroup.LayoutParams il = icon.getLayoutParams();
            il.width = il.height = dp(on ? 52 : 38);
            icon.setLayoutParams(il);
        }

        void bind(Agent a) {
            int accent = Theme.color(a.color, Theme.MUTED);
            if (accent != lastAccent) {
                lastAccent = accent;
                today.setTextColor(accent);
                icon.setAgent(a.id, a.name, accent);
                root.setBackground(Theme.panel(MainActivity.this, accent, 0.22f, 20));
            }
            icon.setStatus(Theme.GREEN, true);
            name.setText(a.name);
            // 宽卡片左栏约占 1.15/2.15，再扣掉图标与运行时长胶囊
            int nameW = wide ? (int) ((width - dp(50)) * 1.15f / 2.15f) - dp(130) : width - dp(110);
            fitText(name, a.name, nameW, wide ? 18 : 14.5f, 10);
            model.setText(a.topModel.isEmpty() ? (a.procCount > 0 ? "进程 " + a.procCount : "运行中") : a.topModel);
            uptime.setText(a.uptimeSec > 0 ? dur(a.uptimeSec) : "LIVE");

            String t = a.topTitle == null ? "" : a.topTitle.replaceAll("\\s+", " ").trim();
            task.setText(t.isEmpty() ? "正在运行，暂无会话标题" : t);

            today.setValue(a.todayTokens);
            sub.setText((a.tokensTotal > 0 ? "累计 " + Theme.fmt(a.tokensTotal) : "无用量记录")
                    + "\n" + a.sessions + " 会话" + (a.share > 0 ? " · " + a.share + "%" : ""));
            spark.setData(a.series, accent);
        }
    }

    private static String dur(long sec) {
        if (sec < 60) return sec + "s";
        long m = sec / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    /** 按 sample 文本的宽度选一个不超过 maxSp、不小于 minSp 的字号。 */
    private void fitText(TextView tv, String sample, int widthPx, float maxSp, float minSp) {
        if (widthPx <= 0) return;
        android.text.TextPaint p = new android.text.TextPaint(tv.getPaint());
        float sp = maxSp;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        while (sp > minSp) {
            p.setTextSize(sp * scaled);
            if (p.measureText(sample) <= widthPx) break;
            sp -= 0.5f;
        }
        if (Math.abs(tv.getTextSize() - sp * scaled) > 0.5f) tv.setTextSize(sp);
    }

    // ---- 占比 ----
    private String legendKey = "";

    private void renderShare(List<Agent> agents) {
        List<Agent> used = new ArrayList<>();
        long total = 0;
        for (Agent a : agents) if (a.tokensTotal > 0) { used.add(a); total += a.tokensTotal; }
        long[] vals = new long[used.size()];
        int[] cols = new int[used.size()];
        for (int i = 0; i < used.size(); i++) {
            vals[i] = used.get(i).tokensTotal;
            cols[i] = Theme.color(used.get(i).color, Theme.MUTED);
        }
        donut.setSegments(vals, cols);

        StringBuilder key = new StringBuilder();
        for (Agent a : used) key.append(a.id).append(':').append(a.share).append(',');
        if (key.toString().equals(legendKey)) return;
        legendKey = key.toString();
        legend.removeAllViews();
        for (int i = 0, shown = 0; i < used.size() && shown < 3; i++) {
            Agent a = used.get(i);
            if (a.share <= 0) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), 0, dp(8), 0);
            AgentIcon ic = new AgentIcon(this);
            ic.setAgent(a.id, a.name, Theme.color(a.color, Theme.MUTED));
            row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView p = Theme.text(this, Theme.TEXT, 11, Theme.MEDIUM);
            p.setPadding(dp(5), 0, 0, 0);
            p.setFontFeatureSettings("tnum");
            p.setText(a.share + "%");
            row.addView(p);
            legend.addView(row);
        }
    }

    // ---- 最近会话 ----
    private void renderRecent(JSONArray rj) throws JSONException {
        int n = rj == null ? 0 : Math.min(rj.length(), 10);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < n; i++) {
            JSONObject r = rj.getJSONObject(i);
            key.append(r.optString("agent")).append(r.optLong("lastAt")).append(r.optLong("tokens")).append('|');
        }
        // 内容没变就不重建，避免每次轮询闪动
        if (key.toString().equals(recentKey) && n > 0) return;
        boolean animate = !recentKey.equals(key.toString());
        recentKey = key.toString();
        recentList.removeAllViews();
        for (int i = 0; i < n; i++) {
            JSONObject r = rj.getJSONObject(i);
            final String agentId = r.optString("agent", "");
            final int color = Theme.color(r.optString("color", ""), Theme.MUTED);
            View rv = recentRow(r, color);
            rv.setOnClickListener(v -> { if (!agentId.isEmpty()) openDetail(agentId, color); });
            Anim.pressable(rv);
            recentList.addView(rv);
            if (animate) Anim.enter(rv, (firstRender ? 420 : 0) + i * 50L, dp(10));
        }
        if (n == 0) {
            TextView t = Theme.text(this, Theme.FAINT, 12, null);
            t.setText("暂无最近的 agent 会话");
            recentList.addView(t);
        }
    }

    private View recentRow(JSONObject r, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.panel(this, color, 0.10f, 14));
        row.setPadding(dp(8), 0, dp(12), 0);
        row.setClickable(true);

        AgentIcon ic = new AgentIcon(this);
        ic.setAgent(r.optString("agent", ""), r.optString("agentName", ""), color);
        if (r.optBoolean("active", false)) ic.setStatus(Theme.GREEN, true);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(9), 0, 0, 0);
        TextView title = Theme.text(this, Theme.TEXT, 12, null);
        title.setText(r.optString("title", "(无标题)"));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        TextView meta = Theme.text(this, Theme.MUTED, 10, null);
        meta.setSingleLine(true);
        meta.setPadding(0, dp(4), 0, 0);
        long lastAt = r.optLong("lastAt", 0);
        String agentName = r.optString("agentName", "");
        meta.setText(agentName + " · " + Theme.fmt(r.optLong("tokens", 0)) + (lastAt > 0 ? " · " + Theme.ago(lastAt) : ""));
        col.addView(meta);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(228), -1);
        lp.rightMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    // 打开某 agent 的详情页（会话列表 + 走势图）
    private void openDetail(String agentId, int color) {
        Log.i(TAG, "打开详情: " + agentId);
        try {
            Intent i = new Intent(this, AgentDetailActivity.class);
            i.putExtra("agentId", agentId);
            i.putExtra("base", serverBase != null ? serverBase : "");
            i.putExtra("color", color);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_right, R.anim.fade_scale_out);
        } catch (Exception e) {
            Log.w(TAG, "打开详情失败: " + e);
        }
    }

    // ---- 系统 ----
    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startKeepAlive() {
        try {
            startService(new Intent(this, KeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "保活服务启动失败: " + e);
        }
    }

    private void hideSystemUi() {
        final int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(v -> {
            if ((v & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0)
                ui.postDelayed(() -> getWindow().getDecorView().setSystemUiVisibility(flags), 2000);
        });
    }

    @Override protected void onStart() { super.onStart(); ui.removeCallbacks(clock); ui.postDelayed(clock, 1000); }
    @Override protected void onResume() { super.onResume(); ui.removeCallbacks(poller); ui.postDelayed(poller, 300); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(poller); ui.removeCallbacks(clock); }
    @Override protected void onDestroy() { super.onDestroy(); ui.removeCallbacks(poller); ui.removeCallbacks(clock); }
}
