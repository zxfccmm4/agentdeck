package com.agentdeck.desk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 单个 agent 的详情页：走势图（72h + 30d）+ 会话列表。
 */
public class AgentDetailActivity extends Activity {
    private static final String TAG = "AgentDeckDetail";
    private TextView tvTitle, tvSub, tvBack;
    private LinearLayout sessionList;
    private TrendView trendHourly, trendDaily;
    private String base, agentId, agentName;
    private int accent = Color.parseColor("#6EE7B7");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        agentId = getIntent().getStringExtra("agentId");
        base = getIntent().getStringExtra("base");
        agentName = agentId != null ? agentId : "";
        buildUi();
        new Thread(this::fetchDetail).start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0e14"));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // 顶栏：返回 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        tvBack = new TextView(this);
        tvBack.setText("‹  返回");
        tvBack.setTextColor(Color.parseColor("#8b949e"));
        tvBack.setTextSize(18);
        tvBack.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvBack.setPadding(dp(8), dp(8), dp(16), dp(8));
        tvBack.setOnClickListener(v -> finish());
        top.addView(tvBack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.parseColor("#e6edf3"));
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setGravity(Gravity.END);
        top.addView(tvTitle);
        root.addView(top);

        tvSub = new TextView(this);
        tvSub.setTextColor(Color.parseColor("#8b949e"));
        tvSub.setTextSize(13);
        tvSub.setPadding(dp(8), 0, 0, dp(8));
        root.addView(tvSub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(8), 0, 0, 0);

        // 两个走势图并排放
        LinearLayout trendRow = new LinearLayout(this);
        trendRow.setOrientation(LinearLayout.HORIZONTAL);
        trendHourly = new TrendView(this);
        trendDaily = new TrendView(this);
        trendRow.addView(trendHourly, new LinearLayout.LayoutParams(0, dp(150), 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(10), ViewGroup.LayoutParams.MATCH_PARENT);
        trendRow.addView(spacer(), gap);
        trendRow.addView(trendDaily, new LinearLayout.LayoutParams(0, dp(150), 1f));
        inner.addView(trendRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        // 会话标题
        TextView sh = new TextView(this);
        sh.setText("会话列表");
        sh.setTextColor(Color.parseColor("#e6edf3"));
        sh.setTextSize(15);
        sh.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        sh.setPadding(0, dp(12), 0, dp(6));
        inner.addView(sh);

        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        inner.addView(sessionList);

        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private View spacer() {
        View v = new View(this);
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
        agentName = d.optString("name", agentId);
        int c = safeColor(d.optString("color", "#6EE7B7"));
        accent = c;
        tvTitle.setText(agentName);
        tvTitle.setTextColor(c);
        JSONObject totals = d.optJSONObject("totals");
        String sub = "";
        if (totals != null) {
            JSONObject tok = totals.optJSONObject("tokens");
            long total = tok != null ? tok.optLong("total", 0) : 0;
            double cost = totals.optDouble("costUsd", 0);
            int sess = totals.optInt("sessions", 0);
            sub = String.format(Locale.US, "%d 会话 · 总用量 %s · 花费 $%.4f", sess, fmt(total), cost);
        }
        tvSub.setText(sub);

        // 走势图
        JSONObject series = d.optJSONObject("series");
        if (series != null) {
            JSONObject hourly = series.optJSONObject("hourly");
            if (hourly != null) setTrend(trendHourly, hourly, c, agentName + " · 近72小时");
            JSONObject daily = series.optJSONObject("daily");
            if (daily != null) setTrend(trendDaily, daily, c, agentName + " · 近30天");
        }

        // 会话列表
        sessionList.removeAllViews();
        JSONArray list = d.optJSONArray("sessions");
        if (list != null) {
            int n = Math.min(list.length(), 30);
            for (int i = 0; i < n; i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) continue;
                sessionList.addView(sessionRow(s, c, i == 0));
            }
        }
        if (list == null || list.length() == 0) {
            TextView t = new TextView(this);
            t.setText("暂无会话记录");
            t.setTextColor(Color.parseColor("#8b949e"));
            t.setTextSize(14);
            sessionList.addView(t);
        }
    }

    private void setTrend(TrendView tv, JSONObject j, int c, String title) {
        JSONArray d = j.optJSONArray("data");
        JSONArray l = j.optJSONArray("labels");
        if (d == null) return;
        long[] data = new long[d.length()];
        for (int i = 0; i < d.length(); i++) data[i] = (long) d.optLong(i, 0);
        String[] labels = null;
        if (l != null) {
            labels = new String[l.length()];
            for (int i = 0; i < l.length(); i++) labels[i] = l.optString(i, "");
        }
        tv.setData(data, labels, c, title);
    }

    private View sessionRow(JSONObject s, int c, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(c, 0.06f, 1f, dp(8)));
        int p = dp(10);
        row.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setTextColor(Color.parseColor("#e6edf3"));
        title.setTextSize(14);
        title.setText(s.optString("title", "(no title)"));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        TextView m = new TextView(this);
        m.setTextColor(Color.parseColor("#8b949e"));
        m.setTextSize(12);
        long tok = totalOf(s.optJSONObject("tokens"));
        long lastAt = s.optLong("lastAt", 0);
        String t = "";
        if (lastAt > 0) t = ago(lastAt) + " · ";
        String model = s.optString("model", "");
        String mm = (model != null && !model.isEmpty()) ? model : "—";
        boolean act = s.optBoolean("active", false);
        String st = act ? "● 运行中" : "";
        m.setText(st + t + mm + " · " + fmt(tok) + " tokens");
        meta.addView(m);
        row.addView(meta);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(first ? 8 : 5);
        row.setLayoutParams(lp);
        return row;
    }

    private long totalOf(JSONObject t) {
        return t != null ? t.optLong("total", 0) : 0;
    }

    private String ago(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0) diff = 0;
        long min = diff / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小时前";
        long d = hr / 24;
        if (d < 30) return d + " 天前";
        Date dt = new Date(ts);
        return new SimpleDateFormat("M/d", Locale.getDefault()).format(dt);
    }

    static String fmt(long n) {
        if (n >= 1_000_000_000) return String.format(Locale.US, "%.2fB", n / 1e9);
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private android.graphics.drawable.GradientDrawable roundedBg(int color, float alpha, float strokeW, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        int a = (int) (alpha * 255);
        g.setColor((a << 24) | (color & 0xffffff));
        if (strokeW > 0) g.setStroke(dp((int) strokeW), Color.parseColor("#222b38"));
        g.setCornerRadius(radius);
        return g;
    }

    private static int safeColor(String c) {
        if (c != null && c.startsWith("#") && c.length() == 7) {
            try { return Color.parseColor(c); } catch (Exception ignored) {}
        }
        return Color.parseColor("#6EE7B7");
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}