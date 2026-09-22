/**
 * 常驻卡密条：状态 + 剩余天数 + 自助退出卡密。
 *
 * 为什么需要它：卡密有效时入口门直接把人送进投递页，激活页上的「解绑本机」
 * 按钮根本看不见，想换卡只能从导航的「卡密管理」绕进去——绝大多数客户
 * 找不到。所以把状态和退出操作提到每个页面的顶部，永远可见。
 *
 * 退出是自助的，但有代价，所以文案必须说清楚：一卡一机，换绑终身 3 次、
 * 每次冷却 7 天。客户在别的机器上要用同一张卡，就得在这儿先退出。
 */
(function () {
  const REBIND_LIMIT = 3;
  const REBIND_COOLDOWN_DAYS = 7;

  function el(html) {
    const t = document.createElement('template');
    t.innerHTML = html.trim();
    return t.content.firstChild;
  }

  function statusText(s) {
    if (!s.enabled) return ['off', '自用模式'];
    switch (s.state) {
      case 'ACTIVE': return ['ok', '已激活'];
      case 'GRACE': return ['warn', '宽限期'];
      case 'EXPIRED': return ['bad', '已到期'];
      case 'REVOKED': return ['bad', '已失效'];
      case 'DEVICE_MISMATCH': return ['bad', '设备不符'];
      case 'NETWORK_BLOCKED': return ['warn', '服务端不可达'];
      default: return ['bad', '未激活'];
    }
  }

  function detail(s) {
    const parts = [];
    if (s.cardKeyMasked) parts.push(s.cardKeyMasked);
    if (s.type === 'time' || s.type === 'trial') {
      parts.push(s.remainingDays != null ? '剩余 ' + s.remainingDays + ' 天' : '时长未知');
    } else if (s.type === 'quota') {
      parts.push('剩余 ' + (s.quotaRemaining != null ? s.quotaRemaining : '?') + ' 次');
    }
    return parts.join(' · ') || s.message || '';
  }

  function render(s) {
    let bar = document.getElementById('cardBar');
    if (!bar) {
      bar = el('<div id="cardBar" class="cardbar"></div>');
      const wrap = document.querySelector('.wrap');
      (wrap || document.body).insertBefore(bar, (wrap || document.body).firstChild);
    }
    const [cls, text] = statusText(s);
    const canExit = s.enabled && (s.state === 'ACTIVE' || s.state === 'GRACE');

    bar.innerHTML =
      '<span class="dot ' + cls + '"></span>' +
      '<span class="ct">卡密：<b>' + text + '</b></span>' +
      '<span class="cd">' + escapeHtml(detail(s)) + '</span>' +
      (s.plan_name ? '<span class="cd">' + escapeHtml(s.plan_name) + '</span>' : '') +
      (canExit ? '<button id="btnExitCard" class="exit">退出卡密</button>' : '') +
      '<a href="/plans.html" class="up">会员套餐</a>' +
      '<a href="/license.html">激活页</a>';

    const btn = document.getElementById('btnExitCard');
    if (btn) btn.onclick = exitCard;
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function exitCard() {
    const ok = confirm(
      '退出卡密（解绑本机）\n\n' +
      '· 退出后本机立刻无法投递\n' +
      '· 会消耗一次换绑次数：终身 ' + REBIND_LIMIT + ' 次\n' +
      '· 每次退出后有 ' + REBIND_COOLDOWN_DAYS + ' 天冷却，冷却期内不能重新绑\n' +
      '· 换到另一台电脑上用时，才需要在这里先退出\n\n' +
      '确定退出？');
    if (!ok) return;
    const btn = document.getElementById('btnExitCard');
    if (btn) { btn.disabled = true; btn.textContent = '退出中…'; }
    fetch('/api/license/unbind', { method: 'POST' })
      .then(r => r.json())
      .then(json => {
        if (json.success) {
          alert('已退出卡密，本机回到未激活状态。\n需要重新激活请填写新的卡密。');
          location.replace('/license.html');
        } else {
          // 服务端拒了（最常见是换绑冷却期）。消息要原样带给用户——
          // 只说"退出失败"的话，客户不知道要等多久
          alert(json.message || '退出失败，请稍后重试');
          refresh();
        }
      })
      .catch(e => {
        alert('请求失败：' + e.message);
        refresh();
      });
  }

  function refresh() {
    fetch('/api/license/status')
      .then(r => r.json())
      .then(json => { if (json && json.data) render(json.data); })
      .catch(() => { /* 问不到就不显示，不挡页面 */ });
  }

  refresh();
  setInterval(refresh, 60000);
})();
