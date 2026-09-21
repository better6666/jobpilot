/**
 * 服务端代理用的两个小工具：地址归一和 key 打码。
 *
 * 之所以单独抽一个文件而不是写在 index.ts 里：这两份逻辑在客户端
 * （AiService.normalizeBaseUrl / AiProperties.maskApiKey）各有一份对应实现，
 * 行为必须一致。抽出来才能像 cards.ts 一样被单测直接覆盖——
 * 两边不一致的后果是管理页里存得下、代理调不通，而这种问题肉眼看不出来。
 */

/**
 * 把地址归一成 {@code https://host[/v1]} 形状，容忍四种常见填法：
 *
 * ```
 *   https://api.openai.com            → https://api.openai.com/v1
 *   https://api.openai.com/v1/        → https://api.openai.com/v1
 *   https://relay.example.com/openai  → 原样（中转站自定义前缀）
 *   https://x.com/v1/chat/completions → https://x.com/v1（粘了完整端点）
 * ```
 *
 * 不合法（空、非 http(s)、只有 scheme 没有 host）一律返回空串，
 * 由调用方当成"没配"处理。
 */
export function normalizeRelayBaseUrl(raw: unknown): string {
  if (typeof raw !== 'string') return ''
  let s = raw.trim()
  while (s.endsWith('/')) s = s.slice(0, -1)
  if (!s) return ''
  const lower = s.toLowerCase()
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) return ''
  // 协议头统一小写：有人会粘 "HTTPS://..." 进来
  const schemeEnd = s.indexOf('://')
  s = s.slice(0, schemeEnd).toLowerCase() + s.slice(schemeEnd)
  // 粘了整个端点进来：砍掉尾部的 /chat/completions
  if (lower.endsWith('/chat/completions')) {
    s = s.slice(0, -'/chat/completions'.length)
    while (s.endsWith('/')) s = s.slice(0, -1)
  }
  // 只有 scheme + host（没有路径）时补 /v1
  if (!/^https?:\/\/[^/]+\/./i.test(s)) s = s + '/v1'
  return s
}

/**
 * key 打码：留头尾，中间用省略号。短 key（8 字符以内）直接 "****"——
 * 留头尾会把整串都露出来，反而等于没打码。
 */
export function maskKey(key: string): string {
  if (!key || !key.trim()) return ''
  const k = key.trim()
  if (k.length <= 8) return '****'
  const keep = Math.min(7, k.length - 4)
  return k.slice(0, keep) + '…' + k.slice(k.length - 4)
}

// ---------------------------------------------------------------- 模型列表

/**
 * 拉模型列表时要试的路径，按优先级排。去重后逐个试，取第一个返回得了的。
 *
 * <p>为什么不能只试 {@code base + '/models'}：各家中转站挂这个接口的位置
 * 不统一，实测见过的填法——
 * <ul>
 *   <li>{@code https://api.openai.com} → 归一成 {@code /v1}，列表在 {@code /v1/models}（标准）</li>
 *   <li>有的站只在根上挂 {@code /models}，{@code /v1/models} 反而 404</li>
 *   <li>有的站给自定义前缀（{@code /openai}、{@code /api/v3}），列表在前缀下，
 *       但同一个域名的根上往往也挂着一份（多租户中转常见）</li>
 * </ul>
 *
 * 顺序上"地址本身拼的"优先——用户填的前缀是中转站文档指明的那条，
 * 兜底路径只是备胎。备胎可能命中同一域名下的另一套服务，所以拉到的列表
 * 要点过「测试连通」才算数，不能当免检。
 */
export function modelListPaths(base: string): string[] {
  if (!base) return []
  const paths = [base + '/models']
  // 地址以 /v1 结尾时，根路径常常也挂着一份
  const withoutV1 = base.replace(/\/v1\/?$/i, '')
  if (withoutV1 && withoutV1 !== base) paths.push(withoutV1 + '/models')
  // 最后一搏：同一个域名的根。base 已经过归一，正常一定是合法绝对地址
  try {
    const root = new URL(base).origin
    if (root && !paths.includes(root + '/models')) paths.push(root + '/models')
  } catch {
    // 地址不合法时上游已经拦掉了，这里安静退出去就行
  }
  return [...new Set(paths)]
}

/** 从一个数组元素里抠模型名。对象认 id/model/name，字符串直接用 */
function modelIdOf(node: unknown): string {
  if (typeof node === 'string') return node.trim()
  if (node && typeof node === 'object') {
    for (const key of ['id', 'model', 'name']) {
      const v = (node as Record<string, unknown>)[key]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
  }
  return ''
}

/**
 * 解析 /models 响应。中转站返回的形状比标准乱得多，按见到过的顺序兜：
 *
 * ```
 *   {"data":[{"id":"gpt-4o"}]}   OpenAI 标准
 *   {"data":["gpt-4o"]}          少数站把 data 做成字符串数组
 *   {"models":[{"id":"..."}]}    自建网关常用
 *   {"models":["..."]}
 *   [{"id":"..."}]               顶层就是数组
 *   ["..."]
 * ```
 *
 * 解析不出东西返回空数组，由调用方决定报什么错——这里不抛。
 */
export function parseModelList(body: unknown): string[] {
  if (body == null) return []
  const root = body as Record<string, unknown>
  const candidates: unknown[] = [root?.data, root?.models, body]
  for (const candidate of candidates) {
    if (!Array.isArray(candidate)) continue
    const ids = candidate.map(modelIdOf).filter(id => id.length > 0)
    if (ids.length > 0) return [...new Set(ids)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
  }
  return []
}
