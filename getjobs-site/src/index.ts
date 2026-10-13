/**
 * Get Jobs 下载站。
 *
 * 职责只有两件：渲染下载页、把安装包从 R2 转出来（统一域名，避免暴露
 * r2.dev 的裸桶地址，也方便以后加下载计数）。
 *
 * 安装包不放 Workers 静态资源：dmg 113MB、Windows 包也上百 MB，
 * 会撞静态资源体积上限。R2 由 binding 直接读，Worker 只做流式转发。
 */

export interface Env {
  DOWNLOADS: R2Bucket
  /** 卡密服务端（license-server），/manage、/admin/* 与 /api/* 全转给它 */
  LICENSE_ORIGIN?: string
  /** 会员页地址，页面上的"查看套餐"按钮指过去 */
  PLANS_URL?: string
  /** 卡密后台，页面上的"激活/续费"指过去 */
  LICENSE_URL?: string
}

interface Release {
  file: string
  label: string
  platform: 'mac' | 'win'
  arch?: string
  size: string
  available: boolean
}

interface Plan {
  plan: string
  name: string
  recommended: boolean
  durations: { days: number; price_cents: number }[]
  quotas: Record<string, number>
  features: Record<string, boolean>
}

const escapeHtml = (value: string) => value.replace(/[&<>"']/g, char =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!)

async function loadPlans(env: Env): Promise<Plan[]> {
  try {
    const origin = env.LICENSE_ORIGIN || 'https://jobpilot.better999.dpdns.org'
    const response = await fetch(new URL('/api/plans', origin), { signal: AbortSignal.timeout(4000) })
    if (!response.ok) return []
    const body = await response.json() as { data?: { plans?: Plan[] } }
    return Array.isArray(body.data?.plans) ? body.data.plans : []
  } catch { return [] }
}

/** 各平台安装包。available=false 时页面显示"即将提供"而不是死链 */
const RELEASES: Release[] = [
  { file: 'JobPilot-1.0.14.dmg', label: 'macOS 版', platform: 'mac',
    arch: 'Apple Silicon（arm64）', size: '115 MB', available: true },
  { file: 'JobPilot-1.0.14-windows.zip', label: 'Windows 版', platform: 'win',
    arch: 'Windows 10 / 11 64 位', size: '110 MB', available: true },
]

const VERSION = '1.0.14'
const UPDATED = '2026-10-13'

/**
 * 安装包的 SHA-256，页面上给用户校验用。
 * 必须声明在 page() 之前：const 不提升，模板字符串里引用了它们
 */
const SHA_MAC = '18f9ab68d39c3e3e28ac01e533d888854d426f2f45c343b7309c39f391c33bbf'
const SHA_WIN = '297738f8eab09ac638d7d74d9505795abf793b0e3f104c6e8b93f9dd26891026'

function page(env: Env, plans: Plan[]): string {
  const pricingCards = plans.map(plan => {
    const periods = plan.durations.map(period => `<span>${period.days} 天 <b>¥${(period.price_cents / 100).toFixed(2)}</b></span>`).join('')
    const q = plan.quotas
    const f = plan.features
    return `<article class="price-card${plan.recommended ? ' recommended' : ''}">
      <div class="price-label">${plan.recommended ? '最受推荐' : 'JOBPILOT PLAN'}</div>
      <h3>${escapeHtml(plan.name)}</h3>
      <p class="price-sub">${plan.plan === 'trial' ? '先体验求职投递流程' : plan.plan === 'advanced' ? '多方向求职与智能策略' : '日常求职与 AI 沟通'}</p>
      <div class="price-number">¥${((plan.durations[0]?.price_cents ?? 0) / 100).toFixed(2)}<small>起 · ${plan.durations[0]?.days ?? 0} 天</small></div>
      <div class="price-periods">${periods}</div>
      <div class="price-quotas"><b>每平台每日投递 ${q.max_daily_apply ?? '—'} 个</b><span>每日 AI 分析 ${q.max_daily_ai_analysis ?? '—'} 次</span><span>简历 ${q.max_resume_count ?? '—'} 份 · 岗位画像 ${q.max_job_profile_count ?? '—'} 个</span></div>
      <div class="price-features">${f.ai_match ? '<span>✓ AI 岗位匹配</span>' : ''}${f.auto_apply ? '<span>✓ 自动投递</span>' : ''}${f.advanced_filter ? '<span>✓ 高级筛选</span>' : ''}${f.ai_greeting ? '<span>✓ AI 招呼语</span>' : ''}${f.smart_apply ? '<span>✓ 智能投递策略</span>' : ''}${f.multi_resume ? '<span>✓ 多简历管理</span>' : ''}${f.ai_strategy ? '<span>✓ AI 策略</span>' : ''}</div>
    </article>`
  }).join('')
  const cards = RELEASES.map(r => `
    <article class="download-card">
      <div class="platform-icon ${r.platform}" aria-hidden="true">${r.platform === 'mac' ? '⌘' : '⊞'}</div>
      <div class="platform-copy">
        <span class="platform-kicker">${r.platform === 'mac' ? 'FOR MAC' : 'FOR WINDOWS'}</span>
        <h3>${r.label}</h3>
        <p>${r.arch}</p>
      </div>
      ${r.available
        ? `<a class="download-button" href="/download/${encodeURIComponent(r.file)}" download aria-label="下载 ${r.label}">下载 ${r.platform === 'mac' ? 'DMG' : 'ZIP'} <span aria-hidden="true">↗</span></a>
           <div class="download-meta">${r.size} <span>·</span> v${VERSION}</div>`
        : `<span class="download-button disabled">即将提供</span>`}
    </article>`).join('')

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#141b30">
<title>JobPilot 下载 · 让求职投递有条理</title>
<meta name="description" content="下载 JobPilot 原生桌面应用，支持 macOS 和 Windows。在本机设置求职条件、预演投递并查看每次记录。">
<style>
:root{color-scheme:light;--navy:#141b30;--ink:#1d2639;--muted:#69758b;--line:#e5e9f1;--blue:#4657c5;--pale:#f6f8fc;--white:#fff}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--pale);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}a{color:inherit}button,a{ -webkit-tap-highlight-color:transparent}a:focus-visible{outline:3px solid #a5b1ff;outline-offset:4px;border-radius:4px}
.container{width:min(1120px,calc(100% - 48px));margin:auto}
.topbar{height:72px;background:var(--navy);color:#fff;border-bottom:1px solid rgba(255,255,255,.1)}.topbar .container{height:100%;display:flex;align-items:center;justify-content:space-between}.brand{display:flex;align-items:center;gap:11px;font-size:17px;font-weight:800;letter-spacing:-.03em;text-decoration:none}.brand-mark{font-size:29px;line-height:1;color:#b9c3ff}.nav-link{color:#c2cbe0;text-decoration:none;font-size:13px;font-weight:600}.nav-link:hover{color:#fff}
.hero{background:var(--navy);color:#fff;overflow:hidden}.hero .container{display:grid;grid-template-columns:.92fr 1.08fr;gap:54px;align-items:center;min-height:490px;padding-top:62px;padding-bottom:68px}.eyebrow{display:inline-flex;gap:9px;align-items:center;font-size:12px;font-weight:800;letter-spacing:.14em;color:#bcc7ff}.eyebrow:before{content:"";width:7px;height:7px;border-radius:50%;background:#7ce0b4;box-shadow:0 0 0 5px rgba(124,224,180,.12)}h1{font-size:clamp(40px,5vw,66px);line-height:1.13;letter-spacing:-.065em;margin:25px 0 21px;font-weight:850}.hero p{color:#c0cadb;font-size:16px;line-height:1.85;max-width:510px;margin:0}.hero-actions{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-top:31px}.hero-cta{display:inline-flex;align-items:center;gap:24px;padding:14px 20px;background:#7585f3;color:#fff;border-radius:10px;font-size:14px;font-weight:750;text-decoration:none;box-shadow:0 12px 24px rgba(25,29,79,.25)}.hero-cta:hover{background:#8b9aff}.hero-secondary{font-size:13px;color:#c0cadb;text-decoration:none;border-bottom:1px solid #69748e;padding-bottom:3px}.version{font-size:12px;color:#8996af;margin-top:23px}
.app-preview{background:#f9faff;border:1px solid #77829d;border-radius:15px;box-shadow:0 28px 70px rgba(2,7,24,.42);transform:rotate(-2deg) translateX(12px);overflow:hidden;min-width:0}.preview-top{height:25px;background:#eceef5;display:flex;gap:5px;padding:9px 12px}.preview-top i{width:7px;height:7px;border-radius:50%;background:#c2c7d3}.preview-body{display:grid;grid-template-columns:112px 1fr;min-height:293px}.preview-sidebar{background:#1d2538;color:#abb6cd;padding:18px 10px;font-size:8px}.preview-logo{color:#fff;font-weight:800;font-size:11px;margin:0 0 30px 5px}.preview-nav{padding:9px 8px;border-radius:5px;margin:5px 0}.preview-nav.active{background:#4b5bc0;color:#fff}.preview-main{padding:21px 23px;color:#273248}.preview-main strong{font-size:17px}.preview-main small{display:block;color:#8791a3;font-size:8px;margin-top:3px}.preview-status{background:#fff;border:1px solid #e6e9f0;border-radius:8px;padding:11px 12px;margin-top:18px;display:flex;align-items:center;justify-content:space-between;font-size:9px}.preview-status b{color:#263854}.preview-pill{color:#47735f;background:#e6f4ed;border-radius:20px;padding:4px 7px}.preview-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:10px}.preview-metric{background:#fff;border:1px solid #e6e9f0;border-radius:7px;padding:10px 7px;color:#8b95a5;font-size:7px}.preview-metric b{display:block;color:#263854;font-size:16px;margin-bottom:4px}.preview-tabs{font-size:8px;font-weight:700;color:#4b5bc0;border-bottom:1px solid #e0e5ee;margin-top:21px;padding-bottom:9px}.preview-form{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px}.preview-form span{height:69px;background:#fff;border:1px solid #e6e9f0;border-radius:7px;padding:10px;font-size:8px;font-weight:700}.preview-form span:after{content:"关键词   陈列设计";display:block;color:#8d99ac;margin-top:14px;font-weight:400}.preview-form span+span:after{content:"预演模式   已开启"}
.section{padding:78px 0}.section-head{display:flex;justify-content:space-between;align-items:end;gap:24px;margin-bottom:25px}.section-kicker{color:var(--blue);letter-spacing:.15em;font-size:11px;font-weight:850}.section h2{font-size:31px;letter-spacing:-.04em;line-height:1.25;margin:10px 0 0}.section-intro{font-size:13px;color:var(--muted);line-height:1.65;margin:0;max-width:360px}.download-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.download-card{background:#fff;border:1px solid var(--line);border-radius:18px;padding:27px;display:grid;grid-template-columns:55px 1fr;column-gap:17px;box-shadow:0 8px 24px rgba(27,39,65,.035)}.platform-icon{width:54px;height:54px;border-radius:14px;display:grid;place-items:center;font-size:26px;font-weight:500;background:#e9eaf0;color:#1e2638}.platform-icon.win{background:#e6edff;color:#3857b7}.platform-copy{min-width:0}.platform-kicker{font-size:10px;letter-spacing:.13em;font-weight:800;color:#8995aa}.platform-copy h3{font-size:22px;margin:3px 0 4px;letter-spacing:-.035em}.platform-copy p{margin:0;color:var(--muted);font-size:12px}.download-button{grid-column:1/-1;margin-top:27px;border-radius:9px;background:var(--blue);color:#fff;text-decoration:none;display:flex;align-items:center;justify-content:space-between;padding:13px 16px;font-size:14px;font-weight:750;transition:background .18s,transform .18s}.download-button:hover{background:#3444ae;transform:translateY(-2px)}.download-button span{font-size:20px;line-height:1}.download-button.disabled{background:#e7eaf0;color:#8992a4}.download-meta{grid-column:1/-1;text-align:center;font-size:11px;color:#929bad;margin-top:11px}.download-meta span{padding:0 5px}.release-note{font-size:12px;color:#69758b;margin-top:20px;line-height:1.7}
.steps-section{background:#fff;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.steps-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-top:32px}.step{border-top:2px solid #dce2ee;padding-top:20px}.step-number{color:var(--blue);font-size:12px;font-weight:850;letter-spacing:.12em}.step h3{font-size:16px;margin:16px 0 9px}.step p{font-size:12px;color:var(--muted);line-height:1.8;margin:0}.step b{color:var(--ink)}
.pricing-section{background:#fff;border-top:1px solid var(--line)}.pricing-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.price-card{border:1px solid var(--line);border-radius:18px;padding:26px;background:#fff;box-shadow:0 8px 24px rgba(27,39,65,.035);display:flex;flex-direction:column;min-height:390px}.price-card.recommended{border:2px solid var(--blue);padding:25px}.price-label{font-size:11px;letter-spacing:.08em;font-weight:850;color:var(--blue)}.price-card h3{font-size:22px;margin:17px 0 4px}.price-sub{font-size:12px;color:var(--muted);margin:0}.price-number{font-size:36px;font-weight:850;letter-spacing:-.05em;margin:27px 0 17px}.price-number small{display:block;font-size:12px;font-weight:500;letter-spacing:0;color:var(--muted);margin-top:2px}.price-periods{display:grid;gap:10px}.price-periods span{display:flex;justify-content:space-between;font-size:13px;color:var(--muted)}.price-periods b{color:var(--ink)}.price-quotas{border-top:1px solid var(--line);margin-top:auto;padding-top:19px;display:grid;gap:7px;font-size:12px;color:var(--muted)}.price-quotas b{font-size:14px;color:var(--ink)}.price-features{display:flex;flex-wrap:wrap;gap:7px;margin-top:17px;min-height:23px}.price-features span{background:#eef1ff;color:#4555b4;border-radius:5px;padding:5px 7px;font-size:11px}.price-note{color:var(--muted);font-size:12px;line-height:1.7;margin:20px 0 0}
.details-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.detail-card{background:#fff;border:1px solid var(--line);border-radius:16px;padding:26px}.detail-card h3{margin:0 0 11px;font-size:17px}.detail-card p{margin:0;color:var(--muted);font-size:12px;line-height:1.85}.detail-card code{background:#f2f4f8;padding:2px 5px;border-radius:3px;color:#344260}.hash{margin-top:27px;padding:18px;background:#eef1f7;border-radius:10px;color:#627086;font-size:10px;line-height:1.85;overflow-wrap:anywhere}.hash b{color:#35425b}.resource-links{display:flex;gap:10px;flex-wrap:wrap;margin-top:25px}.resource-links a{font-size:13px;font-weight:700;color:var(--blue);text-decoration:none;border:1px solid #cdd4ee;background:#f9faff;border-radius:8px;padding:10px 14px}.resource-links a:hover{background:#edf0ff}
footer{background:var(--navy);color:#aebad0;padding:33px 0;font-size:11px;line-height:1.8}footer .container{display:flex;justify-content:space-between;gap:20px}footer b{color:#fff;font-size:14px}
@media(max-width:820px){.hero .container{grid-template-columns:1fr;padding-top:58px;padding-bottom:58px;gap:40px}.app-preview{max-width:620px;transform:none}.steps-grid{grid-template-columns:repeat(2,1fr)}.pricing-grid{grid-template-columns:1fr}.price-card{min-height:0}.section{padding:62px 0}}
@media(max-width:620px){.container{width:min(100% - 32px,1120px)}.topbar{height:60px}.hero .container{padding-top:49px;padding-bottom:45px}.hero p{font-size:14px}.app-preview{display:none}.section-head{display:block}.section-intro{margin-top:12px}.download-grid,.details-grid{grid-template-columns:1fr}.steps-grid{grid-template-columns:1fr;gap:19px}.section{padding:51px 0}.section h2{font-size:26px}footer .container{display:block}footer p{margin-bottom:0}}
</style>
</head>
<body>
<header class="topbar"><div class="container"><a class="brand" href="/"><span class="brand-mark">◇</span> JobPilot</a><a class="nav-link" href="#download">下载安装 <span aria-hidden="true">↗</span></a></div></header>
<main>
<section class="hero"><div class="container"><div><div class="eyebrow">原生桌面应用 · WINDOWS & MAC</div><h1>让求职投递<br>有条理地进行。</h1><p>在自己的电脑上设置岗位条件、先预演筛选结果，再启动投递。运行状态、投递记录与操作日志，随时看得见。</p><div class="hero-actions"><a class="hero-cta" href="#download">下载桌面版 <span aria-hidden="true">↓</span></a><a class="hero-secondary" href="#start">了解如何开始</a></div><div class="version">当前版本 v${VERSION} · 更新于 ${UPDATED}</div></div><div class="app-preview" role="img" aria-label="JobPilot 原生桌面工作台界面示意"><div class="preview-top"><i></i><i></i><i></i></div><div class="preview-body"><div class="preview-sidebar"><div class="preview-logo">◇ JobPilot</div><div class="preview-nav active">投递工作台</div><div class="preview-nav">AI 话术</div><div class="preview-nav">卡密管理</div><div class="preview-nav">会员套餐</div></div><div class="preview-main"><strong>投递工作台</strong><small>设置求职条件，查看任务运行状态</small><div class="preview-status"><b>准备就绪 · 空闲</b><span class="preview-pill">预演模式</span></div><div class="preview-metrics"><div class="preview-metric"><b>0</b>发现岗位</div><div class="preview-metric"><b>0</b>符合要求</div><div class="preview-metric"><b>0</b>已投递</div><div class="preview-metric"><b>0</b>已跳过</div></div><div class="preview-tabs">投递设置　　投递记录　　运行日志</div><div class="preview-form"><span>搜索范围</span><span>投递策略</span></div></div></div></div></div></section>
<section class="section" id="download"><div class="container"><div class="section-head"><div><div class="section-kicker">DOWNLOAD</div><h2>选择你的系统</h2></div><p class="section-intro">下载后直接在本机运行。两个版本都提供预演模式，方便先核对岗位与操作流程。</p></div><div class="download-grid">${cards}</div><p class="release-note">支持 macOS 12+（Apple Silicon）及 Windows 10 / 11（64 位）。Windows 下载为免安装 ZIP，解压后运行 JobPilot.exe。</p></div></section>
<section class="section pricing-section" id="plans"><div class="container"><div class="section-head"><div><div class="section-kicker">MEMBERSHIP</div><h2>套餐与价格</h2></div><p class="section-intro">先看每天的投递额度和 AI 能力，再选择适合自己的使用时长。投递额度按平台各算一份。软件不内置每小时上限，投递节奏由你的「岗位间隔秒数」决定。</p></div>${plans.length ? `<div class="pricing-grid">${pricingCards}</div><p class="price-note">价格与权益从套餐服务实时读取；实际可用额度以激活后的账户状态为准。完整功能对比可在桌面应用「会员套餐」查看。</p>` : '<p class="price-note">套餐暂时无法加载，请稍后刷新页面。</p>'}</div></section>
<section class="section steps-section" id="start"><div class="container"><div class="section-kicker">GET STARTED</div><h2>从设置到投递，四步就绪</h2><div class="steps-grid"><div class="step"><div class="step-number">01 / 安装</div><h3>打开桌面应用</h3><p>macOS 安装 DMG 中的应用；Windows 解压 ZIP 后启动 JobPilot.exe。</p></div><div class="step"><div class="step-number">02 / 激活</div><h3>输入卡密</h3><p>在「卡密管理」中完成激活，随后即可配置求职条件。</p></div><div class="step"><div class="step-number">03 / 设置</div><h3>填写目标岗位</h3><p>选择平台、关键词和筛选条件，按需配置 AI 话术与投递策略。</p></div><div class="step"><div class="step-number">04 / 预演</div><h3>先检查再开始</h3><p><b>保持预演模式开启</b>运行一次，核对岗位结果后再正式投递。</p></div></div></div></section>
<section class="section"><div class="container"><div class="section-head"><div><div class="section-kicker">GOOD TO KNOW</div><h2>安装与校验</h2></div><p class="section-intro">首次运行时，系统可能显示安全提示。以下是当前版本的处理方式。</p></div><div class="details-grid"><div class="detail-card"><h3>macOS</h3><p>如果提示无法验证开发者，请前往「系统设置 → 隐私与安全性」找到 JobPilot，选择「仍要打开」。当前安装包尚未完成 Apple 公证。</p></div><div class="detail-card"><h3>Windows</h3><p>解压后运行 <code>JobPilot.exe</code>。若 SmartScreen 提示「Windows 已保护你的电脑」，可选择「更多信息 → 仍要运行」。当前版本尚未提供代码签名。</p></div></div><div class="hash"><b>SHA-256 校验值</b><br>macOS　${SHA_MAC}<br>Windows　${SHA_WIN}</div><div class="resource-links"><a href="#plans">查看套餐与价格 ↑</a><a href="/manage" target="_blank" rel="noopener">卡密管理后台 ↗</a></div></div></section>
</main>
<footer><div class="container"><div><b>◇ JobPilot</b><br>在本机管理求职投递</div><p>招聘平台登录由本机浏览器完成。请遵守所用平台的规则与要求。</p></div></footer>
</body>
</html>`
}

/** macOS dmg 的 SHA-256，页面上给用户校验用 */
/**
 * 解析单段 Range 头。多段 Range（a-b,c-d）对安装包没意义，直接当不合法，
 * 让客户端整体重新下——反正它拿到 416 会这么干。
 */
function parseRange(header: string, size: number):
    { start: number; end: number } | null {
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!m) return null
  let start: number
  let end: number
  if (m[1] === '') {
    // 后缀形式：bytes=-500 表示最后 500 字节
    if (m[2] === '') return null
    const n = parseInt(m[2], 10)
    if (n <= 0) return null
    start = Math.max(0, size - n)
    end = size - 1
  } else {
    start = parseInt(m[1], 10)
    end = m[2] === '' ? size - 1 : parseInt(m[2], 10)
  }
  if (start >= size || end >= size || start > end) return null
  return { start, end }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)

    // 卡密后台和客户端接口共用下载站域名，避免用户端依赖第二个域名。
    // 只做转发不复制实现——后台里发的卡、改的套餐都落在同一个 D1 上，
    // 复制一份代码迟早两边不一致。ADMIN_KEY 存在页面 sessionStorage 里，
    // 由浏览器以 Authorization 头带上，代理原样透传即可
    if (url.pathname === '/manage' || url.pathname.startsWith('/admin/')
        || url.pathname.startsWith('/api/')
        || ['/activate', '/verify', '/report', '/unbind'].includes(url.pathname)) {
      const origin = env.LICENSE_ORIGIN || 'https://jobpilot.better999.dpdns.org'
      const upstream = new URL(url.pathname + url.search, origin)
      const headers = new Headers(request.headers)
      headers.set('host', new URL(origin).host)
      // 代理转发会让上游看到 worker 的 IP，后台若按 IP 限流会误伤
      headers.delete('cf-connecting-ip')
      const resp = await fetch(new Request(upstream.toString(), {
        method: request.method,
        headers,
        body: request.method === 'GET' || request.method === 'HEAD'
          ? undefined : await request.text(),
        redirect: 'manual',
      }))
      const out = new Headers(resp.headers)
      // 兜底去掉可能暴露上游域名的头
      out.delete('set-cookie-domain')
      return new Response(resp.body, {
        status: resp.status,
        statusText: resp.statusText,
        headers: out,
      })
    }

    // 安装包：从 R2 流式转出。支持 Range，断点续传才可用
    if (url.pathname.startsWith('/download/')) {
      const key = decodeURIComponent(url.pathname.slice('/download/'.length))
      if (!key || key.includes('..')) {
        return new Response('Not found', { status: 404 })
      }
      const hasRange = request.headers.has('range')
      // 先探一次拿大小：get 不读 body，成本可忽略。有了 size 才能自己判
      // 分段合法性——R2 对越界区间是直接抛异常变成 500 的，必须先拦掉
      const meta = await env.DOWNLOADS.get(key)
      if (!meta) {
        return new Response('安装包不存在：' + key, { status: 404 })
      }
      const baseHeaders = new Headers()
      meta.writeHttpMetadata(baseHeaders)
      baseHeaders.set('etag', meta.httpEtag)
      baseHeaders.set('content-disposition', `attachment; filename="${meta.key}"`)
      baseHeaders.set('cache-control', 'public, max-age=31536000, immutable')
      baseHeaders.set('accept-ranges', 'bytes')

      if (!hasRange) {
        return new Response(meta.body, { status: 200, headers: baseHeaders })
      }

      const range = parseRange(request.headers.get('range')!, meta.size)
      if (!range) {
        // 单段 Range 解析不了或越界：按规范回 416，客户端会整体重新下
        return new Response(null, {
          status: 416,
          headers: { 'content-range': `bytes */${meta.size}` },
        })
      }

      // 把原 Range 头交给 R2 切片，206 的头自己写——R2 的 object.range
      // 元数据不可靠（range 从 0 开始时它根本不标，客户端会以为不支持分段），
      // 而 {offset,length} 的对象形式在这套后端上直接抛，两条路都不全能信
      let sliced: R2ObjectBody | null = null
      try {
        sliced = await env.DOWNLOADS.get(key, { range: request.headers })
      } catch (e) {
        // 切片失败就整体给：下载成功比断点续传重要
        return new Response(meta.body, { status: 200, headers: baseHeaders })
      }
      const headers = new Headers(baseHeaders)
      headers.set('content-range',
        `bytes ${range.start}-${range.end}/${meta.size}`)
      headers.set('content-length', String(range.end - range.start + 1))
      return new Response(sliced?.body ?? meta.body, { status: 206, headers })
    }

    if (url.pathname === '/' || url.pathname === '/index.html') {
      return new Response(page(env, await loadPlans(env)), {
        headers: { 'content-type': 'text/html; charset=utf-8' },
      })
    }

    return new Response('Not found', { status: 404 })
  },
}
