package com.agentdeck.desk;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 单个 agent 的详情页：汇总指标 + 走势图（72h + 30d）+ 会话列表。
 */
public class AgentDetailActivity extends Activity {
    private TextView tvTitle, tvSub;
    private CountText kSessions, kTokens, kCost, kActive;
    private LinearLayout sessionList;
    private TrendView trendHourly, trendDaily;
    private AgentIcon titleIcon;
    private String base, agentId;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        agentId = getIntent().getStringExtra("agentId");
        base = getIntent().getStringExtra("base");
        accent = getIntent().getIntExtra("color", Theme.BRAND_A);
        buildUi();
        new Thread(this::fetchDetail).start();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_out_right);
    }

    private int dp(float v) { return Theme.dp(this, v); }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        GlowBackground glow = new GlowBackground(this);
        glow.setColors(accent, Theme.BRAND_B);
        frame.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), 0);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // 顶栏：返回 + 标题 + 小 logo
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Theme.text(this, Theme.TEXT, 14, Theme.MEDIUM);
        back.setText("‹  返回");
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(14), 0, dp(16), 0);
        back.setBackground(Theme.panel(this, Theme.TEXT, 0.04f, 99));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        Anim.pressable(back);
        top.addView(back, new LinearLayout.LayoutParams(-2, dp(38)));

        titleIcon = new AgentIcon(this);
        titleIcon.setAgent(agentId, agentId, accent);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        tdLp.leftMargin = dp(14);
        top.addView(titleIcon, tdLp);

        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(10), 0, 0, 0);
        tvTitle = Theme.text(this, Theme.TEXT, 20, Theme.BOLD);
        tvTitle.setText(agentId != null ? agentId : "");
        tcol.addView(tvTitle);
        tvSub = Theme.text(this, Theme.MUTED, 11, null);
        tvSub.setPadding(0, dp(3), 0, 0);
        tvSub.setText("加载中…");
        tcol.addView(tvSub);
        top.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));

        LogoView logo = new LogoView(this);
        logo.setAnimated(false);
        top.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(44)));
        Anim.enter(top, 60, -dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, dp(12), 0, dp(16));

        // 指标
        LinearLayout kpis = new LinearLayout(this);
        kSessions = kpi(kpis, "会话", true);
        kSessions.setFmt(v -> String.valueOf(Math.round(v)));
        kTokens = kpi(kpis, "总 TOKEN", true);
        kCost = kpi(kpis, "花费", true);
        kCost.setFmt(Theme::money);
        kActive = kpi(kpis, "运行中", false);
        kActive.setFmt(v -> String.valueOf(Math.round(v)));
        inner.addView(kpis, new LinearLayout.LayoutParams(-1, dp(58)));
        Anim.enter(kpis, 120, dp(14));

        // 两个走势图并排放
        LinearLayout trendRow = new LinearLayout(this);
        trendHourly = new TrendView(this);
        trendDaily = new TrendView(this);
        trendHourly.setData(null, null, accent, "近 72 小时");
        trendDaily.setData(null, null, accent, "近 30 天");
        trendRow.addView(trendHourly, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -1, 1f);
        gap.leftMargin = dp(10);
        trendRow.addView(trendDaily, gap);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, dp(170));
        trLp.topMargin = dp(10);
        inner.addView(trendRow, trLp);
        Anim.enter(trendRow, 200, dp(18));

        TextView sh = Theme.text(this, Theme.MUTED, 11, Theme.MEDIUM);
        sh.setText("会话列表");
        sh.setLetterSpacing(0.1f);
        sh.setPadding(dp(2), dp(16), 0, dp(8));
        inner.addView(sh);

        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        inner.addView(sessionList);

        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(frame);
    }

    private CountText kpi(LinearLayout row, String label, boolean gap) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        box.setBackground(Theme.panel(this, accent, 0.10f, 14));
        TextView l = Theme.text(this, Theme.MUTED, 10.5f, Theme.MEDIUM);
        l.setText(label);
        l.setLetterSpacing(0.08f);
        box.addView(l);
        CountText v = new CountText(this);
        v.setTextColor(Theme.TEXT);
        v.setTextSize(20);
        v.setTypeface(Theme.BOLD);
        v.setPadding(0, dp(6), 0, 0);
        v.setPlaceholder("—");
        box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (gap) lp.rightMargin = dp(10);
        row.addView(box, lp);
        return v;
    }

    private void fetchDetail() {
        if (base == null || base.isEmpty()) { uiText("未配置服务端"); return; }
        try {
            URL u = new URL(base + "/api/agents/" + agentId);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) { c.disconnect(); uiText("获取失败"); return; }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close(); c.disconnect();
            final JSONObject d = new JSONObject(sb.toString());
            runOnUiThread(() -> renderDetail(d));
        } catch (Exception e) {
            uiText("加载失败: " + e.getMessage());
        }
    }

    private void uiText(final String msg) {
        runOnUiThread(() -> tvSub.setText(msg));
    }

    private void renderDetail(JSONObject d) {
        String name = d.optString("name", agentId);
        accent = Theme.color(d.optString("color", ""), accent);
        tvTitle.setText(name);

        JSONArray list = d.optJSONArray("sessions");
        int active = 0;
        String model = "";
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            if (s.optBoolean("active", false)) active++;
            if (model.isEmpty()) model = s.optString("model", "");
        }
        titleIcon.setAgent(agentId, name, accent);
        titleIcon.setStatus(active > 0 ? Theme.GREEN : 0, active > 0);
        tvSub.setText((active > 0 ? "运行中" : "空闲") + (model.isEmpty() ? "" : " · 最近模型 " + model));

        JSONObject totals = d.optJSONObject("totals");
        if (totals != null) {
            JSONObject tok = totals.optJSONObject("tokens");
            kSessions.setValue(totals.optInt("sessions", 0));
            kTokens.setValue(tok != null ? tok.optLong("total", 0) : 0);
            kCost.setValue(totals.optDouble("costUsd", 0));
        }
        kActive.setValue(active);

        JSONObject series = d.optJSONObject("series");
        if (series != null) {
            JSONObject hourly = series.optJSONObject("hourly");
            if (hourly != null) setTrend(trendHourly, hourly, "近 72 小时", true);
            JSONObject daily = series.optJSONObject("daily");
            if (daily != null) setTrend(trendDaily, daily, "近 30 天", false);
        }

        sessionList.removeAllViews();
        int n = list == null ? 0 : Math.min(list.length(), 30);
        for (int i = 0; i < n; i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            View row = sessionRow(s);
            sessionList.addView(row);
            if (i < 10) Anim.enter(row, 260 + i * 45L, dp(14));
        }
        if (n == 0) {
            TextView t = Theme.text(this, Theme.FAINT, 13, null);
            t.setText("暂无会话记录");
            t.setPadding(dp(2), dp(4), 0, 0);
            sessionList.addView(t);
        }
    }

    private void setTrend(TrendView tv, JSONObject j, String title, boolean hourly) {
        JSONArray d = j.optJSONArray("data");
        JSONArray l = j.optJSONArray("labels");
        if (d == null) return;
        int n = d.length();
        long[] data = new long[n];
        for (int i = 0; i < n; i++) data[i] = d.optLong(i, 0);
        String[] labels = new String[l == null ? 0 : l.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = l.optString(i, "");
        if (hourly) {
            // 与服务端分桶一致：最后一桶是当前整点
            long hour = 3600_000L;
            long end = System.currentTimeMillis() / hour * hour;
            SimpleDateFormat f = new SimpleDateFormat("M/d HH:00", Locale.getDefault());
            String[] tips = new String[n];
            for (int i = 0; i < n; i++) tips[i] = f.format(new Date(end - (n - 1 - i) * hour));
            tv.setTips(tips);
        }
        tv.setData(data, labels, accent, title);
    }

    private View sessionRow(JSONObject s) {
        boolean act = s.optBoolean("active", false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Theme.panel(this, accent, act ? 0.14f : 0.05f, 14));
        row.setPadding(dp(12), dp(10), dp(14), dp(10));

        View stripe = new View(this);
        stripe.setBackground(Theme.pill(this, act ? Theme.GREEN : accent, act ? 1f : 0.5f));
        row.addView(stripe, new LinearLayout.LayoutParams(dp(3), dp(30)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(10), 0);
        TextView title = Theme.text(this, Theme.TEXT, 13.5f, null);
        title.setText(s.optString("title", "(无标题)"));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        TextView meta = Theme.text(this, Theme.MUTED, 11, null);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(4), 0, 0);
        long lastAt = s.optLong("lastAt", 0);
        String model = s.optString("model", "");
        int turns = s.optInt("turns", 0);
        StringBuilder m = new StringBuilder();
        if (lastAt > 0) m.append(Theme.ago(lastAt));
        if (!model.isEmpty()) m.append(m.length() > 0 ? " · " : "").append(model);
        if (turns > 0) m.append(" · ").append(turns).append(" 轮");
        meta.setText(m.length() > 0 ? m : "—");
        col.addView(meta);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        if (act) {
            TextView badge = Theme.text(this, Theme.GREEN, 10.5f, Theme.MEDIUM);
            badge.setText("运行中");
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(Theme.pill(this, Theme.GREEN, 0.14f));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.rightMargin = dp(12);
            row.addView(badge, bLp);
        }

        JSONObject tk = s.optJSONObject("tokens");
        TextView tokens = Theme.text(this, accent, 15, Theme.BOLD);
        tokens.setFontFeatureSettings("tnum");
        tokens.setText(Theme.fmt(tk != null ? tk.optLong("total", 0) : 0));
        row.addView(tokens);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }
}
