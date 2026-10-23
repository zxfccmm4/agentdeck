package com.agentdeck.desk;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 会话授权情况与远程审批组件：
 * 1. PermissionBanner：嵌入主屏/详情页顶部的实时待授权审批条，支持一键「允许 / 始终允许 / 拒绝」
 * 2. showSessionDialog：点击任意会话后弹出的授权详情抽屉（授权模式、工具调用统计、最近调用与拒绝记录、生效规则）
 */
final class PermissionSheet {
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private PermissionSheet() {}

    static String modeLabel(String mode) {
        if (mode == null || mode.isEmpty()) return "默认询问";
        switch (mode) {
            case "auto": return "AUTO 自动审批";
            case "bypassPermissions": return "BYPASS 全放行";
            case "acceptEdits": return "自动放行编辑";
            case "dontAsk": return "DONT ASK";
            case "plan": return "PLAN 只读规划";
            default: return "DEFAULT 询问";
        }
    }

    /** 卡片上用的极简短标签，保证单行不换行。 */
    static String modeShortLabel(String mode) {
        if (mode == null || mode.isEmpty()) return "询问";
        switch (mode) {
            case "auto": return "AUTO";
            case "bypassPermissions": return "BYPASS";
            case "acceptEdits": return "EDITS";
            case "dontAsk": return "SILENT";
            case "plan": return "PLAN";
            default: return "询问";
        }
    }

    static int modeColor(String mode) {
        if (mode == null) return Theme.BRAND_B;
        switch (mode) {
            case "auto": return Theme.BRAND_A;
            case "bypassPermissions": return 0xFFFACC15;
            case "acceptEdits": return Theme.BRAND_B;
            case "plan": return Theme.BRAND_C;
            default: return Theme.MUTED;
        }
    }

    interface AfterDecide { void onDecided(); }

    /**
     * 渲染顶部实时待授权审批条。没有待批项时自动隐藏。
     */
    static void bindPendingBanner(Activity act, LinearLayout container, String base, JSONArray pending, AfterDecide cb) {
        int n = pending == null ? 0 : pending.length();
        if (n == 0) {
            container.removeAllViews();
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        container.removeAllViews();

        JSONObject p = pending.optJSONObject(0);
        if (p == null) return;
        final String id = p.optString("id", "");
        final String tool = p.optString("toolName", "Tool");
        final String summary = p.optString("summary", "");
        final String desc = p.optString("description", "");
        final String cwd = p.optString("shortCwd", "");
        final JSONArray suggestions = p.optJSONArray("suggestions");
        final boolean hasRule = suggestions != null && suggestions.length() > 0;

        int warn = 0xFFF59E0B;
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Theme.dp(act, 14), Theme.dp(act, 7), Theme.dp(act, 12), Theme.dp(act, 7));
        card.setBackground(Theme.panel(act, warn, 0.28f, 14));
        card.setClickable(true);

        PulseDot dot = new PulseDot(act);
        dot.setState(warn, true);
        card.addView(dot, new LinearLayout.LayoutParams(Theme.dp(act, 20), Theme.dp(act, 20)));

        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Theme.dp(act, 10), 0, Theme.dp(act, 12), 0);

        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Theme.text(act, warn, 10.5f, Theme.BOLD);
        badge.setText("待授权" + (n > 1 ? " (" + n + ")" : ""));
        badge.setPadding(Theme.dp(act, 7), Theme.dp(act, 2), Theme.dp(act, 7), Theme.dp(act, 2));
        badge.setBackground(Theme.pill(act, warn, 0.18f));
        titleRow.addView(badge);

        TextView toolTv = Theme.text(act, Theme.TEXT, 14, Theme.BOLD);
        toolTv.setText("  " + tool);
        titleRow.addView(toolTv);

        if (!cwd.isEmpty()) {
            TextView cwdTv = Theme.text(act, Theme.MUTED, 11, null);
            cwdTv.setText("  ·  " + cwd);
            cwdTv.setSingleLine(true);
            titleRow.addView(cwdTv);
        }
        info.addView(titleRow);

        TextView cmdTv = Theme.text(act, Theme.TEXT, 12, null);
        cmdTv.setSingleLine(true);
        cmdTv.setEllipsize(TextUtils.TruncateAt.END);
        cmdTv.setPadding(0, Theme.dp(act, 4), 0, 0);
        cmdTv.setText(!summary.isEmpty() ? summary : (!desc.isEmpty() ? desc : "请求执行 " + tool));
        info.addView(cmdTv);

        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        // 操作按钮组
        card.addView(actionBtn(act, "允许", Theme.GREEN, true, v -> sendDecision(base, id, "allow", cb)));
        if (hasRule) {
            View b2 = actionBtn(act, "始终允许", Theme.BRAND_B, false, v -> sendDecision(base, id, "allow_always", cb));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, Theme.dp(act, 34));
            lp2.leftMargin = Theme.dp(act, 8);
            card.addView(b2, lp2);
        }
        View bDeny = actionBtn(act, "拒绝", Theme.RED, false, v -> sendDecision(base, id, "deny", cb));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-2, Theme.dp(act, 34));
        lp3.leftMargin = Theme.dp(act, 8);
        card.addView(bDeny, lp3);

        container.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private static View actionBtn(Context c, String text, int color, boolean solid, View.OnClickListener click) {
        TextView b = Theme.text(c, solid ? 0xFF07090F : color, 12, Theme.BOLD);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Theme.dp(c, 14), 0, Theme.dp(c, 14), 0);
        b.setBackground(Theme.pill(c, color, solid ? 0.92f : 0.16f));
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, Theme.dp(c, 34)));
        b.setClickable(true);
        b.setOnClickListener(click);
        Anim.pressable(b);
        return b;
    }

    static void sendDecision(String base, String id, String action, AfterDecide cb) {
        if (base == null || base.isEmpty() || id == null || id.isEmpty()) return;
        new Thread(() -> {
            try {
                URL u = new URL(base + "/api/permissions/decide");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = ("{\"id\":\"" + id + "\",\"action\":\"" + action + "\"}").getBytes("UTF-8");
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
            if (cb != null) UI.post(cb::onDecided);
        }).start();
    }

    /**
     * 在当前 Activity 根 FrameLayout 上弹出「会话授权情况」浮层面板。
     */
    static void showSessionSheet(Activity act, String base, String agentId, String sessionId, int accent) {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || content.getChildCount() == 0) return;
        View rootChild = content.getChildAt(0);
        if (!(rootChild instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) rootChild;

        FrameLayout overlay = new FrameLayout(act);
        overlay.setBackgroundColor(Theme.alpha(0xFF000000, 0.68f));
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissOverlay(host, overlay));

        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(act, 18);
        sheet.setPadding(pad, Theme.dp(act, 14), pad, Theme.dp(act, 14));
        sheet.setBackground(Theme.panel(act, accent, 0.14f, 20));
        sheet.setClickable(true); // 拦截内部点击

        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                (int) (act.getResources().getDisplayMetrics().widthPixels * 0.86f),
                (int) (act.getResources().getDisplayMetrics().heightPixels * 0.86f));
        sLp.gravity = Gravity.CENTER;
        overlay.addView(sheet, sLp);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        Anim.enter(sheet, 0, Theme.dp(act, 20));

        // 顶栏
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        AgentIcon ic = new AgentIcon(act);
        ic.setAgent(agentId, agentId, accent);
        top.addView(ic, new LinearLayout.LayoutParams(Theme.dp(act, 36), Theme.dp(act, 36)));

        LinearLayout tcol = new LinearLayout(act);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(Theme.dp(act, 10), 0, Theme.dp(act, 10), 0);
        TextView tvTitle = Theme.text(act, Theme.TEXT, 16, Theme.BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvTitle.setText("正在加载会话授权详情…");
        tcol.addView(tvTitle);

        TextView tvSub = Theme.text(act, Theme.MUTED, 11, null);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvSub.setPadding(0, Theme.dp(act, 3), 0, 0);
        tvSub.setText("Session " + (sessionId == null ? "" : sessionId));
        tcol.addView(tvSub);
        top.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView modeBadge = Theme.text(act, Theme.BRAND_A, 11, Theme.BOLD);
        modeBadge.setPadding(Theme.dp(act, 10), Theme.dp(act, 4), Theme.dp(act, 10), Theme.dp(act, 4));
        top.addView(modeBadge);

        TextView close = Theme.text(act, Theme.MUTED, 13, Theme.BOLD);
        close.setText("✕ 关闭");
        close.setPadding(Theme.dp(act, 12), Theme.dp(act, 6), Theme.dp(act, 12), Theme.dp(act, 6));
        close.setBackground(Theme.panel(act, Theme.TEXT, 0.04f, 99));
        close.setClickable(true);
        close.setOnClickListener(v -> dismissOverlay(host, overlay));
        Anim.pressable(close);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.leftMargin = Theme.dp(act, 10);
        top.addView(close, cLp);
        sheet.addView(top);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Theme.dp(act, 10), 0, 0);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        fetchAndRenderSession(act, base, agentId, sessionId, accent, tvTitle, tvSub, modeBadge, body);
    }

    private static void dismissOverlay(FrameLayout host, View overlay) {
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> host.removeView(overlay)).start();
    }

    private static void fetchAndRenderSession(
            Activity act, String base, String agentId, String sessionId, int accent,
            TextView tvTitle, TextView tvSub, TextView modeBadge, LinearLayout body) {
        new Thread(() -> {
            try {
                String path = "/api/agents/" + agentId + "/sessions/" + (sessionId == null || sessionId.isEmpty() ? "latest" : sessionId);
                URL u = new URL(base + path);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(3500);
                c.setReadTimeout(3500);
                if (c.getResponseCode() != 200) {
                    c.disconnect();
                    UI.post(() -> tvTitle.setText("获取会话授权信息失败"));
                    return;
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                c.disconnect();
                final JSONObject d = new JSONObject(sb.toString());
                UI.post(() -> renderSheetBody(act, base, accent, d, tvTitle, tvSub, modeBadge, body));
            } catch (Exception e) {
                UI.post(() -> tvTitle.setText("加载失败: " + e.getMessage()));
            }
        }).start();
    }

    private static void renderSheetBody(
            Activity act, String base, int accent, JSONObject d,
            TextView tvTitle, TextView tvSub, TextView modeBadge, LinearLayout body) {
        body.removeAllViews();
        tvTitle.setText(d.optString("title", "会话授权详情"));
        String cwd = d.optString("cwd", "");
        String model = d.optString("model", "");
        int turns = d.optInt("turns", 0);
        tvSub.setText((cwd.isEmpty() ? "" : cwd + "  ·  ") + (model.isEmpty() ? "" : model + "  ·  ") + turns + " 轮");

        String pMode = d.optString("permissionMode", "default");
        int mc = modeColor(pMode);
        modeBadge.setText(modeLabel(pMode));
        modeBadge.setTextColor(mc);
        modeBadge.setBackground(Theme.pill(act, mc, 0.16f));

        // 1. 当前待审批请求（若有）
        LinearLayout pendingBox = new LinearLayout(act);
        pendingBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(pendingBox);
        JSONArray pending = d.optJSONArray("pending");
        if (pending != null && pending.length() > 0) {
            bindPendingBanner(act, pendingBox, base, pending, () ->
                    fetchAndRenderSession(act, base, d.optString("agent", "claude"), d.optString("sessionId", ""), accent, tvTitle, tvSub, modeBadge, body));
        }

        // 2. 工具授权与调用统计（横向芯片）
        JSONArray tools = d.optJSONArray("tools");
        if (tools != null && tools.length() > 0) {
            body.addView(secHeader(act, "已授权调用的工具 (" + tools.length() + ")"));
            HorizontalScrollView hs = new HorizontalScrollView(act);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(act);
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                TextView chip = Theme.text(act, Theme.TEXT, 11.5f, Theme.MEDIUM);
                chip.setText(t.optString("name") + "  ×" + t.optInt("count", 0));
                chip.setPadding(Theme.dp(act, 10), Theme.dp(act, 5), Theme.dp(act, 10), Theme.dp(act, 5));
                chip.setBackground(Theme.panel(act, accent, 0.12f, 99));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Theme.dp(act, 8);
                row.addView(chip, lp);
            }
            hs.addView(row);
            body.addView(hs);
        }

        // 3. 最近工具操作与权限状态
        JSONArray recentTools = d.optJSONArray("recentTools");
        if (recentTools != null && recentTools.length() > 0) {
            body.addView(secHeader(act, "最近工具执行与授权记录"));
            int limit = Math.min(recentTools.length(), 8);
            for (int i = 0; i < limit; i++) {
                JSONObject rt = recentTools.optJSONObject(i);
                if (rt == null) continue;
                body.addView(toolRecordRow(act, rt, accent));
            }
        }

        // 4. 当前生效的 Allow / Deny 授权规则
        JSONObject rules = d.optJSONObject("rules");
        if (rules != null) {
            JSONArray allow = rules.optJSONArray("allow");
            JSONArray deny = rules.optJSONArray("deny");
            int allowCnt = allow == null ? 0 : allow.length();
            int denyCnt = deny == null ? 0 : deny.length();
            body.addView(secHeader(act, "生效的持久授权规则 · 允许 " + allowCnt + " 条 / 拒绝 " + denyCnt + " 条"));
            if (allowCnt == 0 && denyCnt == 0) {
                TextView empty = Theme.text(act, Theme.FAINT, 11.5f, null);
                empty.setText("未配置持久规则（依赖会话授权模式 " + modeLabel(pMode) + "）");
                body.addView(empty);
            } else {
                LinearLayout ruleBox = new LinearLayout(act);
                ruleBox.setOrientation(LinearLayout.VERTICAL);
                ruleBox.setPadding(Theme.dp(act, 12), Theme.dp(act, 8), Theme.dp(act, 12), Theme.dp(act, 8));
                ruleBox.setBackground(Theme.panel(act, Theme.BRAND_B, 0.06f, 12));
                int maxShow = Math.min(allowCnt, 12);
                for (int i = 0; i < maxShow; i++) {
                    TextView rv = Theme.text(act, Theme.MUTED, 11, null);
                    rv.setSingleLine(true);
                    rv.setEllipsize(TextUtils.TruncateAt.END);
                    rv.setPadding(0, Theme.dp(act, 2), 0, Theme.dp(act, 2));
                    rv.setText("✓  " + allow.optString(i));
                    ruleBox.addView(rv);
                }
                if (allowCnt > maxShow) {
                    TextView more = Theme.text(act, Theme.FAINT, 10.5f, null);
                    more.setPadding(0, Theme.dp(act, 4), 0, 0);
                    more.setText("… 其余 " + (allowCnt - maxShow) + " 条规则已省略");
                    ruleBox.addView(more);
                }
                body.addView(ruleBox);
            }
        }
    }

    private static TextView secHeader(Context c, String title) {
        TextView t = Theme.text(c, Theme.MUTED, 10.5f, Theme.BOLD);
        t.setText(title);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Theme.dp(c, 12), 0, Theme.dp(c, 6));
        return t;
    }

    private static View toolRecordRow(Context c, JSONObject rt, int accent) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Theme.dp(c, 10), Theme.dp(c, 6), Theme.dp(c, 10), Theme.dp(c, 6));
        String status = rt.optString("status", "ok");
        boolean denied = "denied".equals(status);
        boolean err = "error".equals(status);
        int sc = denied ? Theme.RED : (err ? 0xFFF59E0B : Theme.GREEN);
        row.setBackground(Theme.panel(c, sc, denied ? 0.14f : 0.04f, 10));

        TextView badge = Theme.text(c, sc, 10, Theme.BOLD);
        badge.setText(denied ? "已拒绝" : (err ? "失败" : "已放行"));
        badge.setPadding(Theme.dp(c, 6), Theme.dp(c, 2), Theme.dp(c, 6), Theme.dp(c, 2));
        badge.setBackground(Theme.pill(c, sc, 0.15f));
        row.addView(badge);

        TextView name = Theme.text(c, Theme.TEXT, 11.5f, Theme.BOLD);
        name.setText("  " + rt.optString("name", "") + "  ");
        row.addView(name);

        TextView sum = Theme.text(c, Theme.MUTED, 11, null);
        sum.setSingleLine(true);
        sum.setEllipsize(TextUtils.TruncateAt.END);
        sum.setText(rt.optString("summary", ""));
        row.addView(sum, new LinearLayout.LayoutParams(0, -2, 1f));

        long at = rt.optLong("at", 0);
        if (at > 0) {
            TextView ago = Theme.text(c, Theme.FAINT, 10, null);
            ago.setText("  " + Theme.ago(at));
            row.addView(ago);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Theme.dp(c, 4);
        row.setLayoutParams(lp);
        return row;
    }
}
