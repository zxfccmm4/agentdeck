# AgentDeck — Agent 用量桌面屏

把一个横向摆放的安卓旧手机 / 平板当桌面展示屏，实时展示本机（或远端）正在运行的
coding agent（Claude Code、Codex、pi、opencode、omo、dsh）及其用量。

- 顶部：服务端、在线主机数、总 token、今日 token、花费、时钟
- 中部：每个 agent 的卡片（状态点 · 名称 · 总 token · 会话数 · 今日用量）
- 右上：环形图 = 各 agent token 占比
- 底部：最近使用的 agent 会话
- **点卡片/最近项 → 详情页**：近 72 小时 + 近 30 天 token 走势图、会话列表
- **开机自启动**：设备启动/解锁后自动拉起（部分 OEM 需允许自启动）

## 架构

```
Mac / 远端机器                    横向安卓手机
+-------------------------+      +--------------------------+
| ~/agentdeck/server      |      | AgentDeck App            |
|  node server.js :7777   |      | 轮询 127.0.0.1:7777      |
|  采集各 agent 会话.jsonl |<-----| adb reverse tcp:7777     |
|  /api/state  /api/stream|      +--------------------------+
+-------------------------+
```

- **服务端**（`server/`）：纯 Node，无依赖。扫描本机
  `~/.claude/projects`、`~/.codex`、`~/.pi/agent/sessions`、
  `~/.local/share/opencode/opencode.db`、`~/.omo/tasks` 的会话与用量文件，
  聚合出状态快照。还支持聚合多台远端主机（见 `server/config.json`）。
- **安卓端**（`android/`）：单 Activity + 前台保活服务，纯代码构建布局，
  无第三方依赖。通过 `adb reverse` 走 USB 访问服务端，最稳。

## 快速开始

```bash
# 1. 本机服务端
cd ~/agentdeck/server
node server.js            # 监听 0.0.0.0:7777，浏览器可开 http://localhost:7777 预览

# 2. USB 连接手机 + adb reverse
adb reverse tcp:7777 tcp:7777

# 3. 构建、安装、启动 App
cd ~/agentdeck/android
./build.sh build/agentdeck.apk
adb install -r build/agentdeck.apk
adb shell am start -n com.agentdeck.desk/.MainActivity
```

或者直接用打包好的一键脚本：

```bash
~/agentdeck/start.sh       # 启动服务端 + adb reverse + 打开 App
```

## 服务端接口

| 接口           | 说明                                   |
| -------------- | -------------------------------------- |
| `/api/state`   | 完整状态快照（JSON），客户端轮询用     |
| `/api/stream`         | SSE，每秒推送一次最新状态（浏览器预览用）|
| `/api/health`         | 健康检查                                    |
| `/api/agents/:id`     | 单 agent 详情（会话列表 + 72h/30d 走势数据） |
| `/`                   | 简易 HTML 预览                              |

## 采集的数据源

| agent     | 位置                                                        |
| --------- | ----------------------------------------------------------- |
| Claude    | `~/.claude/projects/**/*.jsonl`（含 usage 与 cost-state）   |
| pi        | `~/.pi/agent/sessions/**/*.jsonl`（含 usage）               |
| Codex     | `~/.codex/sessions/**/rollout-*.jsonl` + sqlite 索引         |
| opencode  | `~/.local/share/opencode/opencode.db`（session 表带用量）   |
| omo       | `~/.omo/tasks/*.json` + 复用 opencode 存储                  |
| dsh       | Electron 桌面端，无稳定用量导出；可置 `DSH_USAGE_FILE` 导入  |

## 连接方式选择

- **USB（推荐）**：`adb reverse tcp:7777 tcp:7777`，App 自动连
  `http://127.0.0.1:7777`。
- **WiFi**：手机和 Mac 同一局域网，App 会自动扫描 7000/7777/8080/3000
  端口，也可在 `startServerDiscovery()` 里预设地址。
- **远端主机**：在 `server/config.json` 里配 `remotes: [{name,url}]`，
  服务端聚合多台机器的数据。

## 字段说明

`/api/state` 的 `agents[]` 每一项：

```
{
  id, name, color, status("active|idle|empty|missing|offline"),
  sessions, activeSessions, procCount, turns,
  tokens{input,output,cacheRead,cacheWrite,reasoning,total},
  costUsd, todayTokens, weekTokens, lastAt, share,
  series[12],        // 近 12 小时逐小时 token
  top[],             // 最近的会话
  share              // token 占比 %
}
```

## 构建

需要：macOS + Homebrew `openjdk@21` + Android SDK
（`sdkmanager "platforms;android-34" "build-tools;34.0.0"`）。

```bash
cd ~/agentdeck/android && ./build.sh build/agentdeck.apk
```

Build 脚本会用 Google Maven 的 R8（build-tools 自带的 d8 对 JDK21 字节码有 bug）。