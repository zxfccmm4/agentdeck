package com.agentdeck.desk;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
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
    private TextView tvDate, tvWeek, tvConn;
    private ClockView clockView;
    private MixBar yearBar;
    private PulseDot connDot;
    private CountText kpiTotal, kpiToday, kpiCost, kpiActive;
    private View kpisView;
    private LinearLayout permBanner;
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

        // 4 个 KPI 指标与待授权审批条同槽位切换：有待批请求时审批条原位替换 KPI 行，绝不遮挡也不挤压下方卡片
        FrameLayout topSlot = new FrameLayout(this);
        kpisView = buildKpis();
        topSlot.addView(kpisView, new FrameLayout.LayoutParams(-1, dp(46)));
        permBanner = new LinearLayout(this);
        permBanner.setOrientation(LinearLayout.VERTICAL);
        permBanner.setVisibility(View.GONE);
        topSlot.addView(permBanner, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(-1, -2);
        kLp.topMargin = dp(8);
        root.addView(topSlot, kLp);

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
        Anim.enter(kpisView, 90, dp(14));
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
        conn.setPadding(dp(6), dp(3), dp(8), dp(3));
        conn.setBackground(Theme.panel(this, Theme.BRAND_B, 0.06f, 99));
        conn.setClickable(true);
        conn.setOnClickListener(v -> showServerConfigDialog());
        Anim.pressable(conn);
        LinearLayout.LayoutParams connLp = new LinearLayout.LayoutParams(-2, -2);
        connLp.topMargin = dp(3);
        connDot = new PulseDot(this);
        connDot.setState(Theme.MUTED, true);
        conn.addView(connDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        tvConn = Theme.text(this, Theme.MUTED, 11.5f, null);
        tvConn.setText("正在连接服务端…");
        tvConn.setPadding(dp(4), 0, 0, 0);
        tvConn.setSingleLine(true);
        conn.addView(tvConn);
        brand.addView(conn, connLp);
        top.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(buildShare(), new LinearLayout.LayoutParams(-2, dp(46)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 时钟：翻滚数字 + 秒环；下方日期 · 星期胶囊 · 年内进度
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        clockView = new ClockView(this);
        clock.addView(clockView, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        dateRow.setPadding(0, dp(5), dp(2), 0);
        tvDate = Theme.text(this, Theme.MUTED, 11.5f, Theme.MEDIUM);
        tvDate.setFontFeatureSettings("tnum");
        dateRow.addView(tvDate);
        tvWeek = Theme.text(this, Theme.BRAND_A, 10.5f, Theme.BOLD);
        tvWeek.setPadding(dp(7), dp(2), dp(7), dp(2));
        tvWeek.setBackground(Theme.pill(this, Theme.BRAND_A, 0.14f));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-2, -2);
        wLp.leftMargin = dp(6);
        dateRow.addView(tvWeek, wLp);
        clock.addView(dateRow);
        // 年内进度细条，宽度与时钟一致
        yearBar = new MixBar(this);
        LinearLayout.LayoutParams ybLp = new LinearLayout.LayoutParams(-1, dp(3));
        ybLp.topMargin = dp(6);
        clock.addView(yearBar, ybLp);
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
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat weekFmt = new SimpleDateFormat("EEE", Locale.CHINA);

    private void tickClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        Date now = c.getTime();
        tvDate.setText(dateFmt.format(now));
        tvWeek.setText(weekFmt.format(now) + " · W" + c.get(java.util.Calendar.WEEK_OF_YEAR));
        // 今年已过去的比例
        int doy = c.get(java.util.Calendar.DAY_OF_YEAR);
        int days = c.getActualMaximum(java.util.Calendar.DAY_OF_YEAR);
        // 年进度条：已过去部分用品牌色，剩余部分空槽
        yearBar.setParts(0, doy, 0, 0, Theme.BRAND_A);
        yearBar.setTail(days - doy);
    }

    // ---- 服务器发现（支持 USB / 手动配置 / UDP 广播秒级发现 / 并发局域网扫描） ----
    private void startServerDiscovery() {
        new Thread(() -> {
            // 1. 优先尝试用户手动保存的非本地地址或上次成功地址
            String saved = prefs.getString("server", null);
            if (saved != null && probe(saved)) { setServer(saved); return; }
            // 2. USB 模式：adb reverse 把 127.0.0.1:7777 转发到 Mac
            for (int attempt = 0; attempt < 2; attempt++) {
                if (probe("http://127.0.0.1:7777")) { Log.i(TAG, "USB模式命中 127.0.0.1:7777"); setServer("http://127.0.0.1:7777"); return; }
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            String env = System.getenv(ENV_SERVER);
            if (env != null && env.length() > 0 && probe(env)) { setServer(env); return; }
            // 3. UDP 局域网广播发现（50ms 内秒级响应，无需 adb）
            String udpHit = discoverViaUdp();
            if (udpHit != null && probe(udpHit)) { setServer(udpHit); return; }
            // 4. 并发局域网网段扫描（約 1.5s 扫完整个 /24）
            String scan = scanLanParallel();
            if (scan != null) setServer(scan);
            else {
                Log.w(TAG, "未找到任何服务端");
                ui.post(() -> setConn(false, "未找到服务端 · 点击此处配置 IP 或局域网扫描"));
            }
        }).start();
    }

    private String discoverViaUdp() {
        java.net.DatagramSocket sock = null;
        try {
            sock = new java.net.DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(700);
            byte[] req = "AGENTDECK_DISCOVER".getBytes("UTF-8");
            sock.send(new java.net.DatagramPacket(req, req.length,
                    InetAddress.getByName("255.255.255.255"), 7777));
            String lip = localIp();
            if (lip != null && lip.contains(".")) {
                String bcast = lip.substring(0, lip.lastIndexOf('.') + 1) + "255";
                sock.send(new java.net.DatagramPacket(req, req.length,
                        InetAddress.getByName(bcast), 7777));
            }
            byte[] buf = new byte[512];
            java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
            sock.receive(resp);
            JSONObject j = new JSONObject(new String(resp.getData(), 0, resp.getLength(), "UTF-8"));
            int port = j.optInt("port", 7777);
            return "http://" + resp.getAddress().getHostAddress() + ":" + port;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (sock != null) sock.close();
        }
    }

    private String scanLanParallel() {
        String ip = localIp();
        if (ip == null) return null;
        final String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
        final java.util.concurrent.atomic.AtomicReference<String> found = new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(24);
        for (int i = 1; i <= 254; i++) {
            final int host = i;
            pool.submit(() -> {
                if (found.get() != null) return;
                String base = "http://" + prefix + host + ":7777";
                if (probeFast(base, 450)) found.compareAndSet(null, base);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(2500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        return found.get();
    }

    private boolean probeFast(String base, int timeoutMs) {
        try {
            URL u = new URL(base + "/api/health");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void showServerConfigDialog() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        b.setTitle("连接其他设备 / 服务端地址");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(10), p, dp(6));

        TextView hint = Theme.text(this, Theme.MUTED, 12, null);
        String lip = localIp();
        hint.setText("支持局域网 WiFi、Tailscale 异地组网或公网地址（无需 ADB）。\n本机局域网 IP: " + (lip != null ? lip : "未知"));
        box.addView(hint);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Theme.TEXT);
        input.setHintTextColor(Theme.FAINT);
        input.setHint("例如 http://10.2.172.145:7777");
        input.setText(serverBase != null ? serverBase : prefs.getString("server", "http://"));
        box.addView(input);
        b.setView(box);

        b.setPositiveButton("连接并保存", (d, w) -> {
            String raw = input.getText().toString().trim();
            if (raw.isEmpty()) return;
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://" + raw;
            if (!raw.matches(".*:\\d+$") && !raw.contains("/")) raw = raw + ":7777";
            final String target = raw.replaceAll("/+$", "");
            setConn(false, "正在连接 " + hostOf(target) + "…");
            new Thread(() -> {
                if (probe(target)) setServer(target);
                else ui.post(() -> setConn(false, "无法连接 " + hostOf(target)));
            }).start();
        });
        b.setNeutralButton("局域网自动搜索", (d, w) -> {
            prefs.edit().remove("server").apply();
            setConn(false, "正在搜索局域网服务端…");
            startServerDiscovery();
        });
        b.setNegativeButton("取消", null);
        b.show();
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
        JSONArray pending = root.optJSONArray("pendingPermissions");
        boolean hasPending = pending != null && pending.length() > 0;
        kpisView.setVisibility(hasPending ? View.GONE : View.VISIBLE);
        PermissionSheet.bindPendingBanner(this, permBanner, serverBase, pending, this::fetchState);
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
        int mode = n == 1 ? 3 : (n == 2 ? 2 : 1);

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
            c.setLayoutMode(mode);
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

    /** 一张运行中 agent 的卡片：根据同屏数量自适应三栏（1张）/ 双栏（2张）/ 上下（3+张）。 */
    private final class Card {
        final LinearLayout root;
        final AgentIcon icon;
        final TextView name, model, task, uptime, permBadge, sub;
        final CountText today;
        final SparkView spark;
        final MixBar mix;
        final TextView mixLegend, mixStats;
        LinearLayout head, nameCol;
        int lastAccent;
        int width;
        int mode = 0;

        final LinearLayout left, mid, right, mixBox;
        final View leftSpacer;

        Card(String id) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(dp(16), dp(12), dp(16), dp(12));
            root.setClickable(true);
            root.setOnClickListener(v -> openDetail(id, lastAccent));
            Anim.pressable(root);

            // 左栏：顶层（图标 + 名称/模型 + 运行时长）；底层（全宽任务标题 + 精简审批胶囊）
            left = new LinearLayout(MainActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            head = new LinearLayout(MainActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon = new AgentIcon(MainActivity.this);
            icon.setLive(true);
            head.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout nc = new LinearLayout(MainActivity.this);
            nameCol = nc;
            nc.setOrientation(LinearLayout.VERTICAL);
            nc.setPadding(dp(10), 0, dp(6), 0);
            name = Theme.text(MainActivity.this, Theme.TEXT, 17, Theme.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            nc.addView(name);

            model = Theme.text(MainActivity.this, Theme.MUTED, 11f, null);
            model.setSingleLine(true);
            model.setEllipsize(TextUtils.TruncateAt.END);
            model.setPadding(0, dp(3), 0, 0);
            nc.addView(model);
            head.addView(nc, new LinearLayout.LayoutParams(0, -2, 1f));

            uptime = Theme.text(MainActivity.this, Theme.GREEN, 10.5f, Theme.MEDIUM);
            uptime.setFontFeatureSettings("tnum");
            uptime.setSingleLine(true);
            uptime.setPadding(dp(8), dp(3), dp(8), dp(3));
            uptime.setBackground(Theme.pill(MainActivity.this, Theme.GREEN, 0.14f));
            head.addView(uptime, new LinearLayout.LayoutParams(-2, -2));
            left.addView(head);

            leftSpacer = new View(MainActivity.this);
            left.addView(leftSpacer, new LinearLayout.LayoutParams(1, 0, 1f));

            // 底层：左为任务标题，右为紧凑审批胶囊（无 emoji，点击打开会话授权详情）
            LinearLayout taskRow = new LinearLayout(MainActivity.this);
            taskRow.setGravity(Gravity.CENTER_VERTICAL);
            task = Theme.text(MainActivity.this, Theme.TEXT, 12.5f, null);
            task.setPadding(dp(9), dp(2), dp(6), dp(2));
            task.setSingleLine(true);
            task.setEllipsize(TextUtils.TruncateAt.END);
            task.setAlpha(0.90f);
            taskRow.addView(task, new LinearLayout.LayoutParams(0, -2, 1f));

            permBadge = Theme.text(MainActivity.this, Theme.BRAND_A, 10f, Theme.BOLD);
            permBadge.setSingleLine(true);
            permBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            permBadge.setClickable(true);
            Anim.pressable(permBadge);
            LinearLayout.LayoutParams pmLp = new LinearLayout.LayoutParams(-2, -2);
            pmLp.leftMargin = dp(6);
            taskRow.addView(permBadge, pmLp);
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
            trLp.topMargin = dp(6);
            left.addView(taskRow, trLp);

            // 中栏：今日用量 + token 构成
            mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mixBox = new LinearLayout(MainActivity.this);
            mixBox.setOrientation(LinearLayout.VERTICAL);
            mix = new MixBar(MainActivity.this);
            mixBox.addView(mix, new LinearLayout.LayoutParams(-1, dp(6)));
            mixStats = Theme.text(MainActivity.this, Theme.MUTED, 10f, null);
            mixStats.setFontFeatureSettings("tnum");
            mixStats.setSingleLine(true);
            mixStats.setEllipsize(TextUtils.TruncateAt.END);
            mixStats.setPadding(0, dp(5), 0, 0);
            mixBox.addView(mixStats);

            LinearLayout tc = new LinearLayout(MainActivity.this);
            tc.setOrientation(LinearLayout.VERTICAL);
            LinearLayout tlRow = new LinearLayout(MainActivity.this);
            tlRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView tl = Theme.text(MainActivity.this, Theme.MUTED, 10, Theme.MEDIUM);
            tl.setText("今日");
            tl.setSingleLine(true);
            tl.setLetterSpacing(0.08f);
            tlRow.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
            mixLegend = Theme.text(MainActivity.this, Theme.FAINT, 10, null);
            mixLegend.setSingleLine(true);
            mixLegend.setFontFeatureSettings("tnum");
            tlRow.addView(mixLegend);
            tc.addView(tlRow);
            today = new CountText(MainActivity.this);
            today.setTextSize(24);
            today.setTypeface(Theme.BOLD);
            today.setPadding(0, dp(2), 0, 0);
            tc.addView(today);
            mid.addView(tc);
            mid.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(1, 0, 1f));
            mid.addView(mixBox);

            // 右栏：累计 / 会话 + 带刻度的走势线
            right = new LinearLayout(MainActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            sub = Theme.text(MainActivity.this, Theme.MUTED, 10.5f, null);
            sub.setGravity(Gravity.END);
            sub.setFontFeatureSettings("tnum");
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            right.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            spark = new SparkView(MainActivity.this);
            spark.setAxis(true);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            spLp.topMargin = dp(6);
            right.addView(spark, spLp);

            root.addView(left);
            root.addView(mid);
            root.addView(right);
            setLayoutMode(3);
        }

        /**
         * m=3：全宽单卡，左/中/右三栏全开；
         * m=2：两张并排，隐藏中栏，左右两栏（左栏给足宽度容纳名称、模型与审批胶囊，右栏显示今日用量与走势）；
         * m=1：三张以上，上下排。
         */
        void setLayoutMode(int m) {
            if (m == mode) return;
            mode = m;
            root.setOrientation(m >= 2 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if (m == 3) {
                mid.setVisibility(View.VISIBLE);
                leftSpacer.setVisibility(View.VISIBLE);
                left.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.15f));
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -1, 1.15f);
                mp.leftMargin = dp(18);
                mid.setLayoutParams(mp);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 0.95f);
                rp.leftMargin = dp(18);
                right.setLayoutParams(rp);
            } else if (m == 2) {
                mid.setVisibility(View.GONE);
                leftSpacer.setVisibility(View.VISIBLE);
                left.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.25f));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 0.95f);
                rp.leftMargin = dp(14);
                right.setLayoutParams(rp);
            } else {
                mid.setVisibility(View.GONE);
                leftSpacer.setVisibility(View.GONE);
                left.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
                rp.topMargin = dp(6);
                right.setLayoutParams(rp);
            }
            spark.setAxis(m >= 2);
            name.setTextSize(m == 3 ? 18 : (m == 2 ? 16.5f : 15f));
            ViewGroup.LayoutParams il = icon.getLayoutParams();
            il.width = il.height = dp(m >= 2 ? 42 : 34);
            icon.setLayoutParams(il);
        }

        void bind(Agent a) {
            int accent = Theme.color(a.color, Theme.MUTED);
            if (accent != lastAccent) {
                lastAccent = accent;
                today.setTextColor(accent);
                icon.setAgent(a.id, a.name, accent);
                root.setBackground(Theme.panel(MainActivity.this, accent, 0.22f, 20));
                task.setBackground(new QuoteBar(accent, dp(3)));
            }
            icon.setStatus(Theme.GREEN, true);
            name.setText(a.name);
            int nameW = nameCol.getWidth() - nameCol.getPaddingLeft() - nameCol.getPaddingRight();
            if (nameW > 0) fitText(name, a.name, nameW, mode == 3 ? 17.5f : 16f, 12);
            model.setText(a.topModel.isEmpty() ? (a.procCount > 0 ? "进程 " + a.procCount : "运行中") : shortModel(a.topModel));
            uptime.setText(a.uptimeSec > 0 ? dur(a.uptimeSec) : "LIVE");

            int pmc = PermissionSheet.modeColor(a.topPermMode);
            permBadge.setText((mode == 3 ? PermissionSheet.modeLabel(a.topPermMode) : PermissionSheet.modeShortLabel(a.topPermMode)) + " ›");
            permBadge.setTextColor(pmc);
            permBadge.setBackground(Theme.pill(MainActivity.this, pmc, 0.16f));
            final String sid = a.topSessionId;
            final String aid = a.id;
            final int acc = accent;
            permBadge.setOnClickListener(v -> PermissionSheet.showSessionSheet(MainActivity.this, serverBase != null ? serverBase : "", aid, sid, acc));
            task.setClickable(true);
            task.setOnClickListener(v -> PermissionSheet.showSessionSheet(MainActivity.this, serverBase != null ? serverBase : "", aid, sid, acc));

            String t = a.topTitle == null ? "" : a.topTitle.replaceAll("\\s+", " ").trim();
            task.setText(t.isEmpty() ? "正在运行，暂无会话标题" : t);

            today.setValue(a.todayTokens);
            if (mode == 3) {
                sub.setText((a.tokensTotal > 0 ? "累计 " + Theme.fmtShort(a.tokensTotal) + " · " : "")
                        + a.sessions + " 会话" + (a.share > 0 ? " · " + a.share + "%" : ""));
            } else {
                // 双卡/多卡时把今日用量高亮写在右栏顶部
                sub.setGravity(mode == 2 ? Gravity.END : Gravity.START);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                sb.append("今日 ");
                int st = sb.length();
                sb.append(Theme.fmt(a.todayTokens));
                sb.setSpan(new android.text.style.ForegroundColorSpan(accent), st, sb.length(), 0);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), 0);
                if (a.tokensTotal > 0) sb.append("  ·  累计 ").append(Theme.fmtShort(a.tokensTotal));
                else if (a.sessions > 0) sb.append("  ·  ").append(String.valueOf(a.sessions)).append(" 会话");
                sub.setText(sb);
            }
            spark.setData(a.series, accent);

            mix.setParts(a.tokCacheR, a.tokIn, a.tokCacheW, a.tokOut, accent);
            long sum = a.tokCacheR + a.tokIn + a.tokCacheW + a.tokOut;
            mixLegend.setText(sum > 0 ? "缓存 " + Math.round(a.tokCacheR * 100.0 / sum) + "%" : "");
            mixStats.setText(legend(accent,
                    "缓存", a.tokCacheR + a.tokCacheW, Theme.alpha(accent, 0.55f),
                    "输入", a.tokIn, accent,
                    "输出", a.tokOut, Theme.mix(accent, 0xFFFFFFFF, 0.75f)));
        }

        /** 「● 缓存 301M  ● 输入 67M  ● 输出 2.8M」，圆点与数值各自着色。 */
        private CharSequence legend(int accent, Object... kv) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            for (int i = 0; i < kv.length; i += 3) {
                if (sb.length() > 0) sb.append("  ");
                int color = (int) kv[i + 2];
                int st = sb.length();
                sb.append("●");
                sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(), 0);
                sb.append((String) kv[i]).append('\u2009');
                st = sb.length();
                sb.append(Theme.fmtShort((long) kv[i + 1]));
                sb.setSpan(new android.text.style.ForegroundColorSpan(Theme.TEXT), st, sb.length(), 0);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, sb.length(), 0);
            }
            return sb;
        }
    }

    /** 任务标题左侧的强调色竖线。 */
    private static final class QuoteBar extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int w;
        QuoteBar(int color, int w) { p.setColor(Theme.alpha(color, 0.8f)); this.w = w; }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawRoundRect(b.left, b.top, b.left + w, b.bottom, w / 2f, w / 2f, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 只保留模型名本身：去掉 provider/网关前缀和 [1m] 这类上下文标记。 */
    static String shortModel(String m) {
        String s = m.replaceAll("\\[[^\\]]*\\]$", "");
        int slash = s.lastIndexOf('/');
        return slash >= 0 && slash < s.length() - 1 ? s.substring(slash + 1) : s;
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
            final String sessionId = r.optString("sessionId", "");
            final int color = Theme.color(r.optString("color", ""), Theme.MUTED);
            View rv = recentRow(r, color);
            rv.setOnClickListener(v -> {
                if (!agentId.isEmpty()) {
                    PermissionSheet.showSessionSheet(this, serverBase != null ? serverBase : "", agentId, sessionId, color);
                }
            });
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
