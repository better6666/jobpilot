import assert from 'node:assert/strict'
import { normalizeRelayBaseUrl, maskKey } from '../src/lib/relay.ts'

// ---------------------------------------------------------------- 地址归一
// 下面每一组用例都和客户端 AiServiceTest#normalizeBaseUrl 一一对应。
// 两边行为必须一致：管理页存得下、代理调不通，这种问题肉眼看不出来。

assert.equal(normalizeRelayBaseUrl('https://api.openai.com'), 'https://api.openai.com/v1')
assert.equal(normalizeRelayBaseUrl('https://api.openai.com/v1/'), 'https://api.openai.com/v1')
assert.equal(normalizeRelayBaseUrl('https://relay.example.com/openai'), 'https://relay.example.com/openai')
assert.equal(normalizeRelayBaseUrl('https://relay.example.com/v1/chat/completions'), 'https://relay.example.com/v1')
assert.equal(normalizeRelayBaseUrl('https://relay.example.com/api/v3'), 'https://relay.example.com/api/v3')
assert.equal(normalizeRelayBaseUrl('HTTPS://api.openai.com'), 'https://api.openai.com/v1')
assert.equal(normalizeRelayBaseUrl('  https://api.openai.com/v1  '), 'https://api.openai.com/v1')

// 不合法的一律空串，调用方按"没配"处理
assert.equal(normalizeRelayBaseUrl('api.openai.com'), '')
assert.equal(normalizeRelayBaseUrl('ftp://x.com/v1'), '')
assert.equal(normalizeRelayBaseUrl('   '), '')
assert.equal(normalizeRelayBaseUrl(''), '')
assert.equal(normalizeRelayBaseUrl(null), '')
assert.equal(normalizeRelayBaseUrl(undefined), '')
assert.equal(normalizeRelayBaseUrl(42), '')

// 只砍一次 /chat/completions，别把自定义前缀里的同名目录也砍掉
assert.equal(normalizeRelayBaseUrl('https://x.com/openai/chat/completions'), 'https://x.com/openai')

// ---------------------------------------------------------------- 打码
// 和客户端 AiPropertiesTest#打码 对应

assert.equal(maskKey(null), '')
assert.equal(maskKey(''), '')
assert.equal(maskKey('   '), '')
// 短 key 留头尾等于全露，直接****
assert.equal(maskKey('sk-1234'), '****')
assert.equal(maskKey('12345678'), '****')
assert.equal(maskKey('  sk-1  '), '****')

const masked = maskKey('sk-test0011middlesecretmiddle0099zz')
assert.ok(masked.startsWith('sk-test'), masked)
assert.ok(masked.endsWith('99zz'), masked)
assert.ok(masked.includes('…'), masked)
assert.ok(!masked.includes('middlesecretmiddle'), masked)

// 长度刚好卡在边界上也要对：9 字符留 5 头 4 尾
assert.equal(maskKey('sk-abcde1'), 'sk-ab…cde1')
assert.equal(maskKey('123456789'), '12345…6789')

console.log('relay.test.ts 全部通过')
