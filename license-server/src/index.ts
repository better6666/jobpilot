import { DEFAULT_PLANS, HIGHLIGHT, Plan, PlanRow, parseJson, toPublicPlan } from './plans'
import { Hono } from 'hono'
import { cors } from 'hono/cors'
import {
  generateCardKey,
  generateToken,
  isValidCardKey,
  normalizeCardKey,
  addDays,
  daysLeft
} from './lib/cards'
import {
  normalizeRelayBaseUrl,
  maskKey,
  modelListPaths,
  parseModelList
} from './lib/relay'

export interface Env {
  DB: D1Database
  RATE_LIMIT: KVNamespace
  ADMIN_KEY: string
}

interface Card {
  card_key: string
  batch: string
  type: string
  plan?: Plan
  duration_days: number | null
  quota_total: number | null
  quota_used: number
  max_devices: number
  status: string
  activated_at: string | null
  expires_at: string | null
  unbind_count: number
  last_unbind_at: string | null
  created_at: string
  note: string
}

interface Activation {
  id: number
  card_key: string
  device_id: string
  device_name: string
  token: string
  created_at: string
  last_verify: string | null
}

interface ActivateBody {
  card_key?: string
  device_id?: string
  device_name?: string
}
interface AuthBody {
  token?: string
  device_id?: string
}
interface ReportBody extends AuthBody {
  n?: number
}
interface CreateCardsBody {
  count?: number
  type?: string
  plan?: Plan
  duration_days?: number
  quota_total?: number
  max_devices?: number
  batch?: string
  note?: string
}
interface AiChatBody extends AuthBody {
  system?: string
  user?: string
  temperature?: number
}
interface AiRelay {
  base_url: string
  api_key: string
  model: string
}
interface AiSettingsBody {
  ai?: {
    base_url?: string | null
    api_key?: string | null
    model?: string | null
  }
}

// ---------------------------------------------------------------- 基础工具

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' }
  })
}

const ok = (data: Record<string, unknown> = {}): Response => json({ success: true, data })
const fail = (status: number, code: string, message: string): Response =>
  json({ success: false, error: { code, message } }, status)

function nowIso(): string {
  return new Date().toISOString()
}

function clampInt(v: unknown, min: number, max: number, dflt: number): number {
  const n = Number(v)
  if (!Number.isFinite(n)) return dflt
  return Math.min(max, Math.max(min, Math.floor(n)))
}

/** 滑动窗口限流：windowSec 内最多 limit 次 */
async function rateLimit(kv: KVNamespace, key: string, limit: number, windowSec: number): Promise<boolean> {
  const raw = await kv.get(key)
  const count = raw === null ? 0 : Number(raw)
  if (Number.isFinite(count) && count >= limit) return false
  await kv.put(key, String(count + 1), { expirationTtl: windowSec })
  return true
}

/**
 * token + 设备校验，返回卡；失败直接返回 Response。
 *
 * 两种失败必须分开报：
 *   token 查不到      → TOKEN_INVALID，token 是伪造的或已被解绑清掉
 *   token 对但设备不符 → DEVICE_MISMATCH，最常见的是把本机数据目录拷到了另一台机器
 * 混成一句话"激活信息无效"的话，用户不知道该重新激活还是该找卖家，
 * 也没法发现自己的卡正在别人机器上跑。
 */
async function authenticate(db: D1Database, token: string, deviceId: string): Promise<{ card: Card } | Response> {
  const activation = await db.prepare('SELECT * FROM activations WHERE token = ?').bind(token).first<Activation>()
  if (!activation) {
    return fail(401, 'TOKEN_INVALID', '激活信息无效，请重新激活')
  }
  if (activation.device_id !== deviceId) {
    return fail(401, 'DEVICE_MISMATCH',
      '当前设备与激活时不一致。若你换了电脑或改了主机名/用户名，请重新激活；'
      + '若并未更换设备，请检查是否有人复制了本机的 JobPilot 数据目录')
  }
  const card = await db.prepare('SELECT * FROM cards WHERE card_key = ?').bind(activation.card_key).first<Card>()
  if (!card || card.status !== 'active') {
    return fail(403, 'CARD_DISABLED', '卡密已被作废')
  }
  return { card }
}

/** 距到期还有几天；null / 已过期返回 0 */
function daysUntil(iso: string | null): number {
  if (!iso) return 0
  const ms = new Date(iso).getTime() - Date.now()
  return ms <= 0 ? 0 : Math.ceil(ms / 86400000)
}

function normalizePlan(v: unknown): Plan {
  return v === 'standard' || v === 'advanced' || v === 'trial' ? v : 'trial'
}

/** 读套餐配置；表空着就回退到代码里的兜底默认值，保证前台不至于全灰 */
async function loadPlans(db: D1Database): Promise<PlanRow[]> {
  try {
    const rows = await db.prepare(
      'SELECT * FROM plans WHERE active = 1 ORDER BY sort_order'
    ).all<PlanRow>()
    if (rows.results && rows.results.length > 0) return rows.results
  } catch { /* plans 表还没建（老库）时走兜底 */ }
  return DEFAULT_PLANS
}

/** 时效/次数校验：过期或用完的卡不能继续用平台额度。通过返回 null */
function checkCardUsable(card: Card): Response | null {
  const now = nowIso()
  if ((card.type === 'time' || card.type === 'trial') && (!card.expires_at || card.expires_at <= now)) {
    return fail(403, 'CARD_EXPIRED', '卡密已到期')
  }
  if (card.type === 'quota' && card.quota_used >= (card.quota_total ?? 0)) {
    return fail(403, 'QUOTA_EXHAUSTED', '卡密次数已用完')
  }
  return null
}

// ---------------------------------------------------------------- AI 中转

const SETTINGS_AI_KEY = 'ai_relay'

/**
 * 平台自备的 OpenAI 兼容中转。三项缺任意一项都算"没配"——
 * 客户端看到没配就提示用户改用"我自己的接口"，不会把空地址传出去。
 */
async function getAiRelay(db: D1Database): Promise<AiRelay | null> {
  const row = await db.prepare('SELECT value FROM settings WHERE key = ?').bind(SETTINGS_AI_KEY).first<{ value: string }>()
  if (!row) return null
  try {
    const v = JSON.parse(row.value) as Partial<AiRelay>
    const baseUrl = normalizeRelayBaseUrl(v.base_url)
    if (!baseUrl || !v.api_key?.trim() || !v.model?.trim()) return null
    return { base_url: baseUrl, api_key: v.api_key.trim(), model: v.model.trim() }
  } catch {
    return null
  }
}

async function saveAiRelay(db: D1Database, relay: AiRelay): Promise<void> {
  await db.prepare(
    `INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`
  ).bind(SETTINGS_AI_KEY, JSON.stringify(relay), nowIso()).run()
}

/** 服务端代理调中转。返回文本或错误文案，绝不抛异常、绝不带 key */
async function callRelay(
  relay: AiRelay,
  system: string,
  user: string,
  temperature: number
): Promise<{ text: string } | { error: string }> {
  const payload = {
    model: relay.model,
    stream: false,
    temperature,
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: user }
    ]
  }
  let response: Response
  try {
    response = await fetch(relay.base_url + '/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + relay.api_key,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(45000)
    })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    return { error: '连接中转失败或超时: ' + msg }
  }
  const body = await response.text()
  if (response.status < 200 || response.status >= 300) {
    return { error: describeRelayStatus(response.status, body) }
  }
  try {
    const parsed = JSON.parse(body) as { choices?: { message?: { content?: string } }[] }
    const content = parsed.choices?.[0]?.message?.content
    if (!content || !content.trim()) return { error: '中转返回 200 但没有内容（choices 为空）' }
    return { text: content }
  } catch {
    return { error: '中转响应不是合法 JSON' }
  }
}

/**
 * 拉中转站的模型列表，给管理页"模型名"用——填好地址和 Key 点一下就出列表，
 * 不用去中转站文档里抄模型名再手打。
 *
 * <p>逐个路径试（见 {@link modelListPaths}）：各家中转站挂 /models 的位置
 * 不统一，只试标准那条的话，一半的站会 404 然后用户以为 key 错了。
 * 有一个路径返回得了非空列表就收工。
 */
async function listRelayModels(
  baseUrl: string,
  apiKey: string
): Promise<{ models: string[] } | { error: string }> {
  const paths = modelListPaths(baseUrl)
  if (paths.length === 0) return { error: '中转地址没配或不是 http(s) 地址' }
  if (!apiKey) return { error: 'API Key 没配' }

  let lastError = ''
  for (const path of paths) {
    let response: Response
    try {
      response = await fetch(path, {
        method: 'GET',
        headers: { Authorization: 'Bearer ' + apiKey },
        signal: AbortSignal.timeout(20000)
      })
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      lastError = '连接中转失败或超时: ' + msg
      continue
    }
    const body = await response.text()
    if (response.status < 200 || response.status >= 300) {
      // 404 只说明这个路径没有，换下一条；别的状态码是 key 或权限问题，
      // 换路径也白搭，直接结束
      if (response.status === 404) {
        lastError = '该地址下没有 /models 接口'
        continue
      }
      return { error: describeRelayStatus(response.status, body) }
    }
    let models: string[] = []
    try {
      models = parseModelList(JSON.parse(body))
    } catch {
      return { error: '中转响应不是合法 JSON' }
    }
    if (models.length > 0) return { models }
    lastError = '中转没返回任何模型'
  }
  return { error: lastError || '没找到模型列表接口' }
}

/** 非 2xx 的中转响应翻成人话。和 callRelay 里的判断保持一套说法 */
function describeRelayStatus(status: number, body: string): string {
  let detail = ''
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string }; message?: string }
    detail = parsed.error?.message ?? parsed.message ?? ''
  } catch {
    detail = body.length > 200 ? body.slice(0, 200) : body
  }
  const reason =
    status === 401 || status === 403
      ? '中转拒绝了（key 无效或没有该模型权限）'
      : status === 404
        ? '中转地址或模型不存在'
        : status === 429
          ? '中转限流'
          : status >= 500
            ? '中转服务端错误'
            : '中转返回 ' + status
  return detail ? reason + '：' + detail : reason
}

// ---------------------------------------------------------------- 应用

const app = new Hono<{ Bindings: Env }>()

app.use('*', cors())

// 根路径留给 public/index.html（激活页），服务信息挪到 /api/info
app.get('/api/info', (c) => ok({ name: 'jobpilot-license', version: '0.1.0' }))

// ---------------------------------------------------------------- 客户端接口

/** 激活：绑设备、发 token；同一设备重复激活幂等返回原 token */
app.post('/activate', async (c) => {
  const ip = c.req.header('cf-connecting-ip') ?? 'unknown'
  if (!(await rateLimit(c.env.RATE_LIMIT, `rl:activate:ip:${ip}`, 10, 60))) {
    return fail(429, 'RATE_LIMITED', '尝试过于频繁，请稍后再试')
  }

  const body = await c.req.json<ActivateBody>().catch(() => null)
  const cardKey = normalizeCardKey(body?.card_key ?? '')
  const deviceId = body?.device_id?.trim() ?? ''
  if (!cardKey || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 card_key 或 device_id')
  }
  if (!isValidCardKey(cardKey)) {
    return fail(400, 'BAD_CARD_FORMAT', '卡密格式不正确')
  }

  // 防枚举：单卡连续失败 10 次锁定 1 小时
  const failKey = `rl:activate:card:${cardKey}`
  const fails = Number((await c.env.RATE_LIMIT.get(failKey)) ?? 0)
  if (Number.isFinite(fails) && fails >= 10) {
    return fail(429, 'CARD_LOCKED', '该卡密失败次数过多，已锁定 1 小时')
  }

  const card = await c.env.DB.prepare('SELECT * FROM cards WHERE card_key = ?').bind(cardKey).first<Card>()
  if (!card) {
    await c.env.RATE_LIMIT.put(failKey, String(fails + 1), { expirationTtl: 3600 })
    return fail(404, 'CARD_NOT_FOUND', '卡密不存在')
  }
  if (card.status !== 'active') {
    return fail(403, 'CARD_DISABLED', '卡密已被作废')
  }

  const existing = await c.env.DB.prepare(
    'SELECT * FROM activations WHERE card_key = ? AND device_id = ?'
  ).bind(cardKey, deviceId).first<Activation>()

  if (!existing) {
    const bound = await c.env.DB.prepare(
      'SELECT COUNT(*) AS n FROM activations WHERE card_key = ?'
    ).bind(cardKey).first<{ n: number }>()
    if ((bound?.n ?? 0) >= card.max_devices) {
      return fail(403, 'DEVICE_LIMIT', '该卡密绑定的设备数已达上限')
    }
    // 试用卡每设备限领一次
    if (card.type === 'trial') {
      const used = await c.env.DB.prepare(
        `SELECT COUNT(*) AS n FROM activations a
         JOIN cards c ON a.card_key = c.card_key
         WHERE a.device_id = ? AND c.type = 'trial'`
      ).bind(deviceId).first<{ n: number }>()
      if ((used?.n ?? 0) > 0) {
        return fail(403, 'TRIAL_USED', '该设备已领取过试用卡')
      }
    }
  }

  const now = nowIso()
  let token = existing?.token
  if (!token) {
    token = generateToken()
    await c.env.DB.prepare(
      `INSERT INTO activations (card_key, device_id, device_name, token, created_at, last_verify)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(cardKey, deviceId, (body?.device_name ?? '').slice(0, 64), token, now, now).run()
  }

  // 时长/试用卡：首次激活即开始计时
  let expiresAt = card.expires_at
  if (!existing && (card.type === 'time' || card.type === 'trial') && !card.activated_at) {
    expiresAt = addDays(now, card.duration_days ?? 0)
    await c.env.DB.prepare(
      'UPDATE cards SET activated_at = ?, expires_at = ? WHERE card_key = ?'
    ).bind(now, expiresAt, cardKey).run()
  }

  await c.env.RATE_LIMIT.delete(failKey)
  return ok({
    token,
    type: card.type,
    expires_at: expiresAt ?? null,
    remaining_days: expiresAt ? daysLeft(expiresAt) : null,
    quota_total: card.quota_total ?? null,
    quota_used: card.quota_used ?? 0,
    quota_remaining:
      card.quota_total != null ? Math.max(0, card.quota_total - card.quota_used) : null
  })
})

/** 心跳：客户端每 10 分钟调用一次 */
app.post('/verify', async (c) => {
  const body = await c.req.json<AuthBody>().catch(() => null)
  const token = body?.token?.trim() ?? ''
  const deviceId = body?.device_id?.trim() ?? ''
  if (!token || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 token 或 device_id')
  }

  const auth = await authenticate(c.env.DB, token, deviceId)
  if (auth instanceof Response) return auth
  const { card } = auth

  const expired = checkCardUsable(card)
  if (expired) return expired

  const now = nowIso()
  await c.env.DB.prepare('UPDATE activations SET last_verify = ? WHERE token = ?').bind(now, token).run()
  return ok({
    type: card.type,
    expires_at: card.expires_at ?? null,
    remaining_days: card.expires_at ? daysLeft(card.expires_at) : null,
    quota_total: card.quota_total ?? null,
    quota_used: card.quota_used ?? 0,
    quota_remaining:
      card.quota_total != null ? Math.max(0, card.quota_total - card.quota_used) : null
  })
})

/** 上报投递次数（仅次数卡扣减） */
app.post('/report', async (c) => {
  const body = await c.req.json<ReportBody>().catch(() => null)
  const token = body?.token?.trim() ?? ''
  const deviceId = body?.device_id?.trim() ?? ''
  if (!token || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 token 或 device_id')
  }

  const auth = await authenticate(c.env.DB, token, deviceId)
  if (auth instanceof Response) return auth
  const { card } = auth

  if (card.type !== 'quota') {
    return fail(400, 'NOT_QUOTA_CARD', '该卡密不是次数卡，无需上报')
  }
  const raw = Number(body?.n ?? 1)
  const n = Number.isFinite(raw) ? Math.min(1000, Math.max(0, Math.floor(raw))) : 1

  await c.env.DB.prepare(
    `UPDATE cards
     SET quota_used = MIN(COALESCE(quota_total, 0), COALESCE(quota_used, 0) + ?)
     WHERE card_key = ?`
  ).bind(n, card.card_key).run()

  const fresh = await c.env.DB.prepare(
    'SELECT quota_used, quota_total FROM cards WHERE card_key = ?'
  ).bind(card.card_key).first<Pick<Card, 'quota_used' | 'quota_total'>>()

  return ok({
    reported: n,
    quota_used: fresh?.quota_used ?? 0,
    quota_remaining:
      fresh?.quota_total != null ? Math.max(0, fresh.quota_total - fresh.quota_used) : null
  })
})

/** 自助解绑：终身 3 次，每次冷却 7 天 */
app.post('/unbind', async (c) => {
  const body = await c.req.json<AuthBody>().catch(() => null)
  const token = body?.token?.trim() ?? ''
  const deviceId = body?.device_id?.trim() ?? ''
  if (!token || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 token 或 device_id')
  }

  const auth = await authenticate(c.env.DB, token, deviceId)
  if (auth instanceof Response) return auth
  const { card } = auth

  if (card.unbind_count >= 3) {
    return fail(403, 'UNBIND_LIMIT', '换绑次数已用完（终身 3 次）')
  }
  if (card.last_unbind_at && Date.now() - new Date(card.last_unbind_at).getTime() < 7 * 86400000) {
    return fail(403, 'UNBIND_COOLDOWN', '解绑冷却期内（7 天），请稍后再试')
  }

  const now = nowIso()
  await c.env.DB.prepare(
    'UPDATE cards SET unbind_count = COALESCE(unbind_count, 0) + 1, last_unbind_at = ? WHERE card_key = ?'
  ).bind(now, card.card_key).run()
  await c.env.DB.prepare('DELETE FROM activations WHERE card_key = ? AND device_id = ?')
    .bind(card.card_key, deviceId).run()

  return ok({ unbound: true, unbind_count: card.unbind_count + 1, unbind_limit: 3 })
})

// ---------------------------------------------------------------- AI 中转（客户端）

/**
 * 平台中转有没有配。不需要鉴权：只回答"配了没"和"用的什么模型"，
 * 不含 key 也不含地址——客户端要靠这个决定页面上显示哪种模式。
 */
/**
 * 公开套餐列表：会员页用，不需要鉴权。
 * 价格/天数/权益全来自 plans 表，后台改完这里立刻变，前端不存硬编码价格。
 */
app.get('/api/plans', async (c) => {
  const rows = await loadPlans(c.env.DB)
  return ok({
    plans: rows.map(toPublicPlan),
    highlight: HIGHLIGHT,
  })
})

/**
 * 当前设备的 entitlement：plan + 功能开关 + 配额 + 卡片状态。
 *
 * 客户端每次启动和每次心跳后拉这个，用它决定哪些功能可用、今天还能投几次。
 * 服务端是唯一真相源——客户端缓存只用于断网时兜底。
 */
app.post('/api/license/entitlement', async (c) => {
  const body = await c.req.json<AuthBody>().catch(() => null)
  const token = body?.token?.trim() ?? ''
  const deviceId = body?.device_id?.trim() ?? ''
  if (!token || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 token 或 device_id')
  }

  const auth = await authenticate(c.env.DB, token, deviceId)
  if (auth instanceof Response) return auth
  const { card } = auth

  const rows = await loadPlans(c.env.DB)
  const row = rows.find(r => r.plan === normalizePlan(card.plan)) ?? rows[0] ?? DEFAULT_PLANS[0]
  const usable = checkCardUsable(card)

  return ok({
    plan: normalizePlan(card.plan),
    plan_name: row.name,
    card_type: card.type,
    card_status: card.status,
    usable: usable === null,
    expires_at: card.expires_at,
    remaining_days: daysUntil(card.expires_at),
    quota_total: card.quota_total,
    quota_remaining: card.quota_total == null ? null : card.quota_total - card.quota_used,
    features: parseJson<Record<string, boolean>>(row.features, {}),
    quotas: parseJson<Record<string, number>>(row.quotas, {}),
    recommended: row.recommended === 1,
  })
})

app.get('/api/ai/info', async (c) => {
  const relay = await getAiRelay(c.env.DB)
  return ok({ configured: !!relay, model: relay?.model ?? null })
})

/**
 * 平台中转代理。客户端只带 token + device_id + 提示词，中转的地址和 key
 * 全程留在服务端——买卡的人从安装目录里翻不出这把 key。
 *
 * 失败一律走 fail()：调用方（GreetingService）会退回固定话术，不让投递中断。
 */
app.post('/api/ai/chat', async (c) => {
  const body = await c.req.json<AiChatBody>().catch(() => null)
  const token = body?.token?.trim() ?? ''
  const deviceId = body?.device_id?.trim() ?? ''
  if (!token || !deviceId) {
    return fail(400, 'BAD_REQUEST', '缺少 token 或 device_id')
  }

  const auth = await authenticate(c.env.DB, token, deviceId)
  if (auth instanceof Response) return auth
  const { card } = auth

  const expired = checkCardUsable(card)
  if (expired) return expired

  // 按卡限流：一张卡每分钟最多 120 次，够跑批又不至于被一个客户端打爆中转额度
  if (!(await rateLimit(c.env.RATE_LIMIT, `rl:ai:card:${card.card_key}`, 120, 60))) {
    return fail(429, 'RATE_LIMITED', '请求过于频繁，请稍后再试')
  }

  const relay = await getAiRelay(c.env.DB)
  if (!relay) {
    return fail(503, 'AI_NOT_CONFIGURED', '平台未配置 AI 中转，请在客户端改用"我自己的接口"')
  }

  const user = body?.user ?? ''
  if (!user.trim()) {
    return fail(400, 'BAD_REQUEST', '缺少 user 提示词')
  }
  const rawTemp = Number(body?.temperature)
  const temperature = Number.isFinite(rawTemp) ? Math.min(2, Math.max(0, rawTemp)) : 0.9

  const result = await callRelay(relay, body?.system ?? '', user, temperature)
  if ('error' in result) {
    return fail(502, 'RELAY_FAILED', result.error)
  }
  return ok({ text: result.text, model: relay.model })
})

// ---------------------------------------------------------------- 管理接口

app.use('/admin/*', async (c, next) => {
  const auth = c.req.header('authorization') ?? ''
  if (auth !== `Bearer ${c.env.ADMIN_KEY}`) {
    return fail(401, 'UNAUTHORIZED', 'admin key 错误')
  }
  await next()
})

/** 批量发卡。卡密只在创建响应里完整返回一次，之后查询一律打码。 */
app.post('/admin/cards', async (c) => {
  const body = await c.req.json<CreateCardsBody>().catch(() => null)
  const type = body?.type ?? ''
  if (type !== 'time' && type !== 'quota' && type !== 'trial') {
    return fail(400, 'BAD_TYPE', 'type 必须是 time / quota / trial')
  }

  const count = clampInt(body?.count, 1, 100, 1)
  const batch = (body?.batch ?? nowIso().slice(0, 10)).slice(0, 40)
  const note = (body?.note ?? '').slice(0, 200)
  const maxDevices = clampInt(body?.max_devices, 1, 10, 1)
  const plan = normalizePlan(body?.plan)

  let durationDays: number | null = null
  let quotaTotal: number | null = null
  if (type === 'quota') {
    quotaTotal = clampInt(body?.quota_total, 1, 1000000, 1000)
  } else {
    // 套餐天数没显式给就按档位默认值来（3 天 / 30 天 / 30 天）
    durationDays = clampInt(body?.duration_days, 1, 3650,
      plan === 'trial' ? 3 : 30)
  }

  const keys: string[] = []
  const stmts: D1PreparedStatement[] = []
  for (let i = 0; i < count; i++) {
    const key = generateCardKey()
    keys.push(key)
    stmts.push(
      c.env.DB.prepare(
        `INSERT INTO cards
           (card_key, batch, type, duration_days, quota_total, quota_used, max_devices, status, unbind_count, created_at, note, plan)
         VALUES (?, ?, ?, ?, ?, 0, ?, 'active', 0, ?, ?, ?)`
      ).bind(key, batch, type, durationDays, quotaTotal, maxDevices, nowIso(), note, plan)
    )
  }
  // D1 单批语句数有限制，分片提交
  for (let i = 0; i < stmts.length; i += 50) {
    await c.env.DB.batch(stmts.slice(i, i + 50))
  }

  return ok({ keys, count: keys.length, batch, type, plan })
})

/** 卡密列表（卡号打码，只显示前 9 位） */
app.get('/admin/cards', async (c) => {
  const status = c.req.query('status')
  const batch = c.req.query('batch')
  const limit = clampInt(c.req.query('limit'), 1, 500, 100)

  const where: string[] = []
  const binds: (string | number)[] = []
  if (status) {
    where.push('status = ?')
    binds.push(status)
  }
  if (batch) {
    where.push('batch = ?')
    binds.push(batch)
  }
  binds.push(limit)

  const { results } = await c.env.DB.prepare(
    `SELECT card_key, batch, type, duration_days, quota_total, quota_used, max_devices, status,
            activated_at, expires_at, unbind_count, last_unbind_at, created_at, note,
            (SELECT COUNT(*) FROM activations a WHERE a.card_key = cards.card_key) AS bound_devices
     FROM cards
     ${where.length ? 'WHERE ' + where.join(' AND ') : ''}
     ORDER BY created_at DESC
     LIMIT ?`
  )
    .bind(...binds)
    .all<Record<string, unknown>>()

  const cards = (results ?? []).map((row) => ({
    ...row,
    card_key_masked: String(row.card_key).slice(0, 9) + '****',
    card_key: undefined
  }))
  return ok({ cards })
})

/** 作废卡密 */
app.post('/admin/cards/:key/disable', async (c) => {
  const key = normalizeCardKey(c.req.param('key'))
  if (!isValidCardKey(key)) {
    return fail(400, 'BAD_CARD_FORMAT', '卡密格式不正确')
  }
  await c.env.DB.prepare(`UPDATE cards SET status = 'disabled' WHERE card_key = ?`).bind(key).run()
  return ok({ disabled: key })
})

/** 运营统计 */
app.get('/admin/stats', async (c) => {
  const byStatus = await c.env.DB.prepare('SELECT status, COUNT(*) AS n FROM cards GROUP BY status').all()
  const byType = await c.env.DB.prepare('SELECT type, COUNT(*) AS n FROM cards GROUP BY type').all()
  const since = new Date(Date.now() - 86400000).toISOString()
  const recent = await c.env.DB.prepare(
    'SELECT COUNT(*) AS n FROM activations WHERE last_verify >= ?'
  ).bind(since).first<{ n: number }>()
  return ok({
    by_status: byStatus.results ?? [],
    by_type: byType.results ?? [],
    verified_last_24h: recent?.n ?? 0
  })
})

/** 平台配置（AI 中转）。key 一律打码，和客户端一个规矩 */
app.get('/admin/settings', async (c) => {
  const relay = await getAiRelay(c.env.DB)
  if (!relay) {
    return ok({
      ai: {
        configured: false,
        base_url: '',
        model: '',
        api_key_set: false,
        api_key_masked: null
      }
    })
  }
  return ok({
    ai: {
      configured: true,
      base_url: relay.base_url,
      model: relay.model,
      api_key_set: true,
      api_key_masked: maskKey(relay.api_key)
    }
  })
})

/**
 * 保存平台配置。语义和客户端的 /api/ai/config 一致：
 * api_key 传 null/不传 = 保留原值，空串 = 清除，其他 = 替换。
 * 地址在这里就归一，存进去的永远是能直接拼 /chat/completions 的形状。
 */
app.put('/admin/settings', async (c) => {
  const body = await c.req.json<AiSettingsBody>().catch(() => null)
  if (!body?.ai) {
    return fail(400, 'BAD_REQUEST', '缺少 ai 段')
  }
  const existing = await getAiRelay(c.env.DB)
  const baseUrl = normalizeRelayBaseUrl(body.ai.base_url ?? existing?.base_url ?? '')
  const model = (body.ai.model ?? existing?.model ?? '').trim()
  let apiKey = existing?.api_key ?? ''
  if (body.ai.api_key != null) {
    apiKey = body.ai.api_key.trim()
  }
  await saveAiRelay(c.env.DB, { base_url: baseUrl, api_key: apiKey, model })
  return ok({ saved: true, configured: !!(baseUrl && apiKey && model), base_url: baseUrl })
})

/** 测平台中转通不通。可以带临时覆盖值，改完先测再存 */
app.post('/admin/ai/test', async (c) => {
  const body = await c.req.json<{ base_url?: string; api_key?: string; model?: string }>().catch(() => null)
  const existing = await getAiRelay(c.env.DB)
  const baseUrl = normalizeRelayBaseUrl(body?.base_url ?? existing?.base_url ?? '')
  const apiKey = (body?.api_key ?? existing?.api_key ?? '').trim()
  const model = (body?.model ?? existing?.model ?? '').trim()
  if (!baseUrl || !apiKey || !model) {
    return ok({ ok: false, error: '地址 / Key / 模型没配全，先填好再测' })
  }
  const result = await callRelay(
    { base_url: baseUrl, api_key: apiKey, model },
    '你是连通性测试助手。',
    '请用一句中文回答：你能正常工作吗？',
    0.3
  )
  if ('error' in result) {
    return ok({ ok: false, error: result.error, base_url: baseUrl })
  }
  return ok({ ok: true, reply: result.text, base_url: baseUrl, model })
})

/**
 * 拉中转站的模型列表。和 /admin/ai/test 一样可以带临时覆盖值——用户刚把地址
 * 和 Key 敲进去、还没保存时就想看有哪些模型可选，这时候库里还是旧的。
 *
 * <p>key 只在这个请求里用，响应里一个字符都不带回。
 */
app.post('/admin/ai/models', async (c) => {
  const body = await c.req
    .json<{ base_url?: string; api_key?: string }>()
    .catch(() => null)
  const existing = await getAiRelay(c.env.DB)
  const baseUrl = normalizeRelayBaseUrl(body?.base_url ?? existing?.base_url ?? '')
  const apiKey = (body?.api_key ?? existing?.api_key ?? '').trim()
  if (!baseUrl || !apiKey) {
    return ok({ ok: false, error: '地址 / Key 没填全，先填好再拉' })
  }
  const result = await listRelayModels(baseUrl, apiKey)
  if ('error' in result) {
    return ok({ ok: false, error: result.error, base_url: baseUrl })
  }
  return ok({ ok: true, models: result.models, base_url: baseUrl })
})

export default app
