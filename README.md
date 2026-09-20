# JobPilot

简历批量投递工具（重写版）。与旧版 get_jobs 的关系：旧仓库采用 PolyForm Noncommercial 1.0.0 许可，**不允许商业使用**；本项目为卖卡密商业化而做的全新重写，不复用旧代码。

**许可**：专有软件，保留所有权利。未经授权不得复制、修改或再分发。

**仓库**：https://github.com/better6666/jobpilot
**线上卡密服务**：https://jobpilot.better999.dpdns.org/

## 一、整体架构

```
┌─────────────────────┐         ┌──────────────────────────┐
│  用户电脑（本机）     │  HTTPS  │  Cloudflare              │
│                     │ ◄─────► │                          │
│  JobPilot 后端       │  卡密    │  Worker (jobpilot-license)│
│  Spring Boot :9527  │  校验    │    ├─ D1   卡密/激活记录   │
│    ├─ 卡密校验门禁    │         │    └─ KV   限流计数        │
│    ├─ 投递引擎(移植中)│         └──────────────────────────┘
│    └─ 管理页(移植中)  │
└─────────────────────┘
```

设计要点：

- **校验在云端，投递在本机**。Playwright 驱动浏览器必须在用户机器上跑（要用用户的登录态），搬不上 CF；但卡密校验逻辑轻，放 Worker + D1 零成本起步。
- **客户端不做安全假设**。卡密校验只是"提高白嫖门槛"，不是 DRM。真防白嫖靠的是：卡密一机一绑、服务端可随时禁用、心跳可吊销 token。
- **fail-open 可配**。默认 `fail-open: false`（服务端不可达超宽限期就停）；自用时可开 `license.enabled: false` 完全绕过。

## 二、卡密模式设计

| 卡种 | 说明 | 状态 |
|------|------|------|
| 月卡 / 季卡 / 年卡 | 时间卡，首次激活起算，主力售卖形态 | ✅ 已实现 |
| 24h 体验卡 | 同一设备指纹只能领一次（服务端去重） | ✅ 已实现 |
| 按次卡（配额卡） | 每成功投递一次扣一次，用完即止 | ✅ 已实现 |
| 永久卡 | **不做**。永久授权是无限责任，出纠纷说不清 | 永不 |

绑定与换机策略：

- 一卡同时最多绑 `max_devices` 台（默认 1，批量/团队卡可调）
- 每卡终身 3 次解绑，每次解绑后 7 天才允许再绑（防一张卡到处借）
- 解绑只重置"设备占用"，**不重置时长**（月卡不会因为换机而续期）

## 三、本地开发

前置：JDK 21（系统默认 java 11 不够，`JAVA_HOME` 指到 21 再跑 gradlew）、Node 22+。

### 1. 卡密服务端（license-server）

```bash
cd license-server
npm install
npm run dev            # wrangler dev --local，监听 :8787
npm test               # 卡密生成/时长等纯逻辑单测
npm run typecheck      # tsc --noEmit
```

本地管理密钥放在 `license-server/.dev.vars` 里（一行 `ADMIN_KEY=...`，已被 gitignore，不会提交）。本地和线上 Worker 用同一把，联调方便；命令行里这样读：

```bash
AK="Authorization: Bearer $(grep '^ADMIN_KEY' license-server/.dev.vars | cut -d= -f2)"
```

### 2. JobPilot 后端

```bash
cd ..
JAVA_HOME=<你的JDK21路径> ./gradlew bootRun \
  --args='--license.api-base=http://localhost:8787'
```

启动后：

| 地址 | 用途 |
|------|------|
| http://localhost:9527/api/health | 健康检查 |
| http://localhost:9527/api/license/status | 当前卡密状态 |
| http://localhost:9527/license.html | 用户激活页（输卡密 / 解绑） |

自用免激活模式：`--license.enabled=false`，或 application.yaml 里改。

**数据目录**：数据库和日志一律落在用户目录（macOS `~/Library/Application Support/JobPilot`，Windows `%APPDATA%\JobPilot`，Linux `~/.local/share/JobPilot`），**不相对进程工作目录**——打包成 .app / exe 后用户双击启动时工作目录是 `/` 或 System32，相对路径会直接崩（实测 `/db: Read-only file system`）。路径由 `SystemPaths` 统一算，`main()` 在启动前把绝对路径写进系统属性覆盖 yaml 里的兜底默认值；命令行参数优先级仍最高，开发时可用 `--spring.datasource.url=...` 覆盖。

### 3. 端到端流程（本地实测过）

```bash
# 管理密钥从 .dev.vars 读（本地 dev 和线上 Worker 是同一把）
AK="Authorization: Bearer $(grep '^ADMIN_KEY' license-server/.dev.vars | cut -d= -f2)"

# 1. 发卡（本地 Worker 把地址换成线上 https://jobpilot-license.2333333434.workers.dev 即可）
curl -X POST http://localhost:8787/admin/cards \
  -H "$AK" -H 'Content-Type: application/json' \
  -d '{"count":1,"type":"time","duration_days":30,"batch":"test"}'

# 2. 激活（返回 ACTIVE）
curl -X POST http://localhost:9527/api/license/activate \
  -H 'Content-Type: application/json' \
  -d '{"cardKey":"<上一步返回的卡号>"}'

# 3. 门禁行为：任意平台的 /api/<平台>/start 投递入口，
#    未激活时返回 402（连端点没实现都会被拦住，不会漏成 404）；
#    激活后穿过门禁——当前投递引擎还没移植（P1），所以是 404，属预期
curl -X POST http://localhost:9527/api/boss/start

# 4. 解绑：释放设备占用（服务端终身 3 次、每次冷却 7 天）
curl -X POST http://localhost:9527/api/license/unbind
```

想让本机后端连线上 Worker（而不是本地 8787），启动时加：

```bash
./gradlew bootRun --args='--license.api-base=https://jobpilot.better999.dpdns.org'
```

这条链路已实测：CF 发卡 → 本机后端激活 → 门禁放行 → 托管在 CF 的激活页跨域读到本机状态。

### 4. 测试

```bash
./gradlew test        # Java 侧 30 个单测：门禁过滤器 + 授权状态机 + 控制器入参校验
npm test              # license-server 侧：卡密生成/时长等纯逻辑
npm run typecheck     # Worker 侧 tsc --noEmit
```

Java 测试全部用 Mockito 顶掉 ConfigService 与 LicenseClient，不连数据库、不发网络请求，
覆盖的关键路径：未激活 402 拦截、激活成功落库、服务端拒绝（REVOKED/EXPIRED 映射）、
服务端不可达的宽限判定（GRACE vs NETWORK_BLOCKED）、解绑、心跳边界、fail-open。

## 四、部署卡密服务端到 Cloudflare

**已部署，两个入口都可用**：

| 地址 | 用途 |
|------|------|
| https://jobpilot.better999.dpdns.org/ | **主入口**（自定义域名，国内比 workers.dev 稳） |
| https://jobpilot-license.2333333434.workers.dev/ | 备用入口 |

根路径就是激活页，`/api/info` 是服务信息，`/admin/*` 是管理接口。

对应资源：

| 资源 | 名称 / ID |
|------|-----------|
| Worker | jobpilot-license |
| D1 数据库 | jobpilot-license（`65f9e271-43f6-4032-88ff-1d45d563be45`） |
| KV 命名空间 | jobpilot-license-ratelimit（`8560e1266b05425abba499947e150775`） |
| 自定义域名 | jobpilot.better999.dpdns.org（挂在 better999.dpdns.org zone 下） |
| ADMIN_KEY | CF Secret，值在 `license-server/.dev.vars`（已 gitignore） |

从零复现的步骤：

```bash
cd license-server
npm install
npx wrangler d1 create jobpilot-license        # database_id 回填 wrangler.toml
npx wrangler kv namespace create jobpilot-license-ratelimit   # id 回填 wrangler.toml
npx wrangler d1 execute jobpilot-license --file=schema.sql --remote
npx wrangler secret put ADMIN_KEY              # 管理密钥，别写进代码
npx wrangler deploy
```

注意两点：

1. **激活页是 Worker 的静态资源**（`assets = { directory = "./public" }`，文件在 `license-server/public/index.html`）。它是 `src/main/resources/static/license.html` 的副本——改页面要改源文件再复制过去，别只改一份。
2. **托管页面默认连 `http://localhost:9527`**（本机后端）。用户在自己电脑上打开这个网址就能激活，浏览器跨域由后端的 `CorsFilter` 放行；地址不对时页面底部"连不上？"里可手改。

⚠️ **workers.dev 域名在国内连通性差**，所以额外绑了自定义域名 `jobpilot.better999.dpdns.org`（wranger.toml 里的 `routes` + `custom_domain = true`，部署时 CF 会自动建好代理 DNS 记录）。客户端 `license.api-base` 用自定义域名这个。注意 TOML 里 `routes` 必须写在 `[[d1_databases]]` / `[[kv_namespaces]]` 这些表格**之前**，否则会被当成上个表格的字段而静默失效（这个坑踩过一遍）。

## 五、安全红线（提交前自查）

以下内容**永不进公开仓库**：

- `.env` / `.dev.vars`（API_KEY、ADMIN_KEY）
- `*.db`、`db/`、`cookies/`、`browser-data/`（含用户登录态）
- 简历原件、个人信息（姓名、学校、电话等）
- 卡密明文、后台地址、admin key

.gitignore 已覆盖前四项；个人信息靠"重写时不带过来"保证——旧仓库里的个人配置一律不带入本工程。

## 六、重写路线图

### P0 工程骨架 + 卡密体系 ✅ 已完成

- Gradle + Spring Boot 3.5 + SQLite 骨架
- CF Worker 卡密服务（D1 存卡密、KV 限流、admin 发卡/禁用/统计）
- 客户端校验（启动激活 → 10 分钟心跳 → 72h 宽限 → 状态机）
- 投递入口门禁（未激活 402）
- 激活页 license.html

### P1 单平台跑通（Boss 直聘）⬅️ 当前阶段

- 移植 Playwright 驱动层（patchright driver 装配，见旧工程 build.gradle.kts 的做法）
- Boss 平台：登录态复用 → 岗位列表 → JD 抓取 → 投递动作
- 投递记录落 SQLite（复用 config 表同库，新建 deliveries 表）
- 管理页最小可用版（先 license.html 风格的单页，不上 Next.js）

### P2 其余平台

- 猎聘 / 51job / 智联，逐个按 P1 的模式接入
- 平台 DOM 变化时只有对应 adapter 要改，门禁/记录/UI 不动

### P3 前端与管理页

- 决定是继续单页 HTML 还是上 Next.js（单页更利于打包分发，倾向继续单页）
- 投递记录表格、筛选、统计

### P4 话术与 AI 润色

- 打招呼话术模板池 + 随机/轮换（解决"话术重复很呆"的反馈）
- AI 中转站接入：按 JD 生成个性化开场，失败降级到模板

### P5 打包分发

- `./gradlew bootJar` 产出可执行 jar
- 用 jlink 裁剪 JRE + jpackage 打双平台包（macOS dmg / Windows app-image），用户双击即用、不用装 JDK
- 首次启动向导：填 api-base → 激活 → 开始投递
- 端口被占用时自动顺延（联调踩过：9527 被旧进程占着就直接启动失败，这是售后第一大坑）

**本地已验证**（macOS 侧全链路实跑通过）：jlink 裁剪运行时 52MB，jpackage 打出的 .app 共 90MB，1.3 秒启动，健康检查/建表/授权接口全部正常。CI 用 `.github/workflows/build.yml` 在 macos-latest 与 windows-latest 上自动出包并做冒烟测试，tag 推送时自动附到 Release。

打包时固化的三个坑（改流程前先读）：

1. **jdeps 对 Spring Boot fat jar 会漏报模块**——它看不进 `BOOT-INF/lib` 里的嵌套 jar，只报 `java.base,java.net.http`，实际缺 `java.desktop`（java.beans）和 `java.instrument`（Tomcat），打出来的包启动即崩。模块清单只能靠实跑验证
2. **jpackage 输入目录里只能放 fat jar 一个文件**——plain jar 同时在目录里时，两条 classpath 在 cfg 里互相覆盖（properties 后 key 覆盖前 key），最终类路径只剩空壳
3. **Spring Boot fat jar 的主类是 `JarLauncher`**，不是业务类，指定 `--main-class` 会直接报 ClassNotFoundException

Windows 侧两个预期问题：app-image 未签名会触发 SmartScreen"Windows 已保护你的电脑"（要么买代码签名证书，要么给图文指引教用户点"更多信息→仍要运行"）；Java + 浏览器自动化程序容易被杀毒软件误报。

## 七、目录结构

```
jobpilot/
├── .github/workflows/build.yml  # CI：双平台打包 + 冒烟测试 + Release
├── build.gradle.kts              # 依赖与构建
├── src/main/java/com/jobpilot/
│   ├── JobPilotApplication.java  # 入口（建用户数据目录、按平台设绝对路径）
│   ├── common/                   # 统一响应体、跨域过滤器
│   ├── system/                   # 配置表、建表、健康检查、路径
│   └── license/                  # 卡密：校验、门禁、控制器、激活页数据
├── src/test/java/com/jobpilot/
│   ├── license/                  # 门禁 / 状态机 / 控制器单测
│   └── system/                   # 数据目录路径单测
├── src/main/resources/
│   ├── application.yaml
│   └── static/license.html       # 用户激活页
└── license-server/               # CF Worker 卡密服务端（独立部署）
    ├── src/index.ts              # API：activate/verify/report/unbind + admin
    ├── src/lib/cards.ts          # 卡密生成等纯逻辑
    ├── schema.sql                # D1 表结构
    └── test/cards.test.ts        # 单测
```
