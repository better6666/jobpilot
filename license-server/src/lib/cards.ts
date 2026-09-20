/**
 * 卡密纯逻辑：生成、校验、时间计算。
 * 不依赖任何 Cloudflare API，可在 Node 下单测（见 test/cards.test.ts）。
 */

// 32 个字符：去掉易混淆的 I / O / 0 / 1，人工抄写卡密时不容易错
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const KEY_RE = /^GK-[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$/

function randomBytes(length: number): Uint8Array {
  const buf = new Uint8Array(length)
  crypto.getRandomValues(buf)
  return buf
}

/** 形如 GK-XXXX-XXXX-XXXX-XXXX，16 位随机字符 ≈ 80 bit 熵 */
export function generateCardKey(): string {
  const groups: string[] = []
  for (let g = 0; g < 4; g++) {
    let group = ''
    for (let i = 0; i < 4; i++) {
      // 256 % 32 === 0，取模无偏差
      group += ALPHABET[randomBytes(1)[0] % ALPHABET.length]
    }
    groups.push(group)
  }
  return 'GK-' + groups.join('-')
}

export function isValidCardKey(key: string): boolean {
  return KEY_RE.test(key)
}

export function normalizeCardKey(key: string): string {
  return key.trim().toUpperCase()
}

/** 32 字节随机数的 hex，64 字符 */
export function generateToken(): string {
  return Array.from(randomBytes(32), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** ISO 时间 + 天数 -> 新的 ISO 时间 */
export function addDays(iso: string, days: number): string {
  return new Date(new Date(iso).getTime() + days * 86400000).toISOString()
}

/** 距到期还剩几天（向上取整，已到期返回 0） */
export function daysLeft(expiresAt: string, now: Date = new Date()): number {
  const ms = new Date(expiresAt).getTime() - now.getTime()
  return Math.max(0, Math.ceil(ms / 86400000))
}
