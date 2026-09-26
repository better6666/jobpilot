/**
 * 套餐定义与 entitlement 装配。
 *
 * 价格、天数、功能开关、配额都来自 plans 表（后台可改），
 * 代码里只留"表读不到时的兜底默认值"——避免数据库空着时前端拿不到东西。
 *
 * 功能键与配额键是前后端共用的契约，改名要两边一起改：
 *   job_search / basic_filter / advanced_filter / ai_match / ai_explanation
 *   auto_apply / smart_apply / multi_resume / ai_greeting / analytics
 *   natural_language_rule / ab_test / ai_strategy
 * 配额：max_daily_ai_analysis / max_daily_apply / max_resume_count / max_job_profile_count
 *
 * max_daily_apply 的口径是<em>每个招聘平台各这么多</em>（客户端按 apply:平台名 分桶计数，
 * 见 jobpilot 的 DeliveryService#applyCounterKey）。写 300 就是 Boss 300、猎聘 300……
 */

export type Plan = 'trial' | 'standard' | 'advanced'

export interface PlanRow {
  plan: Plan
  name: string
  price_cents: number
  /** 套餐时长档位（30/60/90 天），JSON 数组 [{days, price_cents}]。
   *  空数组时退化成单档 duration_days + price_cents */
  durations: string
  /** 重点推荐的时长（天）；0 表示不推荐 */
  highlight_days: number
  duration_days: number
  features: string
  quotas: string
  recommended: number
  sort_order: number
  active: number
  updated_at: string
}

/** 兜底默认值：plans 表空着时用，保证新部署不至于前台全灰 */
export const DEFAULT_PLANS: PlanRow[] = [
  {
    plan: 'trial', name: '体验版', price_cents: 290, duration_days: 3, recommended: 0,
    durations: JSON.stringify([{ days: 3, price_cents: 290 }]), highlight_days: 0,
    features: JSON.stringify({
      job_search: true, basic_filter: true, advanced_filter: false,
      ai_match: true, ai_explanation: false, auto_apply: true, smart_apply: false,
      multi_resume: false, ai_greeting: false, analytics: true,
      natural_language_rule: false, ab_test: false, ai_strategy: false,
    }),
    quotas: JSON.stringify({ max_daily_ai_analysis: 20, max_daily_apply: 20,
      max_resume_count: 1, max_job_profile_count: 1 }),
    sort_order: 1, active: 1, updated_at: '',
  },
  {
    plan: 'standard', name: '标准版', price_cents: 1990, duration_days: 30, recommended: 0,
    durations: JSON.stringify([
      { days: 30, price_cents: 1990 }, { days: 60, price_cents: 2990 }, { days: 90, price_cents: 3990 },
    ]), highlight_days: 0,
    features: JSON.stringify({
      job_search: true, basic_filter: true, advanced_filter: true,
      ai_match: true, ai_explanation: false, auto_apply: true, smart_apply: false,
      multi_resume: false, ai_greeting: true, analytics: true,
      natural_language_rule: false, ab_test: false, ai_strategy: false,
    }),
    quotas: JSON.stringify({ max_daily_ai_analysis: 100, max_daily_apply: 120,
      max_resume_count: 1, max_job_profile_count: 3 }),
    sort_order: 2, active: 1, updated_at: '',
  },
  {
    plan: 'advanced', name: '进阶版', price_cents: 3290, duration_days: 30, recommended: 1,
    durations: JSON.stringify([
      { days: 30, price_cents: 3290 }, { days: 60, price_cents: 3990 }, { days: 90, price_cents: 5990 },
    ]), highlight_days: 60,
    features: JSON.stringify({
      job_search: true, basic_filter: true, advanced_filter: true,
      ai_match: true, ai_explanation: true, auto_apply: true, smart_apply: true,
      multi_resume: true, ai_greeting: true, analytics: true,
      natural_language_rule: true, ab_test: true, ai_strategy: true,
    }),
    quotas: JSON.stringify({ max_daily_ai_analysis: 400, max_daily_apply: 300,
      max_resume_count: 5, max_job_profile_count: 10 }),
    sort_order: 3, active: 1, updated_at: '',
  },
]

/** 重点推荐套餐：进阶版 60 天。UI 据此高亮那一张卡 */
export const HIGHLIGHT = { plan: 'advanced', duration_days: 60, price_cents: 3990 }

/** 真实售价表（分）。后台可改，这里只作兜底默认值 */
export const DEFAULT_DURATIONS: Record<Plan, Array<{ days: number; price_cents: number }>> = {
  trial: [{ days: 3, price_cents: 290 }],
  standard: [
    { days: 30, price_cents: 1990 },
    { days: 60, price_cents: 2990 },
    { days: 90, price_cents: 3990 },
  ],
  advanced: [
    { days: 30, price_cents: 3290 },
    { days: 60, price_cents: 3990 },
    { days: 90, price_cents: 5990 },
  ],
}

export function parseJson<T>(raw: unknown, fallback: T): T {
  if (!raw) return fallback
  // settings 中的套餐对象已经过一次 JSON.parse；D1 plans 表则仍是字符串。
  if (typeof raw === 'object') return raw as T
  if (typeof raw !== 'string') return fallback
  try {
    const v = JSON.parse(raw)
    return v && typeof v === 'object' ? (v as T) : fallback
  } catch {
    return fallback
  }
}

/** plans 表 → 前端可直接渲染的套餐列表（不暴露内部字段） */
export function toPublicPlan(row: PlanRow) {
  const durations = parseJson<Array<{ days: number; price_cents: number }>>(
    row.durations, [])
  return {
    plan: row.plan,
    name: row.name,
    price_cents: row.price_cents,
    duration_days: row.duration_days,
    highlight_days: row.highlight_days || 0,
    durations: durations.length > 0 ? durations : DEFAULT_DURATIONS[row.plan],
    features: parseJson<Record<string, boolean>>(row.features, {}),
    quotas: parseJson<Record<string, number>>(row.quotas, {}),
    recommended: row.recommended === 1,
  }
}
