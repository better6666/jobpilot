import assert from 'node:assert/strict'
import { normalizeRelayBaseUrl, maskKey, modelListPaths, parseModelList } from '../src/lib/relay.ts'

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

// ---------------------------------------------------------------- 模型列表路径
// 只试标准 /v1/models 的话，一半的中转站会 404，用户还以为 key 错了

assert.deepEqual(modelListPaths('https://api.openai.com/v1'), [
  'https://api.openai.com/v1/models',
  'https://api.openai.com/models'
])
// 自定义前缀：先试前缀自己那条，再兜底同一个域名的根
assert.deepEqual(modelListPaths('https://relay.com/openai'), [
  'https://relay.com/openai/models',
  'https://relay.com/models'
])
assert.deepEqual(modelListPaths('https://relay.com/api/v3'), [
  'https://relay.com/api/v3/models',
  'https://relay.com/models'
])
assert.deepEqual(modelListPaths(''), [])

// ---------------------------------------------------------------- 模型列表解析
// 各家中转站返回的形状比标准乱，按见到过的顺序兜

assert.deepEqual(parseModelList({ data: [{ id: 'gpt-4o' }, { id: 'gpt-4o-mini' }] }),
  ['gpt-4o', 'gpt-4o-mini'])
// 少数站把 data 做成字符串数组
assert.deepEqual(parseModelList({ data: ['deepseek-chat', 'deepseek-reasoner'] }),
  ['deepseek-chat', 'deepseek-reasoner'])
// 自建网关常用 models 这个键
assert.deepEqual(parseModelList({ models: [{ id: 'qwen-max' }, { id: 'qwen-plus' }] }),
  ['qwen-max', 'qwen-plus'])
assert.deepEqual(parseModelList({ models: ['glm-4'] }), ['glm-4'])
// 顶层直接是数组
assert.deepEqual(parseModelList([{ id: 'step-1' }]), ['step-1'])
assert.deepEqual(parseModelList(['step-1', 'step-2']), ['step-1', 'step-2'])
// 对象里认 id/model/name 三种键
assert.deepEqual(parseModelList({ data: [{ model: 'm1' }, { name: 'm2' }] }), ['m1', 'm2'])

// 空元素和空 id 要剔掉，别让用户在列表里点到空白项
assert.deepEqual(parseModelList({ data: [{ id: 'a' }, {}, { id: '' }, { id: '  ' }] }), ['a'])
// 去重 + 排序，列表才好找
assert.deepEqual(parseModelList({ data: [{ id: 'b' }, { id: 'a' }, { id: 'b' }] }), ['a', 'b'])
// data 是空数组时不该退回顶层，直接算没拉到
assert.deepEqual(parseModelList({ data: [] }), [])
assert.deepEqual(parseModelList({ data: {} }), [])
assert.deepEqual(parseModelList({}), [])
assert.deepEqual(parseModelList(null), [])
assert.deepEqual(parseModelList(undefined), [])
assert.deepEqual(parseModelList('not json'), [])
// 元素既不是字符串也没有可认的键
assert.deepEqual(parseModelList({ data: [{ foo: 'x' }, 42] }), [])

console.log('relay.test.ts 全部通过')
