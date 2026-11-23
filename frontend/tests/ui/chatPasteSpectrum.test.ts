/**
 * — chat composer paste spectrum contract.
 *
 * Three clipboard shapes, one door (the shared composer in ChatPanel, which
 * both the floating dock and the full chat page mount — single wiring point,
 * R40 no second upload path):
 * a) image items → attachment queue through the existing upload door;
 * b) html <table> / TSV → GitHub-flavored Markdown pipe table at the caret
 * (all tables in document order, empty cells kept,
 * pipes escaped, in-cell line breaks as <br>);
 * c) plain text (md source included) → verbatim default paste, never
 * preventDefault-ed.
 * The conversion is gated by the hot-update key
 * ai.chat.paste-table-convert.enabled (factory on); with the key off the text
 * lands raw and a one-time pointer names where to turn it back on.
 *
 * Behaviour lives in the pure planner (node environment, no DOM); wiring is
 * pinned source-as-text in the precedent style of chatDockWiring.test.ts.
 */
import { describe, expect, it } from 'vitest'
import {
  htmlTablesToGfm,
  planChatPaste,
  tsvToGfm,
  PASTE_TABLE_KEY,
} from '@/composables/chatPasteEnhance'
import panelRaw from '@/components/ChatPanel.vue?raw'
import dockRaw from '@/components/ChatDock.vue?raw'
import viewRaw from '@/views/ai/AiChatView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

/** File stand-in: the planner never touches File internals. */
const img = (name = 'shot.png') => ({ name, type: 'image/png' }) as unknown as File

describe('html table → GFM (shape b, priority 1)', () => {
  it('converts a 3x2 table with pipes and an empty cell to the golden text', () => {
    const html =
      '<table><tr><th>Name</th><th>Note|Remark</th></tr>' +
      '<tr><td>Alpha</td><td></td></tr>' +
      '<tr><td>Line1<br>Line2</td><td>OK</td></tr></table>'
    expect(htmlTablesToGfm(html)).toBe(
      '| Name | Note\\|Remark |\n' +
        '| --- | --- |\n' +
        '| Alpha |  |\n' +
        '| Line1<br>Line2 | OK |',
    )
  })

  it('pads short rows to the widest row and decodes entities', () => {
    const html =
      '<table><tr><td>a &amp; b</td><td>c</td><td>d</td></tr><tr><td>x</td></tr></table>'
    expect(htmlTablesToGfm(html)).toBe('| a & b | c | d |\n| --- | --- | --- |\n| x |  |  |')
  })

  it('interleaves both tables with the prose between them, document order', () => {
    const html =
      '<div><table><tr><td>1</td></tr></table><p>text</p><table><tr><td>2</td></tr></table></div>'
    expect(htmlTablesToGfm(html)).toBe('| 1 |\n| --- |\n\ntext\n\n| 2 |\n| --- |')
  })

  it('keeps off-table prose at its original position instead of dropping it', () => {
    // Excel/web copy shape: caption before the table, note after it.
    const html =
      '<p>前置说明</p><table><tr><th>环境</th><th>地址</th></tr>' +
      '<tr><td>dev</td><td>10.0.0.1</td></tr></table>' +
      '<p>中间说明 &amp; 备注<b>重要</b></p>' +
      '<table><tr><th>端口</th></tr><tr><td>8081</td></tr></table>'
    expect(htmlTablesToGfm(html)).toBe(
      '前置说明\n\n' +
        '| 环境 | 地址 |\n| --- | --- |\n| dev | 10.0.0.1 |\n\n' +
        '中间说明 & 备注重要\n\n' +
        '| 端口 |\n| --- |\n| 8081 |',
    )
  })

  it('drops whitespace-only gaps so empty prose never floods the insert', () => {
    const html = '<table><tr><td>1</td></tr></table>\n   \n  <table><tr><td>2</td></tr></table>'
    expect(htmlTablesToGfm(html)).toBe('| 1 |\n| --- |\n\n| 2 |\n| --- |')
  })

  it('returns null when the html carries no table', () => {
    expect(htmlTablesToGfm('<div>just <b>words</b></div>')).toBeNull()
  })
})

describe('TSV → GFM (shape b, priority 2: only without html)', () => {
  it('converts a multi-line tab-separated block', () => {
    expect(tsvToGfm('a\tb\n1\t2\n')).toBe('| a | b |\n| --- | --- |\n| 1 | 2 |')
  })

  it('refuses single-line or tab-less text', () => {
    expect(tsvToGfm('a\tb')).toBeNull()
    expect(tsvToGfm('one line\nanother line')).toBeNull()
  })
})

describe('planChatPaste routing', () => {
  it('shape c: plain text passes through untouched and the event is not consumed', () => {
    const plan = planChatPaste({ html: '', text: 'hello world', images: [] }, true)
    expect(plan).toEqual({ images: [], insertText: null, consumed: false, tableHit: false })
  })

  it('shape a: image items ride the plan through with no consumption', () => {
    const plan = planChatPaste({ html: '', text: '', images: [img()] }, true)
    expect(plan.images).toHaveLength(1)
    expect(plan.consumed).toBe(false)
    expect(plan.tableHit).toBe(false)
  })

  it('shape b: html table wins over the TSV twin carried together by Excel', () => {
    const plan = planChatPaste(
      { html: '<table><tr><td>h</td></tr></table>', text: 'h2\tx\r\n1\t2', images: [] },
      true,
    )
    expect(plan.consumed).toBe(true)
    expect(plan.insertText).toBe('| h |\n| --- |')
  })

  it('key off: table clipboard is recognised but pastes raw (honest pointer input)', () => {
    const plan = planChatPaste({ html: '<table><tr><td>h</td></tr></table>', text: 'h', images: [] }, false)
    expect(plan.tableHit).toBe(true)
    expect(plan.consumed).toBe(false)
    expect(plan.insertText).toBeNull()
  })

  it('shape a+b together: images queue AND the table converts (dual routing)', () => {
    const plan = planChatPaste(
      { html: '<table><tr><td>k</td></tr></table>', text: 'k', images: [img('p.png')] },
      true,
    )
    expect(plan.images).toHaveLength(1)
    expect(plan.consumed).toBe(true)
    expect(plan.insertText).toBe('| k |\n| --- |')
  })

  it('exposes the exact hot-update key literal the seed carries', () => {
    expect(PASTE_TABLE_KEY).toBe('ai.chat.paste-table-convert.enabled')
  })
})

describe('composer wiring (single source, R33 orthogonality)', () => {
  it('mounts exactly one paste door on the shared ChatPanel composer', () => {
    expect(panelRaw).toContain('@paste="onComposerPaste"')
    expect((panelRaw.match(/@paste=/g) ?? []).length).toBe(1)
    // The dock and the full-page view host the same kernel — never a second listener.
    expect(dockRaw).not.toMatch(/@paste|clipboardData/)
    expect(viewRaw).not.toMatch(/@paste|clipboardData/)
  })

  it('reuses the picker upload door: exactly one uploadChatImage call site', () => {
    expect((panelRaw.match(/uploadChatImage\(/g) ?? []).length).toBe(1)
    expect(panelRaw).toContain('attachImageFiles')
    expect(panelRaw).toMatch(/function onPickImages[\s\S]{0,200}attachImageFiles\(/)
  })

  it('keeps the R33 keyboard door orthogonal (Enter/Shift + IME untouched by paste)', () => {
    expect(panelRaw).toMatch(/@keydown="onComposerKeydown"/)
    expect(panelRaw).not.toMatch(/function onComposerPaste[\s\S]{0,900}keydown\(/)
  })

  it('carries the off-state pointer wording in both locales with path + key name', () => {
    expect(zhRaw).toMatch(/convertOffTip/)
    expect(enRaw).toMatch(/convertOffTip/)
    expect(zhRaw).toMatch(/layout[\s\S]{0,4000}?chatDock[\s\S]{0,12000}?paste/)
  })
})
