# P1 需求台账 —— Boss 直聘平台移植

来源：README.md 路线图 P1 + 旧 get_jobs 工程实测行为（勘察报告）。干净重写，不复用旧代码。

## 功能需求

| # | 需求 | 优先级 | 验证标准 |
|---|------|--------|----------|
| R1 | patchright 驱动层：npm 安装 patchright-core@1.62.1，playwright-java 1.62.0 消费 | P0 | `./gradlew installPatchrightDriver` 产出 build/patchright-driver/package/cli.js；bootRun 带 playwright.cli.dir 启动 |
| R2 | 持久化浏览器上下文：本机 Chrome 通道、非 headless、反检测参数、单线程调度 | P0 | 启动后打开 Boss 列表页不崩；Playwright 调用全部收敛单线程 |
| R3 | 登录态：持久化 profile 复用 + SQLite cookie 表兜底 + 未登录扫码引导 | P0 | 已登录直接进列表；未登录出二维码，扫码后检测到登录 |
| R4 | 岗位列表：搜索 URL 构造（城市/筛选/关键词）+ 无限滚动加载 + 拦截 detail.json 接口取结构化数据 | P0 | 真实跑出岗位卡片（职位/公司/薪资/HR/JD） |
| R5 | 打分过滤：SCORE_RULES（threshold/硬排除/加减分），低于阈值不投递 | P1 | 单测覆盖打分与阈值行为 |
| R6 | 投递动作：详情页→立即沟通→弹窗→聊天框写话术→发送；结果判定与上限识别 | P0 | 真实投递一条，deliveries 表记录"已投递" |
| R7 | 投递记录：deliveries 表 + encrypt_id 去重 + 状态更新 | P0 | 同岗位不重复插库；状态可更新 |
| R8 | API：POST /api/boss/start（门禁拦截）、GET /api/boss/status、GET/PUT /api/boss/config | P0 | 未激活 402；配置可读写 |
| R9 | 管理页最小版：配置（关键词/城市/话术/间隔）+ 开始/停止 + 实时进度 | P1 | 浏览器可用 |
| R10 | 频率控制：岗位间随机间隔（可配，默认 10s） | P1 | 日志可见间隔 |

## 非需求（本期不做）

- AI 打招呼话术（P4，留 GreetingProvider 接缝，先用固定 sayHi）
- 图片简历发送（留 seam）
- 其他平台（P2）
- 企业微信推送
- 自动黑名单挖掘

## 关键坑（来自旧工程注释，重写时必须遵守）

1. Boss 列表页在受控标签页会长时间转圈：所有 evaluate/locator 必须显式超时；落地页用岗位列表页不用首页
2. 不能等 LOAD/NETWORKIDLE（SPA+WebSocket），用 DOMCONTENTLOADED + 45s
3. 持久化上下文自带的启动空白页必须关掉，不能复用
4. Playwright Java 非线程安全：单线程调度，后台登录监控与投递互斥
5. 列表首卡片点击不触发 detail.json：先点第二个再切回第一个
6. 滚动加载靠"卡片数稳定+footer"双信号，容忍 frame 失效异常
7. 风控：间隔太短会全量弹验证页（旧工程实测 7 分钟 297 个详情触发）

## 环境事实

- macOS arm64，Chrome 已装（setChannel("chrome") 可用）
- JDK 21（Corretto，在父目录 jdk21/），系统默认 java 11 不够
- playwright 1.62.0 ↔ patchright-core 1.62.1（版本对齐，勿降 1.51：locator.count() 是坏的）
