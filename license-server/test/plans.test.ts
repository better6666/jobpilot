import assert from 'node:assert/strict'
import { parseJson, toPublicPlan, DEFAULT_PLANS } from '../src/plans.ts'

const configured = {
  ...DEFAULT_PLANS[1],
  name: '标准版 Plus',
  features: { advanced_filter: true, ai_greeting: true },
  quotas: { max_daily_apply: 60, max_daily_ai_analysis: 100 },
} as unknown as typeof DEFAULT_PLANS[number]

const fromSettings = toPublicPlan(configured)
assert.equal(fromSettings.features.advanced_filter, true)
assert.equal(fromSettings.features.ai_greeting, true)
assert.equal(fromSettings.quotas.max_daily_apply, 60)
assert.equal(fromSettings.durations.length, 3)

const fromTable = toPublicPlan(DEFAULT_PLANS[1])
assert.equal(fromTable.features.advanced_filter, true)
assert.equal(fromTable.quotas.max_daily_apply, 60)
assert.deepEqual(parseJson(undefined, {}), {})

console.log('plans.test.ts 全部通过')
