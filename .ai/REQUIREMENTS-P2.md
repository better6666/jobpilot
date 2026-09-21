# P2 需求台账 —— 猎聘 / 51job / 智联招聘 三平台移植

> `.ai/REQUIREMENTS.md` 是 P1 Boss 的台账，`.ai/STATE.md` 是打包分发侧的状态。
> 本篇只记 P2。建立于 2026-09-21。

## 1. 需求来源

用户原话："三个都要啊"。P1 只交付了 Boss 直聘一个平台，管理页也只能投 Boss。
老工程 get_jobs 支持四平台，但它是 PolyForm Noncommercial 1.0.0 许可——
拿它卖卡密就是商用违规，这也是当初做干净重写的原因。所以 P2 的三个适配器
**必须从零写，不能抄老工程代码**；老工程只作为"平台行为事实"的来源读。

## 2. 验收标准

| # | 需求 | 验收方式 |
|---|---|---|
| R1 | 猎聘可登录、可采集、可投递 | 真机跑通：扫码/复用登录态 → 出列表 → 投递 N 个 → deliveries 有记录 |
| R2 | 51job 同上 | 同上 |
| R3 | 智联同上 | 同上 |
| R4 | 三平台与 Boss 共用一套编排，不是四份复制 | `DeliveryService` 基类 + 4 个子类；Boss 已重构上去 |
| R5 | 同一时刻只允许一个平台在跑 | `RunCoordinator`；Boss 跑批中启动智联返回"正在投递" |
| R6 | 管理页能切平台、能分别配置 | 通用投递页 + 每平台独立 config（config_key = 平台名） |
| R7 |  deliveries 表按 platform 隔离，互不干扰 | 唯一索引 (platform, encrypt_id, encrypt_user_id)；clearDeliveries 只清本平台 |
| R8 | 每个平台都有码表（城市等），离线可用 | classpath JSON：liepin/job51/zhilian-options.json |
| R9 | 不引入老工程的任何密钥/配置 | 老 .env 里的 BASE_URL/API_KEY/MODEL 一律不进 jobpilot |
| R10 | 每个平台都有单测覆盖 URL 与解析 | 新增测试，全量 `./gradlew test` 通过 |

## 3. 平台行为事实（来自老工程实测，作为需求而非设计）

### 3.1 猎聘 liepin
- 登录态：老工程纯 DOM 判定（`#header-quick-menu-user-info` 存在即已登录）；
  实测 cookie 里 `lt_auth` / `user_name` 是登录令牌，本工程改用 **cookie 第一判据 +
  DOM 第二判据**（老工程的"找不到登录入口就判已登录"会在白屏时误判）
- 搜索 URL：`https://www.liepin.com/zhaopin/?city={码}&dq={码}&salary={码}&currentPage=0&key={词}`
  ——`city` 与 `dq` **必须同值双写**，只写 city 大区筛选不生效
- 列表：服务端分页（AntD），总页数取 `.list-pagination-box` 下**倒数第二个** li；
  翻页点 `li.ant-pagination-next` 里的 `button.ant-pagination-item-link`，
  禁用判据是 li 的 class 含 `ant-pagination-disabled`
- 数据源：拦截 `https://api-c.liepin.com/api/com.liepin.searchfront4c.pc-search-job`，
  **必须排除同前缀的 `pc-search-job-cond-init`**；卡片在 `data.data.jobCardList`，
  分 job / comp / recruiter 三个子对象
- 索引对齐铁律：接口返回的实体列表**按下标与 DOM 卡片一一对应**（第 i 张卡用第 i 个实体）
- 投递：卡片内 hover `.recruiter-info-box` 才渲染沟通按钮；按钮判据是**自身文本含
  "聊一聊"**；"继续聊"= 已投递，要跳过；点完聊天 overlay 出现
  `.__im_basic__header-wrap` 即成功，**没有确认对话框、不开新标签页**
- 弹窗：每张卡处理前 + 翻页前都要关 `.ant-modal-wrap`（完善简历弹窗）和订阅弹窗
- 城市码表 14 个（猎聘内部码，北京=010），salary 无码表原样透传

### 3.2 51job
- 登录态：老工程纯 DOM（`span.login.loginBtnClick` 含"登录"= 未登录；
  `a.uname.e_icon.at` 或 `a[href*='/pc/my/myjob']` = 已登录）。
  登录主 token cookie 名是 `51job`，本工程用它做第一判据
- 搜索 URL：`https://we.51job.com/pc/search?jobArea={码}&salary={码}&keyword={词}`，
  多值用英文逗号连接；关键词必须 URL 编码（老工程裸拼中文）
- 列表：分页制，最多 50 页；翻页三级策略（next 按钮 → 页码数字 → 跳页输入框）
- **热身动作**：~~导航后必须点一次 `div.ss`（排序下拉），否则搜索接口可能不触发~~
  → **实机作废**：现网 `div.ss` 匹配不到任何节点，搜索接口导航后自己就会触发。
  常量和 `warmUpSortDropdown` 方法已删
- 数据源：`page.onResponse` 常驻监听 `/api/job/search-pc`，**按 URL query 的
  requestId 去重**；列表路径按序探测 `data.items` → `data.jobList` → …；
  每个字段都有 3-4 个候选名
- 投递：按钮就在列表页卡片上（`.j_joblist` 容器内 `button:has-text('投递')`，
  **必须 `:not(:has-text('一键投递'))` 且必须限定容器**，否则命中顶导航）；
  已投递只在按钮 innerText 里（含"已投递"/"已申请"/"记录"）
- 弹窗：Vant + ElementUI 两套混用，扫码下载 App 弹窗、投递成功框都要关
- **第一大坑：应届生外链劫持**——搜索混有 yingjiesheng.com 外链，点职位会
  `window.open` 弹外部站甚至劫持主页。四层防御：route abort / window.open 覆写 /
  onPopup + onPage 关闭 / 投递前 evaluate 预检
- 反爬：阿里 WAF（`acw_tc` cookie），命中验证**放弃当前关键词**
- 日上限：三层检测 toast 文本，命中**停整个任务**
- 城市码表 26 个（6 位，北京=010000），薪资码表 13 个
- **实机新增**：`jobAreaString` 的地区串可能用**间隔号**分隔（`苏州·苏州工业园区`），
  不是只有 `-`。`splitLocation` 要按最先出现的分隔符切（`-`、`·`、空格都算）

### 3.3 智联招聘 zhilian
- 登录态：老工程纯 DOM（`a.home-header__c-no-login` 存在=未登录）；
  老工程**没有** cookie 名白名单，本工程需真机抓一次登录态 cookie 名再定
  （不猜）。只可微信扫码
- 搜索 URL：~~`https://www.zhaopin.com/sou/jl{城市码}/p{页码}?sl={薪资码}`，
  **关键词不走 URL**——导航后填输入框按 Enter~~
  → **实机作废**：填输入框按 Enter 后 SPA 跳到不含 `sl` 的地址，薪资过滤被丢。
  实际用 `https://www.zhaopin.com/jobs?pageMode=search&jl={码}&kw={词}&sl={区间}&page={页码}`，
  服务端渲染，关键词和薪资都生效。实测同一关键词有/无 `sl` 分别返回 20 和 11 条
- 列表：分页制，最多 50 页；下一页 `a.soupager__btn:has-text("下一页")`，
  末页判据是 class 含 `soupager__btn--disable`
- 数据源：**纯 DOM 采集**（老工程没拦任何接口）。卡片 `div.job-card`：
  职位名 `span.vue-clamp__text`、公司名+链接 `a.job-card__company-name`、
  薪资 `span.job-card__salary`、经验/技能 `span.job-card__skill-tag`
- jobId：~~从链接 `jobdetail/{id}.htm` 提取~~
  → **实机修正**：列表卡片上唯一的 `a[href]` 是**公司页**
  （`/companydetail/CZL....htm`），扫遍卡片全部 `data-*` 属性也没有 id。
  jobId 只存在于**点开卡片后详情面板**的 `a[href*='jobdetail/']`
  （形如 `CCL1254044340J40897050612.htm`）。所以每张卡都必须点一次面板，
  而面板 DOM 是复用的——必须用「列表卡标题对上 + jobdetail 链接和上一张不同」
  双条件确认面板已切换，投递前再用 `jobUrl` 精确校验一次
- **实机修正**：地区/经验/学历/招聘人数在
  `ul.job-detail-summary__tags > li.job-detail-summary__tag` 里（地区形如
  `苏州·常熟市`），**不在** `header-main` 的 span 里；学历**采得到**
  （`学历不限`/`大专`/`本科`），老台账"学历采不到"的结论作废
- 公司 meta 有**两种形态**：`/100-299人 · 行业`（两段）和
  `/未融资 · 20-99人 · 行业一、行业二`（三段），规模按"含人"认、行业取末段
- 投递：点卡片 → 详情面板 → `button.job-detail-summary__apply` /
  `button:has-text('立即投递')` / `button:has-text('投个简历')` 三选一；
  简历选择弹窗勾"每次投递默认发送该简历"；确认按钮 `button:has-text('投递简历')`
  （兜底：JS 找自身 innerText 精确等于"投递简历"的**最后一个**）
- **点投递可能新开标签页**，必须注册 `context.onPage` 及时关掉
- 遮罩：每个岗位点卡片前和每次投递后都要 JS 暴力 remove modal/mask/overlay
- 上限：`//div[@class='a-job-apply-workflow']` 文本含"达到上限" → 停任务
- 前置条件：账号里必须预设默认投递简历，否则投递失败
- 城市码表 42 个（北京=530）

## 4. 本工程的实现约定

- 包结构：`com.jobpilot.delivery`（共享内核）+ `com.jobpilot.{boss,liepin,job51,zhilian}`（适配器）
- 每个适配器固定五个文件：`XxxDriver`（浏览器操作）、`XxxJobCard`（卡片 + 解析）、
  `XxxSearchUrl`（URL 构造）、`XxxProperties`（配置）、`XxxController`（API）
- `XxxDriver` 不持有状态，所有方法都在 dispatcher 线程内被调用
- 卡片解析做成静态纯函数，可单测（不启动浏览器）
- 真机验证用**旧 browser-data 的副本**，绝不动原 profile

## 5. 风险与未决（2026-09-21 已全部收敛）

| 项 | 结论 |
|---|---|
| 智联登录 cookie 名未知 | **仍是 DOM 判定**：实测老 profile 的 zhaopin.com cookie 全是设备/统计字段，没有稳定登录 token，只能等 `a.home-header__c-no-login` 消失。智联只支持微信扫码 |
| 51job 的 `div.ss` 热身是否仍必需 | **不必需**，选择器已失效，已删 |
| 猎聘 salary 码表缺失 | 已自建（老工程也没有，属补齐而非照抄） |
| 智联学历字段 DOM 采不到 | **采得到**，在 `ul.job-detail-summary__tags` 里，DB 列有值 |
| 三平台同时只能跑一个 | 设计如此（单浏览器上下文），R5，`RunCoordinator` 实现 |

### 实机验证逼出来、台账原先没写的三条

1. **智联的面板是复用 DOM 异步换内容的**。点卡片后旧内容还在、标题节点也一直在，
   `waitFor` 立刻返回，固定 sleep 也不够——会读到上一张甚至下一张的面板，
   jobId 错 = 去重错 = 真实模式下投错职位。必须用「列表卡标题对上 + jobdetail 链接
   和上一张不同」双条件轮询，投递前再用 `jobUrl` 精确校验一次
2. **智联的薪资过滤是区间重叠不是包含**。`sl=12000,20000` 会放行 8000-15000，
   预演时看到"超范围"的岗位不是 bug
3. **51job 的地区串用间隔号**（`苏州·苏州工业园区`），`splitLocation` 不能只认 `-`

### 智联有一个用不上的接口，记下来备查

`POST https://fe-api.zhaopin.com/c/i/search/positions` 返回的数据很全
（`data.list[].jobDetailData.position.base.{positionName,salary,education,
positionWorkingExp,positionNumber,positionId}`、`companyName`、`companySize`、
`industryName`、`cityDistrict`、`description`、分页 `count`/`isEndPage`）。
但它的 POST body **没有薪资字段**，用了它就等于放弃薪资过滤，所以最终走的是
服务端渲染的 DOM 路线。哪天智联给这个接口加了薪资参数，可以换成它，会快很多。
