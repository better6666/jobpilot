/**
 * 激活门：没卡的用户打开任何界面都先送去激活页。
 *
 * 和服务端 HealthController 的根路径跳转是两道：
 *   服务端管"打开软件看到的第一个页面"；
 *   这里管"之后在界面里点来点去、或者直接输了 /delivery.html 的情况"。
 *
 * 判据用 /api/license/status 的 state，只挡三种"确实需要一张卡"的状态：
 * UNACTIVATED / EXPIRED / REVOKED / DEVICE_MISMATCH。
 * NETWORK_BLOCKED（服务端暂时连不上）和 GRACE（宽限期）不挡——
 * 网络抖动不该把人锁在界面外，那两种状态下投递入口本来就会返回 402，
 * 用户照样能看到自己的配置和记录。
 */
(function () {
  // 激活页和会员页必须放行：没激活的用户正是要看到这两页才能买卡/激活，
  // 把它们也拦去激活页就成了死循环——永远看不到价格
  if (location.pathname === '/license.html' || location.pathname === '/plans.html') return;

  fetch('/api/license/status')
    .then(r => r.json())
    .then(json => {
      const s = (json && json.data) || {};
      if (!s.enabled) return;                 // 自用模式，不校验
      if (['UNACTIVATED', 'EXPIRED', 'REVOKED', 'DEVICE_MISMATCH'].indexOf(s.state) < 0) return;
      location.replace('/license.html');
    })
    .catch(() => { /* 问不到状态就不拦，页面自己的逻辑会处理 */ });
})();
