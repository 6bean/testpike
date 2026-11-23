/**
 * Shared upload dialog bottom-rhythm contract.
 *
 * Defect under test (vision review item 3 of the shared-upload round): inside FileUploadDialog the
 * drag area and the footer buttons sat on a loose vertical rhythm — Element
 * Plus pushes its format tip (.el-upload__tip) with a 5px top / large default
 * margin and the dragger keeps generous internal padding, so the zone between
 * the drop rect and the submit row read as dead whitespace (dialog 640x392,
 * ratio itself is in band; the gap is a component-internal problem shared by
 * all four consumer pages: documents / skill-market / tool-market / plugins).
 *
 * Fix is display-only and single-sourced in the shared component: tighten the
 * dragger padding and pull the format hint up directly above the action area
 * with a compact fixed margin. Contract = raw-file style-block assertion
 * (precedent = chatProcessItemWhitespacePreserve).
 */
import { describe, expect, it } from 'vitest'
import dialogRaw from '@/components/FileUploadDialog.vue?raw'

function styleBlock(src: string): string {
  const m = src.match(/<style scoped>([\s\S]*?)<\/style>/)
  return m ? m[1] : ''
}

describe('FileUploadDialog bottom rhythm', () => {
  const css = styleBlock(dialogRaw)

  it('compresses the dragger interior padding instead of the EP default', () => {
    expect(css).toMatch(/:deep\(\.el-upload-dragger\)[^{]*\{\s*padding:\s*\d+px/)
  })

  it('pins the format hint to a compact fixed margin above the action area', () => {
    expect(css).toMatch(/:deep\(\.el-upload__tip\)[^{]*\{\s*margin-top:\s*\d+px\s*;\s*margin-bottom:\s*0\s*;\s*\}/)
  })

  it('carries the dialog body rhythm through one theme gap token', () => {
    // Single-source vertical rhythm: the shared band-gap token on the body
    // container is the only carrier of the gap between the adjacent blocks
    // (extra-form row / drop zone / inline error / progress row), so no block
    // patches its own top margin any more — the per-rule 12px margins this
    // contract used to pin are superseded by the token.
    expect(css).toMatch(
      /\.file-upload-body\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;[^}]*gap:\s*var\(--tp-band-gap\)/)
    const rules = css.replace(/\/\*[\s\S]*?\*\//g, '').split('}')
    const topMarginRules = rules.filter(block => /margin-top\s*:/.test(block))
    expect(topMarginRules.length).toBe(1)
    expect(topMarginRules[0]).toContain(':deep(.el-upload__tip)')
  })
})
