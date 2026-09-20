import assert from 'node:assert/strict'
import {
  generateCardKey,
  generateToken,
  isValidCardKey,
  normalizeCardKey,
  addDays,
  daysLeft
} from '../src/lib/cards.ts'

// 卡密格式与归一化
for (let i = 0; i < 500; i++) {
  const key = generateCardKey()
  assert.ok(isValidCardKey(key), `格式错误: ${key}`)
  assert.ok(!/[IO01]/.test(key), `含易混淆字符: ${key}`)
  assert.equal(normalizeCardKey(`  ${key.toLowerCase()} `), key)
}

// token：64 位小写 hex
assert.equal(generateToken().length, 64)
assert.match(generateToken(), /^[0-9a-f]{64}$/)

// 时间计算
const now = new Date().toISOString()
assert.equal(daysLeft(addDays(now, 30)), 30)
assert.equal(daysLeft(addDays(now, 1)), 1)
assert.equal(daysLeft(addDays(now, -1)), 0)

// 唯一性：1000 张不重复
const keys = new Set(Array.from({ length: 1000 }, generateCardKey))
assert.equal(keys.size, 1000)

console.log('cards.test.ts 全部通过')
