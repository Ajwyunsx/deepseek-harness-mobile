/* dsh-mobile 注入脚本：处理 SPA 重渲染与软键盘。
   幂等：重复执行不会叠加副作用。 */
(function () {
  'use strict';
  if (window.__dshMobileInjected) return;
  window.__dshMobileInjected = true;

  // 老 WebView（Chrome < 116，Android 12 及以下的系统 WebView 常见）
  // 没有 AbortSignal.any/timeout，dsh 的工作区选择器等会直接抛
  // "AbortSignal.any is not a function"（issue #2/#4）。补最小实现。
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var ctrl = new AbortController();
      for (var i = 0; i < signals.length; i++) {
        var s = signals[i];
        if (!s) continue;
        if (s.aborted) { ctrl.abort(s.reason); return ctrl.signal; }
        s.addEventListener('abort', (function (sig) {
          return function () { ctrl.abort(sig.reason); };
        })(s), { once: true });
      }
      return ctrl.signal;
    };
  }
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (ms) {
      var ctrl = new AbortController();
      setTimeout(function () {
        try { ctrl.abort(new DOMException('The operation timed out.', 'TimeoutError')); }
        catch (e) { ctrl.abort(); }
      }, ms);
      return ctrl.signal;
    };
  }

  // SPA 路由切换 / 主题切换后确保 viewport 不被改回桌面宽度
  function ensureViewport() {
    var v = document.querySelector('meta[name=viewport]');
    var want = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';
    if (v && v.getAttribute('content') !== want) {
      v.setAttribute('content', want);
    }
  }

  // 详情面板三态门控：
  //  - 有实质内容 → .dsh-mobile-open，全屏覆盖层
  //  - 只有占位文案（"Click a tool row..."）→ .dsh-mobile-empty，整体隐藏。
  //    否则它会在 grid 里常驻占掉大半宽度，把消息流挤成窄条、文本逐词换行，
  //    卡片全被拉成一屏高——"看不到对话消息"的另一半根因。
  // dsh 0.1.5 把列从 _detailsCol 改名为 _rightbarCol，并加了稳定的
  // data-rightbar-col 属性；优先用属性，类名兜底，避免哈希改名再次失效。
  function updateDetailsCol() {
    var mobile = window.innerWidth <= 700;
    var cols = document.querySelectorAll('div[data-rightbar-col], div[class*="_rightbarCol"]');
    for (var i = 0; i < cols.length; i++) {
      var dc = cols[i];
      var text = (dc.innerText || '').trim();
      var placeholder = /click a tool row/i.test(text) || text.length < 30;
      var has = !placeholder && (text.length > 150 ||
        !!dc.querySelector('pre, code, table, img, video, canvas, textarea, [class*="_code"], [class*="_terminal"]'));
      // 上游把右栏收进 0px 轨道时帧上带 data-rightbar-collapsed：此时列内可能
      // 仍留着实质内容，不能据此弹全屏（会平白盖住对话）——交给上游保持隐藏。
      var upstreamCollapsed = !!dc.closest('[data-rightbar-collapsed]');
      dc.classList.toggle('dsh-mobile-open', mobile && has && !upstreamCollapsed);
      dc.classList.toggle('dsh-mobile-empty', mobile && (!has || upstreamCollapsed));
    }
  }

  // 统一找 dsh 侧栏开关：优先按类名片段（_toggle），aria-label 文案做兜底
  // （dsh 更新或语言切换后 aria-label 可能变，类名更稳）。
  function findSidebarToggle() {
    return document.querySelector('div[class*="_sidebarCol"] button[class*="_toggle"]') ||
      document.querySelector('div[class*="_sidebarCol"] button[aria-label*="sidebar" i]') ||
      document.querySelector('div[class*="_sidebarCol"] button[class*="_iconButton"]');
  }

  // dsh 侧栏的真实内部状态（收起时内层根节点带 _collapsed 类）。
  // 绝不能看宽度（收起态被我们的 CSS display:none，宽度恒 0），
  // 也不能看 body 的 dsh-mobile-drawer 类——那是我们派生的，有 300ms 防抖滞后，
  // 据此二次翻转 toggle 会在真机慢渲染下把刚打开的瞬间又关掉（"按钮点不了"）。
  function sidebarIsOpen() {
    var col = document.querySelector('div[class*="_sidebarCol"]');
    if (!col) return false;
    return !col.querySelector('[class*="_collapsed"]');
  }
  // 用户意图时间戳：主动打开（lastOpenIntent）取消正在进行的强制收起，
  // 主动收起（lastCloseIntent）阻止打开的延迟补开——两路异步操作不再互相打架
  // （旧行为：点会话行后的 6 秒强制收起窗内再点汉堡，抽屉被反复开了又关，
  //  看着像"触控失灵/闪跳"）。
  var lastOpenIntent = 0;
  var lastCloseIntent = 0;
  // 仅在确认当前是收起态时才点 toggle 展开；展开失败（点击没生效）最多补一次。
  function openSidebar(attemptsLeft) {
    lastOpenIntent = Date.now();
    if (sidebarIsOpen()) { updateSidebarDrawer(); return; }
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(function () {
      updateSidebarDrawer();
      // 700ms 内用户主动收过（点遮罩/选会话）就不再补开
      if (!sidebarIsOpen() && attemptsLeft > 0 && Date.now() - lastCloseIntent > 900) openSidebar(attemptsLeft - 1);
    }, 700);
  }
  // 仅在确认当前是展开态时才点 toggle 收起。
  function closeSidebar() {
    if (!sidebarIsOpen()) { updateSidebarDrawer(); return; }
    lastCloseIntent = Date.now();
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(updateSidebarDrawer, 300);
  }
  // 在时间窗内持续强制收起：dsh 导航后会按持久化状态把侧栏重新展开，
  // 且展开可能发生在导航结束后 1~2 秒（真机更慢），点一次 toggle 可能白收。
  // 窗内每 400ms 检查一次，只在确认展开态时才点 toggle（绝不盲翻）。
  // 窗内用户主动打开（lastOpenIntent 晚于窗口起点）立即让路。
  function enforceClosed(until, start) {
    if (start === undefined) start = Date.now();
    if (Date.now() > until) return;
    if (lastOpenIntent > start) return;
    closeSidebar();
    setTimeout(function () { enforceClosed(until, start); }, 400);
  }

  // 左上角悬浮按钮：rail 不常驻，点它呼出侧栏抽屉（即触发 dsh 自带的 toggle）
  var railBtn = null;
  function ensureRailBtn() {
    if (railBtn && railBtn.isConnected) return railBtn;
    railBtn = document.getElementById('dsh-mobile-railbtn');
    if (!railBtn) {
      railBtn = document.createElement('button');
      railBtn.id = 'dsh-mobile-railbtn';
      railBtn.setAttribute('aria-label', 'Open sidebar');
      railBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/></svg>';
      railBtn.addEventListener('click', function () { openSidebar(2); });
      document.body.appendChild(railBtn);
    }
    return railBtn;
  }

  // 侧栏抽屉化：手机上侧栏展开（宽度 >100px）时不应挤压会话区，
  // 改为覆盖式抽屉 + 遮罩，点遮罩收起（点自带 toggle 按钮）。
  var maskEl = null;
  function ensureMask() {
    if (maskEl && maskEl.isConnected) return maskEl;
    maskEl = document.getElementById('dsh-mobile-drawer-mask');
    if (!maskEl) {
      maskEl = document.createElement('div');
      maskEl.id = 'dsh-mobile-drawer-mask';
      maskEl.addEventListener('click', function () { closeSidebar(); });
      document.body.appendChild(maskEl);
    }
    return maskEl;
  }
  function updateSidebarDrawer() {
    if (window.innerWidth > 700) {
      document.body.classList.remove('dsh-mobile-drawer');
      return;
    }
    var col = document.querySelector('div[class*="_sidebarCol"]');
    var open = false;
    if (col) {
      // 收起态 sidebarCol 被我们的 CSS display:none（宽度 0），不能看宽度；
      // dsh 收起时内层根节点带 _collapsed 类，以此为准。
      open = !col.querySelector('[class*="_collapsed"]');
    }
    document.body.classList.toggle('dsh-mobile-drawer', open);
    if (open) ensureMask();
    if (window.innerWidth <= 700) ensureRailBtn();
  }

  // token/耗时统计条打标（类名是构建哈希，按文本识别）：
  //  - 输入座汇总条："1 turns · 1 steps | LLM … | TTFT avg … tok/s"
  //  - 消息内计时行："04:37 · Ran for 12s · TTFT 1s · 169 tok/s"（_timeEnd）
  // 两种都是一行 nowrap，窄屏直接溢出屏幕右缘（右缘吊一截 "k/s"）。
  // 段间的 "|" 分隔符一并打标——折行后行尾会吊着孤立的 "|"，很割裂，CSS 隐藏。
  function tagStatsBar() {
    var els = document.querySelectorAll('div, span');
    var cand = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.classList.contains('dsh-mobile-stats')) continue;
      var t = el.textContent || '';
      if (t.length < 8 || t.length > 200) continue;
      if (el.querySelector('textarea, input, button, [class*="_card"]')) continue;
      var composerStats = /turns|steps/.test(t) && /tok\/s|TTFT|LLM/.test(t);
      var msgStats = t.length < 80 && /Ran for|TTFT/.test(t) && /tok\/s/.test(t);
      if (composerStats || msgStats) cand.push(el);
    }
    // 只打标最内层匹配者：外层容器（整条消息气泡）只是"包含"统计文本，
    // 打标会让它内部所有 span 变 inline-block nowrap，破坏正文排版。
    for (var c = 0; c < cand.length; c++) {
      var innermost = true;
      for (var d = 0; d < cand.length; d++) {
        if (d !== c && cand[c].contains(cand[d])) { innermost = false; break; }
      }
      if (innermost) cand[c].classList.add('dsh-mobile-stats');
    }
    var bars = document.querySelectorAll('.dsh-mobile-stats');
    for (var b = 0; b < bars.length; b++) {
      var kids = bars[b].querySelectorAll('*');
      for (var k = 0; k < kids.length; k++) {
        if ((kids[k].textContent || '').trim() === '|') {
          kids[k].classList.add('dsh-mobile-stats-sep');
        }
      }
    }
  }

  // 弹层水平钳位：dsh 桌面定位的弹层（菜单/气泡，如模型选择器 right:0 对齐
  // 触发器右缘）在手机窄屏会整层被裁出左右边缘。超界时改写 left/right 拉回屏内；
  // dsh 重渲染会重置内联定位，下次超界再钳，不累积。
  function clampPopovers() {
    if (window.innerWidth > 700) return;
    var vw = window.innerWidth;
    // 注：_popover/_floating 类名在 rc.6/rc.7 DOM 中不存在（issue #5），
    // tooltip 气泡用 [role="tooltip"] 匹配而非类名
    var els = document.querySelectorAll(
      'div[class*="_menu"], div[class*="_dropdown"], [role="tooltip"]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = window.getComputedStyle(el);
      if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 10) continue;
      var op = el.offsetParent ? el.offsetParent.getBoundingClientRect() : { left: 0, right: vw };
      if (r.left < 6) {
        el.style.right = 'auto';
        el.style.left = Math.round(6 - op.left) + 'px';
      } else if (r.right > vw - 6) {
        el.style.left = 'auto';
        el.style.right = Math.round(op.right - (vw - 6)) + 'px';
      }
    }
  }

  // Tooltip 自动消失：dsh 的 Tooltip 组件靠 mouseenter/focus 弹出、
  // mouseleave/blur 才消失——触屏点按会合成 mouseenter/focus 把气泡弹出来，
  // 但要再点一下别处才消失（Bash 行上黏住"命令"黑块、统计条弹出全文本
  // 大黑框盖住输入区）。气泡允许弹出，但出现 2.5 秒后自动隐藏。
  // 只加内联 display:none、不删 DOM 节点：节点由 React 在 anchor
  // mouseleave/blur 时自行卸载，删节点会和 React 渲染脱同步。
  var TIP_AUTOHIDE_MS = 2500;
  function armTooltipAutohide() {
    var tips = document.querySelectorAll('[role="tooltip"]:not([data-dsh-tip-armed])');
    for (var i = 0; i < tips.length; i++) {
      var tip = tips[i];
      tip.setAttribute('data-dsh-tip-armed', '1');
      (function (el) {
        setTimeout(function () {
          el.style.setProperty('display', 'none', 'important');
        }, TIP_AUTOHIDE_MS);
      })(tip);
    }
  }

  // 消息流底部留白 = 输入座实际高度 + 16px 间隙。
  // 背景：输入座由 mobile.css 钉成 fixed 贴视口底（脱离文档流），消息流
  // 末尾需要等量留白才能滚到"最后一条消息在座上方"。
  // 历史教训（勿回退）：
  //  - 旧版把留白写成 scrollBody 的 padding-bottom：sticky 座的锚点会被
  //    滚动容器自身 padding 上顶，padding 多大座浮多高；且测量用
  //    sc.bottom - seat.top，seat.top 随 padding 移动 → 正反馈失控，
  //    运行态座高度变化时 composer 一路漂到页面中部。
  //  - inline 写 padding-bottom 不加 !important 会被 mobile.css 的
  //    !important 兜底规则压掉，动态补偿形同虚设。
  // 现方案：往 scrollBody 末尾追加独立间隔块（in-flow，高度稳定生效），
  // 测量只取座"高度"（随内容变化，不随留白/定位变化），切断反馈回路。
  // composer 汇总统计条（"N turns · N steps | LLM …"，tagStatsBar 打的
  // .dsh-mobile-stats 且不在消息 _flowItem 里）在部分 dsh 版本里不在
  // _composerSeat 内部，而是座下方文档流中的独立节点——座改 fixed 后它
  // 会被座盖住或落到视口外（真机截图：统计行贴屏幕底边被裁一半）。
  // 把它钉到视口最底（CSS .dsh-mobile-composer-stats），座的 bottom 上移
  // 条高。返回条高（在座内/不存在时返回 0）。测量只取"高度"，稳定无反馈。
  var pinnedStats = null;
  function pinComposerStats(comp) {
    var bar = null;
    var bars = document.querySelectorAll('div[class*="_centerCol"] .dsh-mobile-stats');
    for (var i = 0; i < bars.length; i++) {
      var b = bars[i];
      if (comp.contains(b)) continue;
      if (b.closest('div[class*="_flowItem"]')) continue;
      // 条根可能被重复打标（外层容器也是 .dsh-mobile-stats），取最外层
      while (b.parentElement && b.parentElement.classList &&
        b.parentElement.classList.contains('dsh-mobile-stats') &&
        !comp.contains(b.parentElement)) {
        b = b.parentElement;
      }
      bar = b;
      break;
    }
    if (pinnedStats && pinnedStats !== bar) {
      pinnedStats.classList.remove('dsh-mobile-composer-stats');
      pinnedStats = null;
    }
    if (!bar) {
      comp.style.removeProperty('bottom');
      return 0;
    }
    pinnedStats = bar;
    bar.classList.add('dsh-mobile-composer-stats');
    var h = Math.round(bar.getBoundingClientRect().height);
    comp.style.setProperty('bottom', h + 'px', 'important');
    return h;
  }

  var lastPad = -1;
  var spacerEl = null;
  function fitComposerOverlap() {
    var comp = document.querySelector('div[class*="_centerCol"] div[class*="_composerSeat"]');
    var sc = document.querySelector('div[class*="_centerCol"] [class*="_scrollBody"]');
    if (!comp || !sc) return;
    // 新对话 hero 页的座留在文档流里（不贴底），不需要补偿；
    // 从会话页导航过来时清掉旧间隔块/统计条钉住。
    if (comp.querySelector('div[class*="_composerHero"]')) {
      if (spacerEl && spacerEl.isConnected) spacerEl.remove();
      if (pinnedStats) {
        pinnedStats.classList.remove('dsh-mobile-composer-stats');
        pinnedStats = null;
      }
      lastPad = -1;
      return;
    }
    if (!spacerEl || !spacerEl.isConnected || spacerEl.parentElement !== sc) {
      spacerEl = document.getElementById('dsh-mobile-composer-spacer');
      if (!spacerEl) {
        spacerEl = document.createElement('div');
        spacerEl.id = 'dsh-mobile-composer-spacer';
        spacerEl.setAttribute('aria-hidden', 'true');
        // scrollBody 是 flex 列，禁收缩（mobile.css 有同名规则，这里双保险）
        spacerEl.style.flex = '0 0 auto';
      }
      sc.appendChild(spacerEl);
    }
    // dsh 重渲染可能往 scrollBody 末尾插新节点（如座外统计条），
    // 间隔块必须保持最后一个孩子，否则新内容会落到间隔块下方被裁
    if (sc.lastElementChild !== spacerEl) sc.appendChild(spacerEl);
    var statsH = pinComposerStats(comp);
    var want = Math.max(48, Math.round(comp.getBoundingClientRect().height) + statsH + 16);
    if (want !== lastPad) {
      lastPad = want;
      spacerEl.style.height = want + 'px';
      sc.style.scrollPaddingBottom = want + 'px';
    }
  }

  // 「打开配置文件」拦截：桌面版这个按钮调 settings.openDocument →
  // 容器里没有 xdg-open/编辑器，必报"无法打开配置文件"。改走原生桥
  // （DshNative.openConfig → ConfigEditorActivity 直接编辑容器里的
  // settings.yaml，dsh-settings-file 的 watcher 会热加载，无需重启）。
  // 捕获阶段拦截：React 17+ 的事件挂在根容器上，document 捕获阶段
  // stopPropagation 能拦住原 handler。桥不存在（开发调试）时放行原逻辑。
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var btn = t.closest('button, [role="button"]');
    if (!btn) return;
    var txt = (btn.textContent || '').trim();
    if (!/打开配置文件|Open configuration file/i.test(txt)) return;
    if (!window.DshNative || !window.DshNative.openConfig) return;
    ev.preventDefault();
    ev.stopPropagation();
    window.DshNative.openConfig();
  }, true);

  // 插件页「添加插件」：dsh web 只有插件配置/查看，没有安装入口
  // （桌面安装走 CLI：dsh plugin --profile web add <pkg>，转发 pnpm）。
  // 在插件 section 底部注入一行 输入框+按钮，经原生桥在容器里跑同一条 CLI。
  // 结果通过 window.__dshOnPluginInstallResult 异步回传（安装要联网，可能几分钟）。
  function ensurePluginAdder() {
    if (!window.DshNative || !window.DshNative.installPlugin) return;
    var dlg = document.querySelector('div[role="dialog"], div[class*="_dialog"]');
    if (!dlg) return;
    var heads = dlg.querySelectorAll('[class*="_heading"]');
    var sec = null;
    for (var i = 0; i < heads.length; i++) {
      var t = (heads[i].textContent || '').trim();
      if (t === '插件' || t === 'Plugins') {
        sec = heads[i].closest('div[class*="_section"]') || heads[i].parentElement;
        break;
      }
    }
    if (!sec || document.getElementById('dsh-mobile-plugin-adder')) return;

    var row = document.createElement('div');
    row.id = 'dsh-mobile-plugin-adder';
    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'npm 包名，如 dsh-plugin-xxx';
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('autocorrect', 'off');
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = '添加插件';
    var statusEl = document.createElement('div');
    statusEl.className = 'dsh-mobile-plugin-adder-status';
    btn.addEventListener('click', function () {
      var name = input.value.trim();
      if (!name) {
        statusEl.textContent = '请输入插件包名';
        return;
      }
      btn.disabled = true;
      input.disabled = true;
      statusEl.textContent = '安装中…（首次安装需联网下载 pnpm，可能要几分钟）';
      window.__dshOnPluginInstallResult = function (res) {
        btn.disabled = false;
        input.disabled = false;
        if (res && res.ok) {
          statusEl.textContent = '已安装：' + name + '。到 App 设置页点「重启服务」后生效。';
          input.value = '';
        } else {
          statusEl.textContent = '安装失败：\n' + ((res && res.output) || '未知错误').slice(-800);
        }
      };
      try {
        window.DshNative.installPlugin(name);
      } catch (e) {
        btn.disabled = false;
        input.disabled = false;
        statusEl.textContent = '调用原生桥失败：' + e;
      }
    });
    row.appendChild(input);
    row.appendChild(btn);
    row.appendChild(statusEl);
    sec.appendChild(row);
  }

  var scheduled = false;
  var observer = new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      ensureViewport();
      updateDetailsCol();
      updateSidebarDrawer();
      tagStatsBar();
      clampPopovers();
      fitComposerOverlap();
      ensurePluginAdder();
      armTooltipAutohide();
    }, 300);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  ensureViewport();
  updateDetailsCol();
  updateSidebarDrawer();
  tagStatsBar();
  clampPopovers();
  fitComposerOverlap();
  ensurePluginAdder();
  armTooltipAutohide();
  window.addEventListener('resize', updateSidebarDrawer);

  // 抽屉里点了会话/工作区行（_sessionRow 等）后自动收起抽屉。
  // dsh 桌面行为是选中不收起侧栏，手机上不收起就看不到会话内容。
  // 6 秒窗口内持续强制收起：dsh 导航后可能按持久化状态把侧栏重新展开，
  // 真机慢渲染时重展开可能拖到导航结束 2~3 秒后，点一次 toggle 容易白收。
  // 不依赖 body 的 drawer 类（有防抖滞后），点在侧栏里就生效。
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var col = t.closest('div[class*="_sidebarCol"]');
    if (!col) return;
    // 行内 ⋯ 按钮和弹出的菜单不是"选中行"：绝不能触发强制收起——
    // 否则菜单刚打开抽屉就被收掉、菜单被卸载，归档/重命名永远点不中，
    // 表现就是"点归档立即退回聊天界面但没归上"（issue #1）
    if (t.closest('[class*="_rowActions"], [class*="_menu"], [role="menu"], [role="menuitem"], [role="dialog"]')) {
      return;
    }
    if (t.closest('[class*="_sessionRow"], [class*="_workspaceRow"], [class*="_projectRow"], [class*="_newSession"], [data-slot^="sidebar.session"]')) {
      enforceClosed(Date.now() + 6000);
    }
  }, true);

  // 启动时若侧栏是持久化恢复的展开态，主动收掉——否则进 App 就是
  // 抽屉+遮罩糊脸，整个界面像盖了层黑纱（所有点击都被遮罩拦掉）。
  // 用户一旦主动开过抽屉就取消启动兜底，避免和用户抢。
  // 注意必须用 closest：点击目标通常是按钮里的 <svg>/<line> 子节点，
  // 直接比 id 会漏判，启动兜底照样把用户刚打开的抽屉收掉（"点了又自己关上"）。
  var startupEnforce = true;
  document.addEventListener('click', function (ev) {
    if (ev.target && ev.target.closest && ev.target.closest('#dsh-mobile-railbtn, #dsh-mobile-drawer-mask')) {
      startupEnforce = false;
    }
  }, true);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 1500); }, 1200);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 2500); }, 3500);

  // 手势触发：屏幕左缘右滑呼出抽屉；抽屉打开时在抽屉/遮罩上左滑收起。
  // 只在 touchend 判定（passive 监听，不打断页面滚动）：明确横向位移
  // （|dx|>64 且 |dy|<=50）才触发，竖向滚动/斜滑不误触。
  var drawerTouch = null;
  document.addEventListener('touchstart', function (ev) {
    drawerTouch = null;
    if (window.innerWidth > 700) return;
    var t = ev.touches[0];
    if (!t) return;
    if (document.body.classList.contains('dsh-mobile-drawer')) {
      if (ev.target && ev.target.closest && ev.target.closest('div[class*="_sidebarCol"], #dsh-mobile-drawer-mask')) {
        drawerTouch = { x: t.clientX, y: t.clientY, close: true };
      }
    } else if (t.clientX <= 24) {
      drawerTouch = { x: t.clientX, y: t.clientY, close: false };
    }
  }, { passive: true });
  document.addEventListener('touchend', function (ev) {
    if (!drawerTouch) return;
    var t = ev.changedTouches[0];
    var dx = t.clientX - drawerTouch.x;
    var dy = t.clientY - drawerTouch.y;
    var close = drawerTouch.close;
    drawerTouch = null;
    if (Math.abs(dy) > 50) return;
    if (!close && dx > 64) openSidebar(2);
    if (close && dx < -64) closeSidebar();
  }, { passive: true });

  // 兜底自检：observer 只在有 DOM 变更时跑，若某次竞态后页面静止，
  // 遮罩/抽屉状态可能卡住——每 2 秒按 dsh 真实状态校正一次派生类。
  setInterval(function () { updateSidebarDrawer(); fitComposerOverlap(); }, 2000);

  // 软键盘弹出时把聚焦的输入框滚进可视区
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () {
      var el = document.activeElement;
      if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
        setTimeout(function () {
          try { el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (e) {}
        }, 120);
      }
    });
  }

  // 注：不要在这里拦截 touchend 防双击缩放——viewport 已设 maximum-scale=1 +
  // user-scalable=no，系统层面已禁双击缩放；额外 preventDefault 会吞掉快速连点的
  // 第二次点击，导致按钮"点不了"。
})();
