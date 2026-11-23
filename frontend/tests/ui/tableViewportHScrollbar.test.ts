/**
 * Viewport-resident horizontal scrollbar contract .
 *
 * Long list tables push their horizontal scrollbar below the fold, so users
 * must scroll to the page bottom before they can drag sideways. The fix lives
 * in ONE shared place: an app-level enhancer that pins a proxy scrollbar to
 * the viewport bottom while a table overflows horizontally and its bottom
 * edge is off-screen. Pages must NOT add per-page scrollbar patches.
 *
 * Gates:
 * 1) pure geometry is correct (pin state / thumb / overflow detection,
 * zero proxy when there is no overflow);
 * 2) theme.scss carries the proxy styling single source (height + z-order
 * as variables, no page-level literal);
 * 3) main.ts installs the enhancer exactly once;
 * 4) page-level scrollbar patches only shrink (ratchet baseline).
 * The real-browser truth (proxy visible mid-page, drag moves the table) is
 * asserted by the E2E long-table journey, not here.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  computeDialogPinState,
  computePinState,
  computeThumbGeometry,
  hasHorizontalOverflow,
} from '../../src/utils/tableViewportHScrollbar'

const SRC = new URL('../../src', import.meta.url).pathname
const THEME = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')
const MAIN = readFileSync(join(SRC, 'main.ts'), 'utf-8')

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      if (name === 'node_modules') continue
      walk(p, out)
    } else if (name.endsWith('.vue')) {
      out.push(p)
    }
  }
  return out
}

describe('T579 视口常驻横向滚动条（共享容器单源）', () => {
  it('溢出判定：横向可滚才认定溢出（4px 容差内不误判）', () => {
    expect(hasHorizontalOverflow(2000, 1000)).toBe(true)
    expect(hasHorizontalOverflow(1004, 1000)).toBe(false)
    expect(hasHorizontalOverflow(1000, 1000)).toBe(false)
  })

  it('钉挂判定：表体底沿低于视口底且表体仍横跨视口才出代理；无溢出恒 null', () => {
    const rect = { top: 100, bottom: 2400, left: 200, width: 1200 }
    expect(computePinState(rect, 900, true)).toEqual({ pinned: true, left: 200, width: 1200 })
    expect(computePinState({ top: -2000, bottom: -100, left: 0, width: 800 }, 900, true)).toBeNull()
    expect(computePinState({ top: 100, bottom: 700, left: 0, width: 800 }, 900, true)).toBeNull()
    expect(computePinState(rect, 900, false)).toBeNull()
  })

  it('代理拇指几何：宽度=可视占比，位移=滚动比例；无溢出返回 null', () => {
    expect(computeThumbGeometry(1000, 2000, 500, 1200)).toEqual({ left: 300, width: 600 })
    expect(computeThumbGeometry(1000, 2000, 0, 1200)).toEqual({ left: 0, width: 600 })
    expect(computeThumbGeometry(1000, 1000, 0, 1200)).toBeNull()
  })

  it('theme.scss 承载代理样式单源（高度与 z 序走变量）', () => {
    expect(THEME).toMatch(/--tp-hviewport-bar-height/)
    expect(THEME).toMatch(/--tp-hviewport-bar-z/)
    expect(THEME).toMatch(/\.tp-hviewport-bar[\s\S]*?position:\s*fixed/)
  })

  it('main.ts 单点安装本增强（全链唯一入口）', () => {
    expect(MAIN).toMatch(/installTableViewportHScrollbar/)
  })

  it('页面级横向滚动条补丁只减不增（棘轮基线）', () => {
    const sites: string[] = []
    for (const f of walk(SRC)) {
      const text = readFileSync(f, 'utf-8')
      const styleBlock = /<style[\s\S]*<\/style>/.test(text) ? text.slice(text.indexOf('<style')) : ''
      if (!styleBlock) continue
      if (/\.el-scrollbar__bar|is-horizontal\s*\{|tp-hviewport-bar/.test(styleBlock)) {
        sites.push(f.slice(SRC.length + 1))
      }
    }
    // Baseline captured live at cut time: today the site-wide rules all live
    // in theme.scss, so any hit here is a NEW per-page patch duplicating the
    // single source — ratchet at zero.
    expect(sites.length, `页面级滚动条补丁违反 T579 单源纪律：\n${sites.join('\n')}`).toBeLessThanOrEqual(0)
  })
})

/**
 * extension (user order 2026-11-09): a table inside a dialog or drawer
 * must get the SAME shared proxy bar, pinned to the lower inner edge of the
 * dialog body instead of the window viewport — horizontal scrolling must not
 * require dragging the table rows down first. One implementation, two pin
 * surfaces; the geometry below is the contract for the dialog surface.
 */
describe('T591 dialog/drawer 内表横向滚动条常驻（共享层扩面）', () => {
  const MODULE = readFileSync(join(SRC, 'utils/tableViewportHScrollbar.ts'), 'utf-8')

  it('弹窗钉挂：仅当表体底沿越过弹窗体下沿且横向溢出时才出代理条', () => {
    const body = { top: 120, bottom: 780, left: 100, width: 1000 }
    // Long table crossing the body's lower edge → pin at the body bottom
    // (viewport 900 − body bottom 780 = 120px offset from the window bottom).
    expect(computeDialogPinState({ top: 200, bottom: 2400, left: 100, width: 1000 }, body, 900, true))
      .toEqual({ pinned: true, left: 100, width: 1000, bottom: 120 })
    // The table's own bar already sits inside the visible body → zero proxy.
    expect(computeDialogPinState({ top: 150, bottom: 700, left: 100, width: 1000 }, body, 900, true)).toBeNull()
    // The table starts below the visible body edge → not reachable yet, zero.
    expect(computeDialogPinState({ top: 800, bottom: 1600, left: 100, width: 1000 }, body, 900, true)).toBeNull()
    // No horizontal overflow → never renders, whatever the position is.
    expect(computeDialogPinState({ top: 200, bottom: 2400, left: 100, width: 1000 }, body, 900, false)).toBeNull()
  })

  it('弹窗内代理条贴体下沿内缩与独立层级走 theme.scss 单源令牌', () => {
    expect(THEME).toMatch(/--tp-hviewport-bar-dialog-z/)
    expect(THEME).toMatch(/--tp-hviewport-bar-dialog-inset/)
    expect(THEME).toMatch(/\.tp-hviewport-bar--dialog[\s\S]{0,400}?z-index:\s*var\(--tp-hviewport-bar-dialog-z\)/)
  })

  it('单源模块把弹窗/抽屉内表路由到弹窗模式并跟随可拖拽弹窗移动', () => {
    // Dialog/drawer bodies are now scroll surfaces, not exclusions.
    expect(MODULE).toMatch(/\.el-dialog__body/)
    expect(MODULE).toMatch(/\.el-drawer__body/)
    // Draggable dialogs move via an inline style change that fires no
    // scroll/resize event, so the module must watch the dialog's style attr.
    expect(MODULE).toMatch(/attributeFilter[^)]*'style'/)
    // The old blanket exclusion of dialogs/drawers is gone (R40: extend the
    // one shared surface-routing function instead of a second implementation).
    expect(MODULE).not.toMatch(/closest\(['"]\.el-dialog, \.el-drawer, \.el-overlay/)
  })

  it('弹窗关闭时代理条与其观察器成对销毁（建而不灭即泄漏）', () => {
    expect(MODULE).toMatch(/\.disconnect\(\)/)
    expect(MODULE).toMatch(/destroyEntry/)
  })

  it('抽屉滑入变换与体内滚动必须触发重钉，且监听随条目成对摘除', () => {
    // A right-sliding drawer moves the wrap box via a transform that fires
    // neither the wrap's scroll nor a style mutation, and the dialog body's
    // vertical scroll happens above the wrap — the module re-pins through
    // host-level capture listeners plus the open animation settle events.
    expect(MODULE).toMatch(/addEventListener\('scroll',[^)]*capture:\s*true/)
    expect(MODULE).toMatch(/addEventListener\('transitionend'/)
    expect(MODULE).toMatch(/addEventListener\('animationend'/)
    // R57 pairing: every host listener added at creation is removed on destroy.
    expect(MODULE).toMatch(/removeEventListener\('scroll',[^)]*capture/)
    expect(MODULE).toMatch(/removeEventListener\('transitionend'/)
    expect(MODULE).toMatch(/removeEventListener\('animationend'/)
  })
})
