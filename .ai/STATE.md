# 交接状态 —— 打包分发、双击启动与界面形态

> `.ai/REQUIREMENTS.md` 是 P1 Boss 移植的需求台账，本篇记的是 packaging / 分发 / 启动链路侧的状态，
> 两者互补。更新于 2026-09-21。

## 当前状态

macOS 侧已交付并装机验证：`/Applications/JobPilot.app`（134MB，从 `build/dist/JobPilot-1.0.0.dmg` 装入）。
Windows 侧只走 CI（`.github/workflows/build.yml`，tag 推送自动出包并附 Release），**未推送 tag，未发布**。

## 界面形态：本地网页 + Chrome 应用模式窗口

用户反馈"为什么是网站，我做的不是软件吗"——界面确实是内嵌 Tomcat 托管的本地网页，
没有原生窗口。已改为启动时用 Chrome 应用模式（`--app=`）打开：无地址栏、无标签页、
独立 Dock 图标，看起来是原生软件。`LocalPageOpener` 负责，`jobpilot.app-window=false`
可退回默认浏览器，`jobpilot.open-page=false` 彻底不打开。

三处实测结论（改之前先看）：
- macOS 的 `open` 必须带 `-n`：Chrome 已在运行时少了 `-n` 只把已有窗口翻到前面，不新开窗口
- `--window-size` 对应用窗口无效（传 900x600 照样开 1200x822），别指望它控尺寸
- 应用窗口走用户默认 Chrome profile；自动化那边是 `<数据目录>/browser-data` 的独立 profile，互不干扰

**已知未做**：关掉窗口后端不会退出（Java 进程还在后台），Dock 里是通用 Java 图标。
要补的话是页面 `pagehide` 时通知后端 `System.exit`。

## 本轮修的问题：用户反馈"打不开这个软件"

双击装机版毫无反应。两条原因叠在一起：

1. **真因**：`JobPilotApplication.start()` 里写的是 `app.run(JobPilotApplication.class, args)`。
   `SpringApplication.run(Class, String...)` 是**静态方法**，编译不报错，但它内部会另起一个
   全新的 `SpringApplication`，实例上 `addListeners` 注册的 `PortFallbackListener` 与
   `LocalPageOpener` 被整个丢掉。端口被占时直接启动失败（stdout 没地方去，报错只落在
   `~/Library/Application Support/JobPilot/logs/`），而浏览器又不会自动打开，用户看到的就是
   "双击了没反应"。改成实例方法 `app.run(args)` 即修复。
   **兜底测试**：`JobPilotApplicationStartTest` 真起一遍应用，断言端口被占时照样起来且换了端口。
2. **加重症状**：启动成功也没有任何可见反馈（没有窗口、不开浏览器），所以补了 `LocalPageOpener`。

## 端口探测的两处坑（都在 `PortFallbackListener`）

- **绑通配地址探不到非 Java 占用者**：macOS 上 Java 的通配绑定落到双栈 IPv6 socket，
  只绑 `0.0.0.0` 或只绑 `127.0.0.1` 的进程它一律探不到——两边都 listen、谁都连不上。
  已补一个到 `127.0.0.1:<port>` 的连接探测（`isListening`）。
- **不能顺带探 `::1`**：开着代理/VPN 的 mac 上，连一个肯定没人监听的 `::1` 端口也连得通
  （连接被中间层接走，接着 read 超时），于是每个端口都误报"被占用"，顺延逻辑整个失效，
  日志里还看不出来。第一版就是这么写的，`PortFallbackListenerTest` 三个用例同时挂掉才发现。
  只探 `127.0.0.1`：启动后打开的本机页面走的就是这个地址，够用。

装机后实跑过四种端口场景，全部符合预期：9527 空闲用 9527；9527 被 Java 通配占用、
被只绑 `0.0.0.0` 的进程占用、被只绑 `127.0.0.1` 的进程占用，三种都自动改到 9528
并打开浏览器，health 200、根路径 302。

## 顺带修的 CI 冒烟测试

冒烟测试原来在仓库根目录拉起 `$APP`，而 driver 候选里有一项是"当前目录下的
`build/patchright-driver`"（开发态兜底），于是认领的是开发态 driver，node 不会解压到
用户数据目录，`[ -x "$NODE" ]` 那一步必然失败。已改为 `cd` 到 `$RUNNER_TEMP` 再启动，
路径同步改成绝对路径。

## 测试

`./gradlew test`：84 个单测，0 失败。端口相关 7 个：`PortFallbackListenerTest`（5）+
`JobPilotApplicationStartTest`（2）。启动链路（`LocalPageOpener`）由装机后的真机验证覆盖，
CI 冒烟测试用 `--jobpilot.open-page=false` 关掉自动打开，不在 CI 里开浏览器。

## 待办 / 注意

- Windows 打包仍只在 CI 验证过，未签名会触发 SmartScreen
- 需要发布时才推 tag（`v*`），推了就会自动附到 Release——**未经明确要求不要推**
- `license-server/.dev.vars` 的 ADMIN_KEY 不进任何仓库文件
