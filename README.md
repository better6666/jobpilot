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
#    激活后穿过门禁，四个平台的投递引擎都在（P1/P2 已移植）
curl -X POST http://localhost:9527/api/boss/start

# 4. 解绑：释放设备占用（服务端终身 3 次、每次冷却 7 天）
curl -X POST http://localhost:9527/api/license/unbind
```

想让本机后端连线上 Worker（而不是本地 8787），启动时加：

```bash
./gradlew bootRun --args='--license.api-base=https://jobpilot.better999.dpdns.org'
```

这条链路已实测：CF 发卡 → 本机后端激活 → 门禁放行 → 托管在 CF 的激活页跨域读到本机状态。

**装机版的卡密服务端地址是烧进 `.app` 里的**（`Contents/app/JobPilot.cfg` 的 `java-options=-Dlicense.api-base=https://jobpilot.better999.dpdns.org`，由 jpackage 的 `--java-options` 写入）。双击启动没有传参的地方，api-base 留空就连不上服务端，用户看到的永远是"未激活"——所以出厂必须带生产地址。系统属性优先级**低于**命令行参数，本地联调照样能覆盖：

```bash
"/Applications/JobPilot.app/Contents/MacOS/JobPilot" --license.api-base=http://127.0.0.1:8787
```

验证装机版连的是哪一端，用一张**只存在于本地库**的卡去激活：连本地会拿到 `DEVICE_LIMIT`（卡在本地、已绑过），连生产会拿到 `CARD_NOT_FOUND`。两种错误码不同，一看就知道。

### 4. 测试

```bash
./gradlew test        # Java 侧 272 个单测：门禁过滤器 + 授权状态机 + 控制器入参校验
                      #   + 启动链路 + 四平台的 URL 构造 / 卡片解析 / 去重 / 打分
                      #   + AI 话术（URL 归一 / 输出清洗 / 去重重试 / 兜底 / 配置读写与打码）
npm test              # license-server 侧：卡密生成/时长等纯逻辑
npm run typecheck     # Worker 侧 tsc --noEmit
```

Java 测试全部用 Mockito 顶掉 ConfigService 与 LicenseClient，不连数据库、不发网络请求，
覆盖的关键路径：未激活 402 拦截、激活成功落库、服务端拒绝（REVOKED/EXPIRED 映射）、
服务端不可达的宽限判定（GRACE vs NETWORK_BLOCKED）、解绑、心跳边界、fail-open。

四平台的浏览器操作（Driver）不写单测——那需要真浏览器和真账号，由真机预演覆盖；
单测盯的是**能从 DOM/JSON 里提出正确字段的纯函数**：URL 构造（含薪资归一、页码钳制、
关键词编码）、卡片字段解析、`extractJobId`、去重与打分。每个平台都有一组用**实机抓到的
真实片段**当夹具的用例（如 51job 的 `sensorsdata`、智联的 `jobdetail` 链接），平台改版时
这些用例先红。

`JobPilotApplicationStartTest` 是唯一真起一遍 Spring 容器的用例（数据目录用系统属性指到
临时目录，不碰真库），盯的是"配置端口被占用时应用照样起来并换端口"——这个行为以前只在
生产环境暴露过，回归了也只能从用户那句"打不开"里发现。

## 四、部署卡密服务端到 Cloudflare

**已部署，两个入口都可用**：

| 地址 | 用途 |
|------|------|
| https://jobpilot.better999.dpdns.org/ | **主入口**（自定义域名，国内比 workers.dev 稳） |
| https://jobpilot-license.2333333434.workers.dev/ | 备用入口 |

根路径就是激活页，`/api/info` 是服务信息，`/admin/*` 是管理接口。

**卡密管理平台**（发卡 / 列表 / 作废 / 统计）在 `https://jobpilot.better999.dpdns.org/manage`，页面文件 `license-server/public/manage.html`。打开后第一件事是粘贴 ADMIN_KEY——key 只存在 `sessionStorage` 里（关标签页即失效，不落盘、不进仓库、不发请求到第三方），每次请求以 `Authorization: Bearer` 头带上，401 就提示重填。

管理平台能做的事：

| 面板 | 说明 |
|---|---|
| 运营统计 | 卡密总数 / 有效 / 已作废，按时长·次数·试用分型，24 小时内有心跳的设备数 |
| 发卡 | time / quota / trial 三种，1-100 张一批，可设时长或次数、最大设备数、批次号、备注。**卡密明文只在创建响应里完整出现一次**，之后列表一律打码，所以要当场复制或下载 |
| 卡密列表 | 按状态/批次筛选，展示打码卡号、剩余、已绑设备数、激活与到期时间；作废需输入完整卡号（列表只给打码值，这是故意的） |

⚠️ 管理页的 URL 故意不放在 `/admin/*` 命名空间下。带 `main` 的 Worker 上静态资源优先于 Worker 提供，`/admin.html` 会被 Assets 307 到 `/admin`，而 `/admin/*` 又被 Bearer 中间件拦成 401——页面根本打不开。所以叫 `manage.html`，访问 `/manage`。

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

### P1 单平台跑通（Boss 直聘）✅ 已完成（2026-09-20 实机验证通过）

- 移植 Playwright 驱动层：patchright driver 装配（`installPatchrightDriver` 任务 npm 装包 → 拷到 `build/patchright-driver/package` → `playwright.cli.dir` 指向它），单线程 dispatcher 保证 Playwright 调用不跨线程
- Boss 平台：登录态复用（读 `bst` cookie，免扫码）→ 搜索页无限滚动加载 → 逐个点卡片拦 `/wapi/zpgeek/job/detail.json` 拿结构化数据 → 详情页「立即沟通」→ 处理「已向BOSS发送消息」确认框 → 聊天页输入打招呼语 → 发送
- 投递记录落 SQLite：`deliveries` 表（同库），(platform, encrypt_id, encrypt_user_id) 唯一索引兜底去重；已投递才拦截，预演/失败可重跑
- 打分过滤：职位/学历黑名单一票否决 + 关键词分组加减分，阈值可配（规则从旧工程 SCORE_RULES 移植，JSON 存 config 表）
- 管理页最小可用版：`boss.html` 单页（license.html 风格）——状态徽章、计数器、配置表单、实时日志、投递记录表；启动/停止按钮；预演模式默认开

实机验证结论（macOS，真实 Chrome + 真实账号）：

- 未激活时 `POST /api/boss/start` 返回 402；本地卡密服务激活后放行
- 免扫码登录自动识别；搜索页 300 个岗位全部加载；卡片详情全部拦到
- 预演模式：5 个岗位全部记 `预演`，零真实发送
- 真实模式：2 个岗位成功发出打招呼语并记 `已投递`；去重正确跳过已处理岗位

实机验证逼出来的四个坑（改这段代码前先读，都已写进代码注释）：

1. **点「立即沟通」后的确认框**：框里真正的「继续沟通」是 `A.btn-startchat`，必须按**自身文本**精确匹配点。对话框文字会渗进页面大容器 div，用包含匹配（`:has-text`）会先点到容器，等于点遮罩——框关了但聊天页没打开
2. **已聊过的岗位按钮是「继续沟通」不是「立即沟通」**，按钮识别两种都要认
3. **列表第一个卡片默认选中态**，直接点不触发详情接口，必须先点第二个再切回——且这与 `maxJobsPerKeyword` 无关，只跑 1 个岗位时同样要热身，否则必然超时
4. **服务端任何拒绝都不能挡住启动**：缓存 token 失效、api-base 配错，一律降级成未激活态让用户换卡（历史上有两次这类崩溃）

### P2 其余平台 ✅ 已完成（猎聘 / 51job / 智联招聘，2026-09-21 真机验证通过）

三个平台都接到同一套共享内核上，没有复制四份编排：

- 抽象出 `com.jobpilot.delivery`：`DeliveryService<C extends JobCard>` 管「采集 → 打分 → 去重 → 投递 → 落库」全流程，`CardConsumer`/`DeliveryOutcome`/`ProgressListener` 是适配器要实现的全部接口；Boss 已重构上去，另三个是新建
- `RunCoordinator` 全局运行锁：四个平台共用同一个浏览器上下文，同一时刻只允许一个在跑，第二个启动直接返回"正在投递"
- 每平台独立 config（`config_key = 平台名`），`deliveries` 表按 platform 隔离，唯一索引 (platform, encrypt_id, encrypt_user_id)
- 管理页通用投递页 + 平台入口，四平台共用一套 UI
- 城市/薪资码表做成 classpath JSON（liepin 14 个、job51 26 个、zhilian 42 个），离线可用

真机验证结论（macOS，真实 Chrome + 真实账号，全部预演模式，零真实发送）：

| 平台 | 数据源 | 采集结果 |
|---|---|---|
| 猎聘 | 拦 `api-c.liepin.com/...pc-search-job` | 列表/分页/投递入口都对上 |
| 51job | 拦 `/api/job/search-pc` | 3 个岗位字段完整、记 `预演` |
| 智联 | 纯 DOM（点卡片读详情面板） | 5 个岗位字段完整、记 `预演` |

真机验证逼出来的坑（改这段代码前先读，都已写进代码注释）：

1. **51job 的 jobId 不在任何链接里**。新版列表的 `a[href]` 全是公司页（`/all/coXXXX.html`），数字 jobId 只存在于卡片 div 的 `sensorsdata` 埋点 JSON 里（HTML 实体编码），和搜索接口返回的是同一个值。老工程那套 `div.ss` 排序下拉热身对现网已无效，已删
2. **智联的关键词和薪资都必须走 query**。旧做法先导航 `/sou/jl639/p1` 再往输入框敲关键词，SPA 会跳到不含 `sl` 的地址，薪资过滤被悄悄丢掉（实测同一关键词有/无 `sl` 分别返回 20 和 11 条）。改成 `https://www.zhaopin.com/jobs?pageMode=search&jl=&kw=&sl=` 服务端渲染路由，两个条件都生效。注意智联的 `sl` 是**区间重叠**不是包含，`sl=12000,20000` 会放行 8000-15000
3. **智联的 jobId 只在详情面板里**，列表卡片扫遍 `data-*` 属性一个都没有，唯一锚点还是公司页。所以每张卡都必须点一次面板。而面板 DOM 是复用的，点下去旧内容还在，靠 `waitFor` 标题或固定 sleep 都会读到上一张甚至下一张的面板——jobId 错等于去重错、真实模式下就是投错职位。现在用「列表卡标题对上 + jobdetail 链接和上一张不同」双条件轮询确认，投递前再用 `jobUrl` 精确校验一次面板
4. **智联的面板字段容器和老台账写的不一样**：地区/经验/学历/招聘人数在 `ul.job-detail-summary__tags > li` 里，不在 `header-main` 的 span 里；学历**采得到**（`学历不限`/`大专`/`本科`），老台账"采不到"的结论作废。公司 meta 有两段和三段两种形态（融资阶段 · 规模 · 行业），规模按"含人"认、行业取末段
5. **51job 的 `jobAreaString` 地区串可能用间隔号分隔**（`苏州·苏州工业园区`），拆分时要和 `-` 一起按最先出现的分隔符切

### P3 前端与管理页 ✅ 已完成

- 继续单页 HTML（更利于打包分发）：`index.html` 是平台选择页，`delivery.html?platform=boss|liepin|job51|zhilian` 一个通用投递页按 query 参数切平台，四平台共用一套 UI
- 投递记录表格（每平台独立，`limit` 可调）、启动/停止、预演开关、实时日志
- 仍未做：记录表的筛选与统计图表

### P4 话术与 AI 润色 ✅ 已完成（2026-09-21 装机版实机验证通过）

按岗位 JD 生成个性化打招呼语，接**任意 OpenAI 兼容接口**——官方（`api.openai.com`）和中转站都行，地址由用户在管理页自己填，代码里不预置任何 key 或默认端点。（想开箱即用可以配「平台提供」，见 P6）

- `com.jobpilot.ai`：`AiConfig`（配置模型）/ `AiProperties`（存 config 表，key 只写不打码读）/ `AiService`（HTTP 客户端 + 地址归一 + 输出清洗 + 重试）/ `GreetingService`（拼提示词、去重、兜底）/ `AiController`（`/api/ai/*`）
- 地址归一：`https://api.openai.com` 自动补 `/v1`；中转站自定义前缀（`/v1`、`/api/v3` 之类）原样保留；粘了整个 `.../chat/completions` 端点只砍掉结尾那截；协议头大小写不敏感
- 输出清洗：去代码围栏、包裹引号、"话术："这类自带标签；多段输出只取第一段；空白压成空格；超长按最后一个标点截断
- 去重：和最近 30 条投递话术比对，重复就带着上一句要求模型"换个切入点"重来一次，还重复才退回固定话术
- 兜底链：AI 关着 / 三要素没配全 / 接口报错 / 输出为空 → 一律静默用固定话术，**不卡投递流程**
- 重试策略：4xx 不重试（429 除外），5xx 与连接失败退避 {600ms, 1800ms} 最多 3 次；请求体不带 `max_tokens`、关流式（部分中转站对这两个字段挑食）
- 管理页 `ai.html`：接口开关、地址、key（password 输入框 + "清除 Key"）、模型（可拉取 `/models` 列表点选）、人设、温度，"测试连接"直接看一句真实生成结果
- key 的存取语义：请求体不传 = 保留原值，空串 = 清除，其他 = 替换。管理页从不回显明文 key，输入框留空时没法区分"没改"和"清空"，所以由这条规则兜底

装机版实测结论：装机版激活卡密后门禁放行 → Boss 预演 50 个岗位零失败 → 2 条用了 AI 生成话术（清洗后无围栏残留），其余 48 条因假服务每次返回同一句话触发去重、按设计退回固定话术。

### P5 打包分发 ✅ 已完成

- `./gradlew bootJar` 产出可执行 jar
- 用 jlink 裁剪 JRE + jpackage 打双平台包（macOS dmg / Windows app-image），用户双击即用、不用装 JDK
- 首次启动向导：填 api-base → 激活 → 开始投递
- 端口被占用时自动顺延，启动后自动开一个像软件的窗口（联调踩过：9527 被旧进程占着就直接启动失败，且没有窗口、不打开浏览器，用户看到的就是"双击了没反应"，这是售后第一大坑）

**本地已验证**（macOS 侧全链路实跑通过）：jlink 裁剪运行时 52MB，jpackage 打出的 .app 共 134MB（driver-bundle 只留当前平台 node，瘦身约 170MB），启动后从 jar 内嵌资源解压 patchright driver 1.62.1 + node v24.19.0 到用户数据目录，真机启动系统 Chrome 成功；健康检查/建表/授权接口/根路径跳转全部正常。端口三种场景都从 dmg 装机后实跑过：9527 被 Java 进程占着时自动改到 9528 并打开浏览器、9527 被只绑 0.0.0.0 的进程占着时同样顺延、9527 空闲时原样用 9527。CI 用 `.github/workflows/build.yml` 在 macos-latest 与 windows-latest 上自动出包并做冒烟测试（含 driver/node 解压校验），tag 推送时自动附到 Release。

打包时固化的坑（改流程前先读）：

1. **jdeps 对 Spring Boot fat jar 会漏报模块**——它看不进 `BOOT-INF/lib` 里的嵌套 jar，只报 `java.base,java.net.http`，实际缺 `java.desktop`（java.beans）和 `java.instrument`（Tomcat），打出来的包启动即崩。模块清单只能靠实跑验证
2. **jpackage 输入目录里只能放 fat jar 一个文件**——plain jar 同时在目录里时，两条 classpath 在 cfg 里互相覆盖（properties 后 key 覆盖前 key），最终类路径只剩空壳
3. **Spring Boot fat jar 的主类是 `JarLauncher`**，不是业务类，指定 `--main-class` 会直接报 ClassNotFoundException（不指定时 jpackage 会读 manifest 自己找对）
4. **fat jar 不能用 `java.util.zip` 重写**——为了给 driver-bundle 瘦身试过重打包 fat jar，zip 结构明明完好（`unzip -t` 通过）但 Spring Boot loader 再也读不到 `BOOT-INF/lib`，表现为 `NoClassDefFoundError: org/slf4j/LoggerFactory`。裁剪必须发生在 bootJar 之前：现在是 `trimDriverBundle` 任务产出单平台版 jar，从 `runtimeClasspath` 里顶掉全量的那个
5. **playwright 认 node 只认 `<driverDir>/node`**——不看 PATH，patchright 的 npm 包里也没有 node，用户机器更不会装。所以 fat jar 里必须带 driver-bundle，首次启动把当前平台那份 node 解压到 driver 目录旁边（`PlaywrightDriverSupport.ensureNodeExecutable`）；解压不出来就宁可不认领这个 driver，否则启动浏览器时报 `Exec failed, error: 2`
6. **`cli.js` 是占位文件时 driver 起来就退**——报 `Failed to read message from driver, pipe closed`，没有任何有用信息。装配 driver 目录后要确认 `package/cli.js` 是真身（几百字节，`node package/cli.js` 有正常退出码）；曾经被单测写进一个 12 字节的假 cli.js 污染过开发态 driver 目录，测试因此改为走 `playwright.cli.dir` 指向临时目录，不碰真实 driver
7. **`SpringApplication.run(Class, String...)` 是静态方法**——`app.addListeners(...)` 之后写 `app.run(Xxx.class, args)` 编译不报错，但静态的那个会另起一个 `SpringApplication`，前面注册的监听器被整个丢掉。表现为"端口顺延没生效、启动后不打开浏览器、双击什么都不发生"，而且日志里毫无异常。要调实例方法 `app.run(args)`。这条由 `JobPilotApplicationStartTest` 兜底：它真起一遍应用并断言端口被占时照样起来
8. **端口顺延要在 Web 服务器绑定之前动手**——监听 `ApplicationEnvironmentPreparedEvent`，往 `Environment` 里 `addFirst` 一个 `server.port`；等 Tomcat 起来了再改就晚了。探测要"绑通配地址 + 连 127.0.0.1"两步：只绑通配地址的话，macOS 上 Java 的通配绑定会落到双栈 IPv6 socket，只绑 `0.0.0.0` 或只绑 `127.0.0.1` 的进程它一律探不到（两边都 listen、谁都连不上），所以补一个到 `127.0.0.1:<port>` 的连接探测。**但不能顺带探 `::1`**：开着代理/VPN 的 mac 上连一个肯定没人监听的 `::1` 端口也连得通（连接被中间层接走，接着 read 超时），每个端口都误报"被占用"，顺延逻辑整个失效——比漏探严重得多，而且日志里看不出来。`PortFallbackListenerTest` 对通配/回环两种占用者各有一个用例兜底

启动链路还有一处和端口相关：前端页面靠"是不是本机后端托管"决定 API 地址，判断条件不能带端口号（顺延后端口会变），且同源要优先于 `localStorage` 里存过的旧地址，否则会连到别的进程上。

界面是本地网页，没有原生窗口，所以启动后用 Chrome 的**应用模式**（`--app=`）打开：没有地址栏、没有标签页，有独立的 Dock 图标，看起来就是原生软件而不是"一个网站"。程序本来就要求本机装着 Chrome（`channel=chrome` 做反检测），不引入新依赖；它走用户自己的默认 profile，和自动化那边 `<数据目录>/browser-data` 的独立 profile 互不干扰。三处实测结论：`open` 必须带 `-n`（Chrome 已在运行时少了 `-n` 只把已有窗口翻到前面，不新开窗口）；`--window-size` 对应用窗口无效（传 900x600 照样开 1200x822）；`open -na` 找不到应用时退出码非 0，据此回落到默认浏览器。想退回老行为设 `jobpilot.app-window=false`，彻底不打开设 `jobpilot.open-page=false`。

9. **冒烟测试必须离开仓库根目录再启动打包产物**——driver 候选里有一项是"当前目录下的 `build/patchright-driver`"（开发态兜底），在仓库根目录启动会认领开发态 driver，node 就不会解压到用户数据目录，后面那句"driver 目录里没有解压出的 node"直接判失败。最终用户双击启动时的工作目录也不是仓库根目录，所以冒烟测试也 `cd` 到中性目录再拉起 `$APP`（路径随之改成绝对路径）

Windows 侧两个预期问题：app-image 未签名会触发 SmartScreen"Windows 已保护你的电脑"（要么买代码签名证书，要么给图文指引教用户点"更多信息→仍要运行"）；Java + 浏览器自动化程序容易被杀毒软件误报。

### P6 平台 AI 中转 ✅ 已完成

卡密卖出去之后，客户要自己去找中转站、注册、充值、拿 key 才能用 AI 话术——这一步能拦掉一大半人。所以现在**平台自己接一个中转站**：客户装上软件，在"AI 话术"页选「平台提供」，填个求职者背景就能用，别的什么都不用配。想用自己的 key 也随时能切回「我自己的接口」，两边配置互不覆盖。

安全上只有一条红线：**平台的 key 绝不下发到客户端**。任何持卡人都能把 key 从安装目录里挖出来刷额度，所以话术生成走的是**服务端代理**——客户端只把卡密 token、设备号、提示词发给 Cloudflare Worker，key 和模型名由 Worker 存在 D1 的 `settings` 表里，由 Worker 去请求中转站，只把生成的文字传回来。

- `license-server/src/lib/relay.ts`：读 settings 里的中转配置、地址归一（和 Java 侧 `AiService` 同一套规则）、请求中转站、按状态码映射错误
- `POST /api/ai/info`：客户端问"平台有没有开中转"，返回模型名或不可用原因；拿不到配置回 503
- `POST /api/ai/chat`：客户端发 `{token, device_id, user, temperature}`，服务端验卡（卡被作废回 403）+ 限流（每卡 120 次/分，超了回 429）后调中转；中转没配回 503，中转本身报错回 502，客户端拿到失败一律退回固定话术
- `GET/PUT /admin/settings` + `POST /admin/ai/test`：管理平台 `manage.html` 新增「平台 AI 中转配置」卡片，填地址/Key/模型，Key 只写不读（保存时留空 = 不动，点"清除 Key" = 删掉），能就地测一句真实生成
- 客户端 `AiController`：`mode` 字段（`platform`/`custom`），旧配置（`mode` 为 null）按"客户自己填过地址和 key"判成 custom；`platformInfo` 负责把"没配中转""连不上服务端"翻成客户看得懂的话，`ai.html` 按模式显隐接口字段并实时重算状态徽章

**本地已验证**（全链路实跑通过）：`wrangler dev --local` + `db:init` 建出 settings 表，`manage.html` 配上地址/Key/模型并测连通成功（Key 在列表里显示为打码形式），再起一个全新安装实例用真卡密激活：`/api/ai/config` 返回 `mode:"platform"`、`platformAvailable:true`、模型名正确，而地址/Key/模型三个字段全是空的——客户确实零配置。同一个实例 `/api/ai/test` 走平台代理拿到了中转返回的话术，页面"测试连接"显示"平台中转 / 模型名"。服务端各分支都单独打过：卡密缺失 400、卡密不对 401、卡被作废 403、没配中转 503。想自己复现这套联调，`license-server/README.md` 的"平台 AI 中转"一节写了怎么用一个假中转站在本地跑。

## 七、目录结构

```
jobpilot/
├── .github/workflows/build.yml  # CI：双平台打包 + 冒烟测试 + Release
├── build.gradle.kts              # 依赖与构建（含 patchright driver 装配任务）
├── src/main/java/com/jobpilot/
│   ├── JobPilotApplication.java  # 入口（建用户数据目录、按平台设绝对路径）
│   ├── common/                   # 统一响应体、跨域过滤器
│   ├── system/                   # 配置表、建表、健康检查、路径、端口顺延、页面打开
│   ├── browser/                  # Playwright 驱动层：单线程 dispatcher、持久上下文
│   ├── delivery/                 # 共享内核：JobCard/DeliveryService/去重/打分/运行锁
│   ├── boss/                     # Boss 平台适配器（Driver/Service/Card/URL/Options/API）
│   ├── liepin/                   # 猎聘平台适配器（同上五件套）
│   ├── job51/                    # 51job 平台适配器（同上五件套）
│   ├── zhilian/                  # 智联招聘平台适配器（同上五件套）
│   ├── ai/                       # AI 话术：配置模型 / OpenAI 兼容客户端 / 平台中转代理 / 话术生成与去重兜底
│   └── license/                  # 卡密：校验、门禁、控制器、激活页数据
├── src/test/java/com/jobpilot/
│   ├── license/                  # 门禁 / 状态机 / 客户端健壮性 / 控制器单测
│   ├── delivery/                 # 打分与平台选项接口单测
│   ├── boss/                     # 打分 / URL 构造 / 详情解析 / 去重落库单测
│   ├── liepin/                   # URL 构造 / 卡片解析 / 去重落库单测
│   ├── job51/                    # 同上 + sensorsdata 提 jobId 单测
│   ├── zhilian/                  # URL 构造 / 卡片解析 / 面板字段 / 去重落库单测
│   ├── ai/                       # 地址归一 / 输出清洗 / 去重重试 / 兜底 / 配置读写与打码
│   ├── browser/                  # driver 装配与 node 解包单测
│   └── system/                   # 数据目录路径 / 端口顺延 / 应用启动单测
├── src/main/resources/
│   ├── application.yaml
│   ├── boss-options.json         # 374 城市 + 学历/经验/薪资等筛选项码表
│   ├── liepin-options.json       # 猎聘城市/薪资码表（离线可用）
│   ├── job51-options.json        # 51job 城市/薪资码表（离线可用）
│   ├── zhilian-options.json      # 智联城市码表（离线可用）
│   └── static/
│       ├── license.html          # 用户激活页
│       ├── index.html            # 平台入口页（选平台进通用投递页）
│       ├── delivery.html         # 通用投递管理页（配置/启动/日志/记录，?platform= 区分）
│       ├── ai.html               # AI 话术配置页（开关/接口来源/地址/key/模型/人设/测试连接）
│       └── boss.html             # Boss 专用页（保留兼容旧入口）
└── license-server/               # CF Worker 卡密服务端 + 平台 AI 中转（独立部署）
    ├── src/index.ts              # API：activate/verify/report/unbind + admin + /api/ai/*
    ├── src/lib/cards.ts          # 卡密生成等纯逻辑
    ├── src/lib/relay.ts          # 平台 AI 中转：读 settings、地址归一、请求中转站
    ├── schema.sql                # D1 表结构（cards/devices/settings，IF NOT EXISTS 可重跑）
    ├── public/
    │   ├── index.html            # 激活页（Worker 静态资源，用户在 CF 域名上打开）
    │   └── manage.html           # 卡密管理平台（发卡/列表/作废/统计/AI 中转配置，key 只进 sessionStorage）
    └── test/                     # vitest 单测（cards + relay）
```
