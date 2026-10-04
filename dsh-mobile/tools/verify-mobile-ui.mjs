#!/usr/bin/env node
/**
 * Behavioural gate for the mobile UI layer (mobile.css + inject.js).
 *
 * Those two assets are injected into the real dsh Web UI inside the Android
 * WebView, so the page here mirrors the DOM contract they rely on — the
 * `[class*="_xxx"]` fragments, dsh's `_collapsed` sidebar state, its sidebar
 * toggle button, and the centerCol / scrollBody / composerSeat structure — and
 * loads the REAL assets from dsh-mobile/app/src/main/assets/. Chromium provides
 * real layout and real CSS transitions, so both measured properties and
 * animation timing are observed rather than assumed.
 *
 * Scenarios:
 *   composer-overlap  the bottom spacer must keep compensating the fixed
 *                     composer after React rebuilds the scroll container
 *                     (issue #14)
 *   drawer-exit       closing the sidebar drawer must animate out while dsh's
 *                     own collapse happens only after the animation, instead of
 *                     reflowing the sidebar to its rail inside a still-visible
 *                     panel (issue #15)
 *
 * Usage: node dsh-mobile/tools/verify-mobile-ui.mjs [--headed] [--shots <dir>]
 */
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const repoRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
const assetDir = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'assets')
// Playwright lives in apps/web's devDependencies, not the repo root.
const { chromium } = require(require.resolve('playwright', { paths: [join(repoRoot, 'apps', 'web')] }))

const headed = process.argv.includes('--headed')
const shotsIndex = process.argv.indexOf('--shots')
const shotsDir = shotsIndex === -1 ? undefined : resolve(process.argv[shotsIndex + 1])

const failures = []
const check = (ok, message) => {
  console.log(`  ${ok ? '✓' : '✗'} ${message}`)
  if (!ok) failures.push(message)
}

/** translateX of a computed transform; 0 when the element carries none. */
function translateX(transform) {
  const match = /matrix\(([^)]+)\)/u.exec(transform)
  if (!match) return 0
  const parts = match[1].split(',').map(Number)
  return parts.length >= 6 ? parts[4] : 0
}

/** A phone viewport: Meizu 21 is 1080x2340 at ~2.75 dpr. */
const VIEWPORT = { width: 393, height: 851 }
const DEVICE_SCALE = 2.75

/** The page mirrors what the injector queries; the assets are the real ones. */
function pageHtml(css) {
  return `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>${css}</style>
<style>
  /* Minimal stand-in for the desktop shell the injector expects. */
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; background: #fff; font: 14px system-ui; }
  ._frame_test { display: grid; grid-template-columns: 320px minmax(0, 1fr); height: 100dvh; }
  ._sidebarCol_test { background: #eef2f7; }
  ._sidebarRoot_test { padding: 12px; }
  ._sidebarRoot_test._collapsed_test { width: 56px; overflow: hidden; }
  ._sidebarRoot_test._collapsed_test .label { display: none; }
  ._centerCol_test { display: flex; flex-direction: column; min-height: 0; }
  ._header_test { padding: 12px; }
  ._scrollBody_test { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
  .msg { padding: 10px 12px; border-bottom: 1px solid #eee; }
  ._composerSeat_test { padding-bottom: 8px; }
  ._composer_test { margin: 8px 12px; padding: 10px; border: 1px solid #ccd; border-radius: 14px; }
  ._composer_test textarea { width: 100%; height: 44px; border: 0; resize: none; }
</style></head>
<body>
  <div id="root">
    <div class="_frame_test">
      <div class="_sidebarCol_test">
        <div class="_sidebarRoot_test" id="sidebar-root">
          <div class="label">新会话</div><div class="label">插件</div><div class="label">工作区</div>
          <button class="_toggle_test" id="sidebar-toggle" aria-label="Collapse sidebar">toggle</button>
        </div>
      </div>
      <div class="_centerCol_test">
        <div class="_header_test">标题</div>
        <div class="_scrollBody_test" id="scroller"></div>
        <div class="_composerSeat_test"><div class="_composer_test"><textarea placeholder="发消息"></textarea></div></div>
      </div>
    </div>
  </div>
</body></html>`
}

/** dsh's sidebar behaviour, reduced to what the injector observes. */
const SIDEBAR_TOGGLE_SCRIPT = `
  window.__toggleClicks = [];
  document.getElementById('sidebar-toggle').addEventListener('click', function () {
    window.__toggleClicks.push(performance.now());
    var root = document.getElementById('sidebar-root');
    root.classList.toggle('_collapsed_test');
  });
`

/**
 * The injector re-fits the composer overlap on its 2s recovery tick (and on
 * resize), not on mutations, so every assertion waits past one tick.
 */
const FIT_TICK_MS = 2400

async function fillMessages(page, count) {
  await page.evaluate((n) => {
    const scroller = document.getElementById('scroller')
    scroller.innerHTML = ''
    for (let i = 0; i < n; i += 1) {
      const div = document.createElement('div')
      div.className = 'msg'
      div.textContent = `消息 ${i + 1}：${'内容填充。'.repeat(12)}`
      scroller.appendChild(div)
    }
    scroller.scrollTop = scroller.scrollHeight
  }, count)
}

/** Bottom of the last message versus the top of the fixed composer seat. */
async function overlapPx(page) {
  return page.evaluate(() => {
    const scroller = document.getElementById('scroller')
    const seat = document.querySelector('div[class*="_composerSeat"]')
    const last = scroller.lastElementChild
    const spacer = document.getElementById('dsh-mobile-composer-spacer')
    if (!seat || !last) return { error: 'missing element' }
    // Ignore the spacer itself: the real last *content* node is what must clear the seat.
    const content = spacer && last.id === 'dsh-mobile-composer-spacer'
      ? last.previousElementSibling
      : last
    scroller.scrollTop = scroller.scrollHeight
    return {
      overlap: Math.round(content.getBoundingClientRect().bottom - seat.getBoundingClientRect().top),
      spacerHeight: spacer ? Math.round(spacer.getBoundingClientRect().height) : 0,
      seatHeight: Math.round(seat.getBoundingClientRect().height),
    }
  })
}

async function scenarioComposerOverlap(page) {
  console.log('\nscenario: composer overlap after a container rebuild')
  await fillMessages(page, 12)
  await page.waitForTimeout(FIT_TICK_MS)
  const first = await overlapPx(page)
  check(first.spacerHeight > 0, `spacer carries height (${first.spacerHeight}px)`)
  check(first.overlap <= 0, `last message clears the composer (overlap ${first.overlap}px)`)

  // React may replace the scroll container's children wholesale; the injector has
  // to re-apply the spacer height to the fresh element.
  await page.evaluate(() => {
    const scroller = document.getElementById('scroller')
    const fresh = scroller.cloneNode(false)
    scroller.replaceWith(fresh)
    fresh.id = 'scroller'
    for (let i = 0; i < 12; i += 1) {
      const div = document.createElement('div')
      div.className = 'msg'
      div.textContent = `重建后的消息 ${i + 1}：${'内容填充。'.repeat(12)}`
      fresh.appendChild(div)
    }
  })
  await page.waitForTimeout(FIT_TICK_MS)
  const second = await overlapPx(page)
  check(second.spacerHeight > 0, `spacer regained height after the rebuild (${second.spacerHeight}px)`)
  check(second.overlap <= 0, `last message still clears the composer (overlap ${second.overlap}px)`)
}

/**
 * Sample the drawer while it closes: the panel transform, the mask opacity, and
 * whether dsh's own collapse has already happened (which reflows the sidebar
 * into its rail while the panel is still on screen).
 */
async function scenarioDrawerExit(page, label) {
  console.log(`\nscenario: drawer exit (${label})`)
  await page.click('#dsh-mobile-railbtn')
  await page.waitForTimeout(500)
  const opened = await page.evaluate(() => ({
    drawer: document.body.classList.contains('dsh-mobile-drawer'),
    panelLeft: Math.round(document.querySelector('div[class*="_sidebarCol"]').getBoundingClientRect().left),
    panelWidth: Math.round(document.querySelector('div[class*="_sidebarCol"]').getBoundingClientRect().width),
  }))
  check(opened.drawer, `drawer opens (panel ${opened.panelWidth}px at x=${opened.panelLeft})`)
  if (shotsDir) await page.screenshot({ path: join(shotsDir, `${label}-open.png`) })

  const result = await page.evaluate(async () => {
    window.__toggleClicks = []
    const panel = document.querySelector('div[class*="_sidebarCol"]')
    const mask = document.getElementById('dsh-mobile-drawer-mask')
    const root = document.getElementById('sidebar-root')
    const out = []
    const start = performance.now()
    const timer = setInterval(() => {
      out.push({
        t: Math.round(performance.now() - start),
        transform: getComputedStyle(panel).transform,
        maskOpacity: Number(getComputedStyle(mask).opacity),
        maskDisplay: getComputedStyle(mask).display,
        drawer: document.body.classList.contains('dsh-mobile-drawer'),
        dshCollapsed: root.classList.contains('_collapsed_test'),
      })
    }, 16)
    // Close the way a user does: tap the mask.
    document.getElementById('dsh-mobile-drawer-mask').click()
    await new Promise(r => setTimeout(r, 700))
    clearInterval(timer)
    return { samples: out, clicks: window.__toggleClicks.map(c => Math.round(c - start)) }
  })
  const { samples, clicks } = result
  const panelLeft = samples.length > 0 ? await page.evaluate(() => Math.round(document.querySelector('div[class*="_sidebarCol"]').getBoundingClientRect().left)) : 0

  const transforms = [...new Set(samples.map(s => s.transform))]
  const opacities = [...new Set(samples.map(s => s.maskOpacity))]
  const movingFrames = transforms.length
  const firstClick = clicks[0] ?? -1
  // dsh's own collapse may only land while the panel is already off screen;
  // reflowing the sidebar to its rail inside a visible panel is the defect
  // reported in issue #15.
  const visibleWhileCollapsed = samples.filter(s =>
    s.dshCollapsed && s.drawer && translateX(s.transform) > -0.95 * opened.panelWidth)

  check(movingFrames >= 2, `panel transform animates through ${movingFrames} value(s)`)
  check(opacities.length >= 2, `mask opacity animates through ${opacities.length} value(s)`)
  check(firstClick > 120, `dsh's own collapse waits for the exit animation (toggle at ${firstClick}ms)`)
  check(visibleWhileCollapsed.length === 0,
    `sidebar never reflows inside a still-visible panel (${visibleWhileCollapsed.length} sample(s))`)
  const final = samples[samples.length - 1]
  check(!final.drawer, 'drawer class is removed after the exit')
  check(final.maskOpacity === 0 || final.maskDisplay === 'none',
    `mask ends hidden (opacity ${final.maskOpacity}, display ${final.maskDisplay})`)
  if (shotsDir) await page.screenshot({ path: join(shotsDir, `${label}-closed.png`) })
  return { panelLeft }
}

/**
 * Pick a Chromium build that actually exists: the Playwright-bundled download,
 * else a system browser channel. Keeps the gate offline-runnable on a host whose
 * Playwright cache predates the installed package.
 */
function chromeLaunchOptions() {
  const env = process.env.DSH_VERIFY_BROWSER
  if (env) return env.includes('\\') || env.includes('/') ? { executablePath: env } : { channel: env }
  const candidates = [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  ]
  for (const candidate of candidates) {
    if (existsSync(candidate)) return { executablePath: candidate }
  }
  return {}
}

async function main() {
  const css = await readFile(join(assetDir, 'mobile.css'), 'utf8')
  const inject = await readFile(join(assetDir, 'inject.js'), 'utf8')
  if (shotsDir) await mkdir(shotsDir, { recursive: true })

  const browser = await chromium.launch({ headless: !headed, ...chromeLaunchOptions() })
  try {
    const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: DEVICE_SCALE })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(String(error)))
    await page.setContent(pageHtml(css))
    await page.addScriptTag({ content: SIDEBAR_TOGGLE_SCRIPT })
    await page.addScriptTag({ content: inject })
    await page.waitForTimeout(400)

    await scenarioComposerOverlap(page)
    await scenarioDrawerExit(page, 'fixed')
    await scenarioDrawerExit(page, 'second-run')
    check(errors.length === 0, `inject.js raised no page errors${errors.length ? `: ${errors[0]}` : ''}`)

    if (shotsDir) {
      await writeFile(join(shotsDir, 'samples.json'), JSON.stringify({ shots: shotsDir }, null, 2), 'utf8')
    }
  } finally {
    await browser.close()
  }

  console.log(failures.length === 0
    ? '\nmobile UI gate: all expectations met'
    : `\nmobile UI gate: ${failures.length} failure(s)`)
  process.exitCode = failures.length === 0 ? 0 : 1
}

await main()
