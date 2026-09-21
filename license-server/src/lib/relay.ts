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
