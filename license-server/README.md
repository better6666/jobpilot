# JobPilot 卡密服务端

基于 Cloudflare Worker + D1 + KV 的发卡/验卡服务，免费额度足够中小规模使用。

## 端点

客户端（无需鉴权，靠卡密/token 本身）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/activate` | 激活：`{card_key, device_id, device_name}` → 绑设备、发 token |
| POST | `/verify` | 心跳：`{token, device_id}` → 返回剩余天数/次数 |
| POST | `/report` | 上报投递次数（仅次数卡扣减）：`{token, device_id, n}` |
| POST | `/unbind` | 自助解绑：终身 3 次、每次冷却 7 天 |
| GET | `/api/ai/info` | 平台 AI 中转配了没、用的什么模型（客户端拿它决定显示什么） |
| POST | `/api/ai/chat` | 平台中转代理：`{token, device_id, system, user, temperature}` → `{text, model}` |

管理端（`Authorization: Bearer <ADMIN_KEY>`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/cards` | 批量发卡：`{count, type, duration_days \| quota_total, batch, note, max_devices}` |
| GET | `/admin/cards?status=&batch=&limit=` | 卡密列表（卡号打码） |
| POST | `/admin/cards/:key/disable` | 作废卡密 |
| GET | `/admin/stats` | 运营统计 |
| GET | `/admin/settings` | 读平台配置（AI 中转的 key 只回打码值） |
| PUT | `/admin/settings` | 存平台配置：`{ai:{base_url, api_key, model}}`，api_key 不传=保留、空串=清除 |
| POST | `/admin/ai/test` | 测中转通不通，可带临时覆盖值改完先测再存 |

其他：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 激活页（静态资源，来自 `public/index.html`） |
| GET | `/api/info` | 服务信息（Worker 存活探活用这个） |

卡种：`time`（时长卡，激活即计时）/ `quota`（次数卡）/ `trial`（试用卡，每设备限一次）。

> `public/index.html` 是 `../src/main/resources/static/license.html` 的副本，由 Worker 的
> `assets` 配置托管。改激活页请改源文件后重新复制，别只改一份。

## 本地开发

```bash
npm install
npm run db:init      # 本地 D1 建表
npm run dev          # http://localhost:8787
```

本地 admin key 在 `.dev.vars`（已被 gitignore，不会提交）。

跑一遍全流程自测：

```bash
# 1. 发一张 30 天时长卡
AK="Authorization: Bearer $(grep '^ADMIN_KEY' .dev.vars | cut -d= -f2)"
curl -s -X POST http://localhost:8787/admin/cards \
  -H "$AK" \
  -H 'Content-Type: application/json' \
  -d '{"count":1,"type":"time","duration_days":30}'

# 2. 激活（用上一步返回的卡密）
curl -s -X POST http://localhost:8787/activate \
  -H 'Content-Type: application/json' \
  -d '{"card_key":"GK-XXXX-XXXX-XXXX-XXXX","device_id":"test-device-1"}'

# 3. 心跳 / 解绑
curl -s -X POST http://localhost:8787/verify \
  -H 'Content-Type: application/json' \
  -d '{"token":"<activate返回的token>","device_id":"test-device-1"}'
```

## 平台 AI 中转

中转的地址和 key 存在服务端 `settings` 表里（`key='ai'`，value 是整段 JSON），客户端
`/api/ai/chat` 只带 token + device_id + 提示词——**key 全程不出服务端**。买卡的人从安装
目录里翻不出这把 key，也就烧不掉平台的额度。客户端那边看到的一律是打码值。

配一次就全员可用：在 `manage.html` 的「平台 AI 中转配置」面板填地址 / Key / 模型，
客户在应用的「AI 话术」页选"平台提供"即可，什么都不用再填。三项缺任何一项，
客户端会看到"平台没开 AI 中转"并被引导去填自己的接口。

- 地址在保存时就地归一（补 `/v1`、砍掉粘多的 `/chat/completions`），存进去的永远是能直接拼端点的形状；归一逻辑在 `src/lib/relay.ts`，与 Java 侧 `AiService.normalizeBaseUrl` 逐条对齐、有单测
- 按卡限流：一张卡每分钟最多 120 次，够跑批又不至于被一个客户端打爆中转额度
- 失败语义：401 token 不对 / 403 卡不可用 / 429 太频繁 / 503 平台没配中转 / 502 中转本身报错
- 本地联调可以用一个假中转站验整条链路（不起真 key）：起个监听 `/v1/chat/completions`
  返回 `{"choices":[{"message":{"content":"..."}}]}` 的服务，地址填它即可

## 部署到 Cloudflare

本项目已部署，两个入口：

- **https://jobpilot.better999.dpdns.org**（主，自定义域名）
- https://jobpilot-license.2333333434.workers.dev（备，workers.dev 国内不稳）

绑自定义域名的方法：`wrangler.toml` 里加

```toml
routes = [
  { pattern = "jobpilot.<你的域名>", custom_domain = true }
]
```

再 `npm run deploy`，CF 会自动在对应 zone 下建代理 DNS 记录（要求 wrangler 登录账号对该 zone 有权限）。
注意 `routes` 必须放在所有 `[[d1_databases]]` / `[[kv_namespaces]]` 表格之前。
另外自定义域名存在时 wrangler 会默认关掉 workers.dev，想两个入口都在就显式写 `workers_dev = true`。

从零复现：

```bash
npm install

# 1. 建 D1 数据库，把输出的 database_id 回填到 wrangler.toml
npx wrangler d1 create jobpilot-license

# 2. 建 KV 命名空间，把 id 回填到 wrangler.toml
npx wrangler kv namespace create jobpilot-license-ratelimit

# 3. 远端建表
npx wrangler d1 execute jobpilot-license --remote --file schema.sql

# 3b. 已部署过的库补新表（schema.sql 是 CREATE TABLE IF NOT EXISTS，重复执行安全；
#     加了 settings 表之后必须先补这一步，否则 /admin/settings 会报 no such table）
npx wrangler d1 execute jobpilot-license --remote --file schema.sql

# 4. 设置 admin key（只输一次，之后不可见；本地 .dev.vars 里也放同一把方便联调）
npx wrangler secret put ADMIN_KEY

# 5. 部署（public/ 目录会作为静态资源一起上传，根路径即激活页）
npm run deploy
```

部署后把 Worker 地址配到后端 `application.yaml` 的 `license.api-base`（或启动参数 `--license.api-base=`）。当前线上地址：

- Worker：`https://jobpilot-license.2333333434.workers.dev`
- D1：`jobpilot-license`（id `65f9e271-43f6-4032-88ff-1d45d563be45`）
- KV：`jobpilot-license-ratelimit`（id `8560e1266b05425abba499947e150775`）

注意：workers.dev 域名在国内访问不稳定，客户端已内置 72 小时宽限期，服务端短暂不可达不会拦截投递；正式售卖建议绑国内可达的自定义域名。
