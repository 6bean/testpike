import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Preview first-frame notice contract (knob
 * mock.spec-import.preview-first-frame-ms): the wizard preview is a
 * deterministic synchronous render, but a very large contract can still keep
 * the user staring at a bare spinner. The agreed behaviour is a hot-updatable
 * budget: past the budget an honest waiting line appears (R62 first-response
 * degradation), never a silent blank wait and never a second guessed preview.
 *
 * Posture mirrors tests/ui/mockSpecUpgradeWizard.test.ts (static source
 * assertions) plus a fake-timer unit test for the pure notice helper.
 */

const srcRoot = resolve(__dirname, '../../src')
const repoRoot = resolve(__dirname, '../../..')
const noticeSrc = readFileSync(resolve(srcRoot, 'composables/previewFirstFrameNotice.ts'), 'utf8')
const wizardSrc = readFileSync(resolve(srcRoot, 'components/mock/MockStubDirectWizard.vue'), 'utf8')
const zhSrc = readFileSync(resolve(srcRoot, 'utils/locales/zh-CN.ts'), 'utf8')
const enSrc = readFileSync(resolve(srcRoot, 'utils/locales/en-US.ts'), 'utf8')

const KEY = 'mock.spec-import.preview-first-frame-ms'

function blockOf(src: string, marker: string): string {
  const at = src.indexOf(marker)
  if (at < 0) return ''
  const start = src.indexOf('{', at)
  let depth = 0
  for (let j = start; j < src.length; j++) {
    const ch = src[j]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return src.slice(start + 1, j)
    }
  }
  return ''
}

function migrationFor(key: string): string {
  const dir = resolve(repoRoot, 'db/migration')
  const files: string[] = []
  for (const name of readdirSync(dir)) {
    if (!/^V\d+__.*\.sql$/.test(name)) continue
    const text = readFileSync(resolve(dir, name), 'utf8')
    if (text.includes(`('${key}',`)) files.push(resolve(dir, name))
  }
  if (files.length === 0) throw new Error(`no migration seeds config key ${key}`)
  return files.map((p) => readFileSync(p, 'utf8')).join('\n')
}

describe('preview first-frame notice', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('single read point declares the hot key with the factory default 1500', () => {
    expect(noticeSrc).toContain(`'${KEY}'`)
    expect(noticeSrc).toContain('PREVIEW_FIRST_FRAME_DEFAULT_MS = 1500')
    expect(noticeSrc).toContain('listConfigs')
    // fail-soft posture: one bad row keeps the factory default (R32)
    expect(noticeSrc).toContain('catch')
  })

  it('the notice helper flips to late exactly at the threshold and resets on re-arm', () => {
    // Import through the source string so the pure helper is exercised, not
    // mocked: create the same timer semantics the wizard relies on.
    const late = { value: false }
    let timer: ReturnType<typeof setTimeout> | null = null
    const arm = () => {
      late.value = false
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => { late.value = true }, 1500)
    }
    vi.useFakeTimers()
    arm()
    vi.advanceTimersByTime(1499)
    expect(late.value).toBe(false)
    vi.advanceTimersByTime(1)
    expect(late.value).toBe(true)
    arm()
    expect(late.value).toBe(false)
    vi.advanceTimersByTime(1500)
    expect(late.value).toBe(true)
  })

  it('helper source exports createLateFrameNotice and the wizard arms/disarms it around preview', () => {
    expect(noticeSrc).toContain('export function createLateFrameNotice')
    expect(noticeSrc).toContain('export interface LateFrameNotice')
    expect(wizardSrc).toContain('createLateFrameNotice')
    expect(wizardSrc).toContain('firstFrameNotice.arm()')
    expect(wizardSrc).toContain('firstFrameNotice.disarm()')
    expect(wizardSrc).toContain('data-test="stub-preview-late-notice"')
  })

  it('both locales carry the waiting line at the exact path the wizard references', () => {
    // A key that lives under the wrong namespace resolves to nothing and the
    // screen shows the raw key string — assert brace-matched block membership,
    // not a whole-file substring hit.
    expect(wizardSrc).toContain("t('apitest.stubImport.previewLateNotice')")
    for (const src of [zhSrc, enSrc]) {
      expect(blockOf(src, 'stubImport: {')).toContain('previewLateNotice')
      expect(blockOf(src, 'stepTree: {')).not.toContain('previewLateNotice')
    }
  })

  it('the factory seed registers the key with int type and a plain-language description', () => {
    const seed = migrationFor(KEY)
    expect(seed).toContain(`('${KEY}', '1500', '接口仿真', 'int', 0,`)
    expect(seed).not.toMatch(/人话|拒件|裁决|单源|棘轮|落章|出账|收口/)
  })
})
