package appapi

// adminHTML 是一起听的管理页：第一步扫码登录音乐平台，第二步生成邀请码发给朋友，
// 下面再看成员和房间。扫码复用 go-music-dl 的 /qr_login 接口，二维码由 /lt/qr.png 画出。
const adminHTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>一起听 · 管理</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--text:#1d1f23;--muted:#6b7280;--line:#e6e8eb;--accent:#e2364b;--accent-soft:#fdecee;--ok:#16a34a;--ok-soft:#e8f6ee;--warn:#b45309}
@media (prefers-color-scheme:dark){:root{--bg:#111214;--card:#1b1d21;--text:#e8e9eb;--muted:#9aa0a8;--line:#2c2f35;--accent:#ff5468;--accent-soft:#3a1d22;--ok:#4ade80;--ok-soft:#17301f;--warn:#fbbf24}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
.wrap{max-width:720px;margin:0 auto;padding:24px 16px 48px}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px}
h1{font-size:22px;margin:0}
header form{margin:0}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px;margin-bottom:16px}
.step{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:var(--accent);color:#fff;font-size:13px;font-weight:600;margin-right:8px}
h2{font-size:17px;margin:0 0 4px;display:flex;align-items:center}
.hint{color:var(--muted);font-size:13px;margin:0 0 16px}
.addr{display:flex;gap:8px;align-items:center;background:var(--bg);border-radius:10px;padding:10px 12px;font-family:ui-monospace,Menlo,monospace;font-size:14px;word-break:break-all}
.addr span{flex:1}
button{font:inherit;border:0;border-radius:9px;padding:8px 14px;cursor:pointer;background:var(--accent);color:#fff;white-space:nowrap}
button.ghost{background:transparent;color:var(--muted);border:1px solid var(--line)}
button:disabled{opacity:.5;cursor:default}
.accounts{display:grid;gap:10px}
.acct{display:flex;align-items:center;gap:12px;border:1px solid var(--line);border-radius:12px;padding:12px 14px}
.acct .name{flex:1;font-weight:600}
.badge{font-size:12px;padding:2px 8px;border-radius:99px;background:var(--bg);color:var(--muted);font-weight:400;margin-left:6px}
.badge.on{background:var(--ok-soft);color:var(--ok)}
.badge.admin{background:var(--accent-soft);color:var(--accent)}
.policies{display:grid;gap:8px;margin-bottom:12px}
.policy{display:flex;align-items:flex-start;gap:10px;border:1px solid var(--line);border-radius:10px;padding:10px 12px;color:var(--text);font-size:15px;cursor:pointer}
.policy input{margin-top:5px}
.policy.off{opacity:.5;cursor:default}
.qrbox{display:none;text-align:center;border:1px dashed var(--line);border-radius:12px;padding:16px;margin-top:12px}
.qrbox.show{display:block}
.qrbox img{width:220px;height:220px;background:#fff;border-radius:8px;padding:8px}
.qrstatus{margin-top:8px;font-weight:600}
.qrstatus.ok{color:var(--ok)}.qrstatus.warn{color:var(--warn)}
.row{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-bottom:12px}
label{font-size:13px;color:var(--muted);display:flex;align-items:center;gap:6px}
select{font:inherit;padding:6px 8px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--text)}
.list{display:grid;gap:8px}
.item{display:flex;align-items:center;gap:10px;border:1px solid var(--line);border-radius:10px;padding:10px 12px}
.item .main{flex:1;min-width:0}
.item .sub{color:var(--muted);font-size:12px}
.code{font-family:ui-monospace,Menlo,monospace;font-size:18px;letter-spacing:2px;font-weight:600}
.used{opacity:.5}
.empty{color:var(--muted);font-size:13px;text-align:center;padding:12px}
.toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:var(--text);color:var(--bg);padding:8px 16px;border-radius:99px;font-size:14px;opacity:0;transition:opacity .2s;pointer-events:none}
.toast.show{opacity:1}
</style></head><body><div class="wrap">

<header><h1>一起听 · 管理</h1><form method="post" action="../logout"><button class="ghost" type="submit">退出登录</button></form></header>

<div class="card">
  <h2>App 连接地址</h2>
  <p class="hint">朋友在一起听 App 里填这个地址。用临时隧道时，重启后地址会变。</p>
  <div class="addr"><span id="origin"></span><button class="ghost" onclick="copy(location.origin)">复制</button></div>
</div>

<div class="card">
  <h2><span class="step">1</span>登录音乐平台账号</h2>
  <p class="hint">用你的会员账号扫码登录。朋友播放时由服务器用这个账号解析歌曲，他们自己不需要会员。推荐先登录网易云音乐。</p>
  <div class="accounts" id="accounts"></div>
  <div class="qrbox" id="qrbox">
    <div id="qrtitle" style="margin-bottom:8px"></div>
    <img id="qrimg" alt="登录二维码">
    <div class="qrstatus" id="qrstatus"></div>
    <div style="margin-top:10px"><button class="ghost" onclick="stopQR()">取消</button></div>
  </div>
</div>

<div class="card">
  <h2><span class="step">2</span>邀请朋友</h2>
  <p class="hint">生成邀请码后点“复制邀请”，把连接地址和邀请码一起发给朋友。每个邀请码按次数使用，用完失效。</p>
  <div class="row">
    <label>可用人数<select id="uses"><option value="1">1 人</option><option value="5">5 人</option><option value="10">10 人</option><option value="0">不限</option></select></label>
    <label>有效期<select id="ttl"><option value="24">1 天</option><option value="168" selected>7 天</option><option value="0">永久</option></select></label>
    <button onclick="createInvite()">生成邀请码</button>
  </div>
  <div class="list" id="invites"></div>
</div>

<div class="card">
  <h2>放映厅</h2>
  <p class="hint">放映厅里谁能暂停、拖进度、切下一个。所有房间都用这里的默认档位，管理员可以在房间里改；锁定后管理员也不能改。不管哪一档，所有人都能投票跳过，没权限的人点了会变成请求。</p>
  <div class="policies" id="policies"></div>
  <div class="row">
    <label><input type="checkbox" id="locked" onchange="saveVideo()">锁定档位</label>
  </div>
  <div class="sub" id="policyHint" style="color:var(--muted);font-size:13px"></div>
</div>

<div class="card">
  <h2>成员</h2>
  <p class="hint">停用后对方立即被移出房间，原来的登录失效。管理员在放映厅里任何档位下都能控制进度，也能改房间档位。</p>
  <div class="list" id="members"></div>
</div>

<div class="card">
  <h2>房间</h2>
  <p class="hint">房间由成员在 App 里创建，所有人离开 10 分钟后自动解散。</p>
  <div class="list" id="rooms"></div>
</div>

</div><div class="toast" id="toast"></div>
<script>
var $ = function (id) { return document.getElementById(id); };
var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); };
function api(path, opts) {
  return fetch(path, opts).then(function (r) {
    if (r.status === 401) { location.href = '../login'; throw new Error('未登录'); }
    return r.json();
  });
}
function post(path, body) {
  return api(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body || {})});
}
function toast(msg) {
  var t = $('toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.timer); toast.timer = setTimeout(function () { t.classList.remove('show'); }, 1800);
}
function copy(text) {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(function () { toast('已复制'); });
    return;
  }
  var ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
  document.execCommand('copy'); ta.remove(); toast('已复制');
}
function fmtTime(s) { return s ? new Date(s).toLocaleString('zh-CN', {hour12: false}) : ''; }

// ---------- 平台账号与扫码 ----------
var qr = null;
function loadAccounts() {
  api('accounts').then(function (d) {
    $('accounts').innerHTML = d.accounts.map(function (a) {
      var badge = a.loggedIn ? '<span class="badge on">已登录</span>' : '<span class="badge">未登录</span>';
      var btns = a.logins.map(function (l) {
        var label = a.loggedIn && a.logins.length === 1 ? '重新扫码' : l.label;
        return '<button onclick="startQR(\'' + l.qrSource + '\',\'' + esc(l.app) + '\')">' + esc(label) + '</button>';
      }).join(' ');
      if (a.loggedIn) btns = '<button class="ghost" onclick="logout(\'' + a.source + '\',\'' + esc(a.name) + '\')">退出</button> ' + btns;
      return '<div class="acct"><div class="name">' + esc(a.name) + badge + '</div>' + btns + '</div>';
    }).join('');
  });
}
function startQR(source, app) {
  stopQR();
  $('qrbox').classList.add('show');
  $('qrbox').scrollIntoView({behavior: 'smooth', block: 'center'});
  $('qrtitle').textContent = '用' + app + '扫一扫';
  $('qrimg').removeAttribute('src');
  setQRStatus('正在生成二维码…');
  post('../qr_login/' + source).then(function (s) {
    if (s.error || !s.key) { setQRStatus('生成失败：' + (s.error || '未知错误'), 'warn'); return; }
    $('qrimg').src = s.image_url || ('qr.png?text=' + encodeURIComponent(s.url));
    setQRStatus('等待扫码');
    qr = {source: source, key: s.key, busy: false, errors: 0, timer: setInterval(pollQR, 2000)};
  }).catch(function (e) { setQRStatus('生成失败：' + e.message, 'warn'); });
}
// 查询偶尔会因为网络抖动返回 502，连续失败几次才算真的失败；上一次没回来就不再发新的。
function pollQR() {
  if (!qr || qr.busy) return;
  var cur = qr;
  cur.busy = true;
  api('../qr_login/' + cur.source + '?key=' + encodeURIComponent(cur.key)).then(function (r) {
    cur.busy = false;
    if (qr !== cur) return;
    if (r.error && !r.status) {
      if (++cur.errors < 5) return;
      clearInterval(cur.timer); qr = null;
      setQRStatus('登录失败：' + r.error, 'warn');
      return;
    }
    cur.errors = 0;
    switch (r.status) {
      case 'waiting': setQRStatus('等待扫码'); break;
      case 'scanned': setQRStatus('已扫码，请在手机上确认登录'); break;
      case 'success':
        clearInterval(cur.timer); qr = null;
        setQRStatus('登录成功', 'ok'); toast('登录成功');
        loadAccounts();
        setTimeout(function () { $('qrbox').classList.remove('show'); }, 1500);
        break;
      case 'expired':
        clearInterval(cur.timer); qr = null;
        setQRStatus('二维码已过期，请重新点“扫码登录”', 'warn'); break;
      default:
        if (r.error || r.status === 'failed') {
          clearInterval(cur.timer); qr = null;
          setQRStatus('登录失败：' + (r.message || r.error || ''), 'warn');
        }
    }
  }).catch(function () { cur.busy = false; });
}
function stopQR() {
  if (qr) { clearInterval(qr.timer); qr = null; }
  $('qrbox').classList.remove('show');
}
function setQRStatus(text, cls) { var el = $('qrstatus'); el.textContent = text; el.className = 'qrstatus ' + (cls || ''); }
function logout(source, name) {
  if (!confirm('退出' + name + '账号？退出后朋友将无法播放该平台的会员歌曲。')) return;
  post('accounts/' + source + '/logout').then(loadAccounts);
}

// ---------- 邀请码 ----------
function inviteText(code) {
  return '来一起听歌！\n服务器地址：' + location.origin + '\n邀请码：' + code;
}
function loadInvites() {
  api('invites').then(function (d) {
    var list = (d.invites || []).slice().sort(function (a, b) { return new Date(b.createdAt) - new Date(a.createdAt); });
    if (!list.length) { $('invites').innerHTML = '<div class="empty">还没有邀请码</div>'; return; }
    var now = Date.now();
    $('invites').innerHTML = list.map(function (i) {
      var expired = i.expiresAt && new Date(i.expiresAt).getTime() < now;
      var usedUp = i.maxUses > 0 && i.uses >= i.maxUses;
      var dead = expired || usedUp;
      var state = usedUp ? '已用完' : expired ? '已过期' : (i.expiresAt ? fmtTime(i.expiresAt) + ' 过期' : '永久有效');
      var uses = '已加入 ' + i.uses + (i.maxUses ? ' / ' + i.maxUses : '') + ' 人';
      return '<div class="item' + (dead ? ' used' : '') + '"><div class="main"><div class="code">' + esc(i.code) + '</div>' +
        '<div class="sub">' + uses + ' · ' + state + '</div></div>' +
        (dead ? '' : '<button onclick="copy(inviteText(\'' + esc(i.code) + '\'))">复制邀请</button>') + '</div>';
    }).join('');
  });
}
function createInvite() {
  post('invites', {maxUses: +$('uses').value, ttlHours: +$('ttl').value}).then(function (inv) {
    loadInvites();
    if (inv.code) { copy(inviteText(inv.code)); toast('已生成并复制邀请'); }
  });
}

// ---------- 放映厅 ----------
var POLICIES = [
  ['free', '自由', '所有人都能直接控制'],
  ['requester', '点播人做主', '点了当前视频的人和管理员能直接控制'],
  ['admins', '管理员做主', '只有管理员能直接控制']
];
var video = {policy: 'requester', locked: false, hasAdmins: false};
function renderVideo() {
  $('policies').innerHTML = POLICIES.map(function (p) {
    var off = p[0] === 'admins' && !video.hasAdmins;
    return '<label class="policy' + (off ? ' off' : '') + '"><input type="radio" name="policy" value="' + p[0] + '"' +
      (video.policy === p[0] ? ' checked' : '') + (off ? ' disabled' : '') + ' onchange="saveVideo()">' +
      '<div><b>' + p[1] + '</b>' + (p[0] === 'requester' ? '<span class="badge">推荐</span>' : '') +
      '<div class="sub">' + p[2] + (off ? '。先在下面的成员列表里设置管理员' : '') + '</div></div></label>';
  }).join('');
  $('locked').checked = video.locked;
  $('policyHint').textContent = video.hasAdmins ? '' : '还没有管理员：现在没人能在房间里改档位，所有放映厅都按默认档位走。';
}
function loadVideo() { api('video_settings').then(function (d) { video = d; renderVideo(); }); }
function saveVideo() {
  var picked = document.querySelector('input[name=policy]:checked');
  api('video_settings', {method: 'PUT', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({policy: picked ? picked.value : video.policy, locked: $('locked').checked})})
    .then(function (d) { if (d.error) { toast(d.error); loadVideo(); return; } video = d; renderVideo(); toast('已保存'); });
}
function setAdmin(id, admin) {
  post('members/' + id + '/admin', {admin: admin}).then(function (d) {
    if (d.error) { toast(d.error); return; }
    if (d.policyReverted) toast('已经没有管理员，默认档位改回了「点播人做主」');
    loadMembers(); loadVideo();
  });
}

// ---------- 成员与房间 ----------
function loadMembers() {
  api('members').then(function (d) {
    var list = d.members || [];
    if (!list.length) { $('members').innerHTML = '<div class="empty">还没有人加入</div>'; return; }
    $('members').innerHTML = list.map(function (m) {
      return '<div class="item' + (m.disabled ? ' used' : '') + '"><div class="main"><b>' + esc(m.nickname) + '</b>' +
        (m.admin ? '<span class="badge admin">管理员</span>' : '') +
        (m.disabled ? '<span class="badge">已停用</span>' : '') +
        (muted(m) ? '<span class="badge">禁言中' + (new Date(m.mutedUntil).getFullYear() >= 2100 ? '' : ' 到 ' + fmtTime(m.mutedUntil)) + '</span>' : '') +
        '<div class="sub">' + fmtTime(m.createdAt) + ' 加入</div></div>' +
        '<button class="ghost" onclick="setAdmin(\'' + esc(m.userId) + '\',' + !m.admin + ')">' + (m.admin ? '取消管理员' : '设为管理员') + '</button>' +
        '<button class="ghost" onclick="setMute(\'' + esc(m.userId) + '\',' + (muted(m) ? 0 : -1) + ')">' + (muted(m) ? '解除禁言' : '禁言') + '</button>' +
        '<button class="ghost" onclick="setDisabled(\'' + esc(m.userId) + '\',' + !m.disabled + ',\'' + esc(m.nickname) + '\')">' + (m.disabled ? '恢复' : '停用') + '</button></div>';
    }).join('');
  });
}
function muted(m) { return m.mutedUntil && new Date(m.mutedUntil) > new Date(); }
function setMute(id, minutes) {
  post('members/' + id + '/mute', {minutes: minutes}).then(function (d) {
    if (d.error) { toast(d.error); return; }
    toast(minutes ? '已禁言，管理员或你解除前不能发弹幕' : '已解除禁言');
    loadMembers();
  });
}
function setDisabled(id, disabled, name) {
  if (disabled && !confirm('停用 ' + name + '？')) return;
  post('members/' + id + '/disabled', {disabled: disabled}).then(function () { loadMembers(); loadRooms(); });
}
function loadRooms() {
  api('rooms').then(function (d) {
    var list = d.rooms || [];
    if (!list.length) { $('rooms').innerHTML = '<div class="empty">现在没有房间</div>'; return; }
    $('rooms').innerHTML = list.map(function (r) {
      var np = r.nowPlaying ? '正在播放：' + esc(r.nowPlaying.title) + ' - ' + esc((r.nowPlaying.artists || []).join(' / ')) : '没有在播放';
      return '<div class="item"><div class="main"><b>' + (r.kind === 'video' ? '放映厅 ' : '房间 ') + esc(r.roomCode) + '</b><span class="badge">' + r.onlineCount + ' / ' + r.memberCount + ' 人在线</span>' +
        '<div class="sub">房主 ' + esc(r.ownerNickname) + ' · ' + np + '</div></div></div>';
    }).join('');
  });
}

$('origin').textContent = location.origin;
loadAccounts(); loadInvites(); loadVideo(); loadMembers(); loadRooms();
setInterval(function () { loadMembers(); loadRooms(); }, 10000);
</script></body></html>`
