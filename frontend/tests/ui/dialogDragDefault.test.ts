/**
 * Dialog-dragging single-source contract (user report 2026-11-23).
 *
 * Dragging must be a site-wide dialog default installed ONCE through
 * Element Plus's own global-config channel (src/utils/dialogDragDefault.ts,
 * wired in main.ts before mount). The report was that most dialogs could not
 * be dragged at all — per-page `draggable` flags had been painted on 120 of
 * 146 dialog usages while the rest stayed dead, a classic double-channel
 * drift. This contract keeps exactly one declaration site:
 *
 *  1) the installer really provides `{ dialog: { draggable: true } }` to the
 *     app (behavioral: Element Plus reads `props.draggable ??
 *     globalConfig.dialog.draggable ?? false` in use-dialog);
 *  2) a bare app WITHOUT the installer reads zero (the gate can actually
 *     detect the missing source — anti-idle);
 *  3) main.ts calls it exactly once, BEFORE app.mount (inject resolves at
 *     render time, so a post-mount install would be a silent no-op);
 *  4) no second declaration site exists anywhere under src.
 *
 * The runtime truth (drag actually moves a dialog, also while the
 * site-profile drawer modal is open) is asserted by the E2E spectrum
 * tests/e2e/test_dialog_drag_single_source.py, not here.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { createApp, h } from 'vue'
import { configProviderContextKey } from 'element-plus'
import { describe, expect, it } from 'vitest'
import { installDialogDragDefault } from '../../src/utils/dialogDragDefault'

const SRC = new URL('../../src', import.meta.url).pathname

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      walk(p, out)
    } else if (/\.(ts|vue)$/.test(name)) {
      out.push(p)
    }
  }
  return out
}

describe('dialog dragging global default (single source)', () => {
  it('installer provides the dialog.draggable default to the app', () => {
    const app = createApp({ render: () => h('div') })
    installDialogDragDefault(app)
    const ctx = (app as any)._context.provides[configProviderContextKey]
    expect(ctx).toBeTruthy()
    expect(ctx.value.dialog.draggable).toBe(true)
  })

  it('bare app without the installer carries no global dialog config', () => {
    // Anti-idle direction: if the install site is ever deleted, the test
    // above turns red — this one proves the assertion is not a tautology
    // against a pre-provided default.
    const app = createApp({ render: () => h('div') })
    const ctx = (app as any)._context.provides[configProviderContextKey]
    expect(ctx?.value?.dialog?.draggable).toBeUndefined()
  })

  it('main.ts installs the default exactly once and before mount', () => {
    const main = readFileSync(join(SRC, 'main.ts'), 'utf-8')
    const callSites = main.match(/installDialogDragDefault\(app\)/g) ?? []
    expect(callSites).toHaveLength(1)
    expect(main.indexOf('installDialogDragDefault(app)')).toBeLessThan(
      main.indexOf("app.mount("))
  })

  it('no second dialog.draggable declaration exists under src', () => {
    const offenders = walk(SRC)
      .filter((f) => !f.endsWith('dialogDragDefault.ts'))
      .filter((f) => {
        const src = readFileSync(f, 'utf-8')
        return /dialog\s*:\s*\{[^}]*draggable/.test(src)
      })
    expect(offenders).toEqual([])
  })
})
