/**
 * Extract-row source lane contract (AV batch): the save gate
 * already accepts source=body / header / cookie (single source =
 * core ApiAssertionFormContract.EXTRACT_SOURCES, mirrored here), so the editor
 * must expose the same lane on the edit face — a field the gate validates but
 * the form cannot set is an invisible field (control-scope iron law: the visible
 * face must equal the effective domain).
 *
 * Pins:
 * - utils/assertionForm exports EXTRACT_SOURCE_OPTIONS (value + label per lane)
 *   and consumes it inside validateAssertionRow — the three-lane literal exists
 *   exactly once in the browser mirror (no second copy in the component);
 * - extract type metadata names the three lanes in the grey example line;
 * - AssertionBuilder carries a `source` model on its rows: loaded from the JSON,
 *   rendered as an anchored select, revalidated on change, and written back into
 *   the emitted row only for the non-default lanes (legacy body rows stay
 *   byte-identical);
 * - the body lane keeps the JsonPath field, the header/cookie lanes expose the
 *   name field instead, so a lane that needs a name can never be configured
 *   without one.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ASSERTION_TYPE_META,
  EXTRACT_SOURCE_OPTIONS,
  validateAssertionRow,
} from '@/utils/assertionForm'

const here = dirname(fileURLToPath(import.meta.url))
const builderRaw = readFileSync(resolve(here, '../../src/components/AssertionBuilder.vue'), 'utf-8')
const formRaw = readFileSync(resolve(here, '../../src/utils/assertionForm.ts'), 'utf-8')

describe('extract source lane: single-source vocabulary', () => {
  it('the mirror exports the three lanes with display labels', () => {
    expect(EXTRACT_SOURCE_OPTIONS.map((o) => o.value)).toEqual(['body', 'header', 'cookie'])
    for (const opt of EXTRACT_SOURCE_OPTIONS) {
      expect(opt.label.length, `${opt.value} 缺展示名`).toBeGreaterThan(1)
    }
  })

  it('the lane literal exists once in the mirror (component must not re-declare it)', () => {
    // 'cookie' is lane-exclusive: one quoted occurrence = one vocabulary owner.
    expect((formRaw.match(/'cookie'/g) ?? []).length, "utils/assertionForm 内 'cookie' 字面只允许一处").toBe(1)
    expect(builderRaw).not.toMatch(/'cookie'/)
    expect(builderRaw).toMatch(/EXTRACT_SOURCE_OPTIONS/)
    expect(builderRaw).toMatch(/from '@\/utils\/assertionForm'/)
  })

  it('the gate still fails closed on an unknown lane naming the three values', () => {
    const v = validateAssertionRow({ type: 'extract', source: 'xml', name: 'a', saveAs: 'V' })
    expect(v).toContain('变量提取来源 source 仅支持 body / header / cookie')
  })

  it('extract grey example names the three lanes (editor hint width = gate face)', () => {
    expect(ASSERTION_TYPE_META.extract.example).toContain('source')
    for (const lane of ['body', 'header', 'cookie']) {
      expect(ASSERTION_TYPE_META.extract.example, `示例缺 ${lane} 支`).toContain(lane)
    }
  })
})

describe('extract source lane: editor exposes what the gate validates', () => {
  it('the row model carries source and reads it back from saved JSON', () => {
    expect(builderRaw).toMatch(/source:\s*string/)
    expect(builderRaw).toMatch(/source: laneOf\(r\.source\)/)
    expect(builderRaw).toMatch(/value === '' \? 'body'/)
    expect(builderRaw).toMatch(/source: 'body'/)
  })

  it('an anchored lane select is bound to row.source and revalidates on change', () => {
    expect(builderRaw).toMatch(/data-test="`assertion-source-\$\{i\}`"/)
    expect(builderRaw).toMatch(/v-model="row\.source"/)
    expect(builderRaw).toMatch(/onSourceChange/)
    // Options iterate the shared vocabulary, never a re-typed list.
    expect(builderRaw).toMatch(/v-for="lane in EXTRACT_SOURCE_OPTIONS"/)
  })

  it('the JsonPath field stays on the body lane only', () => {
    expect(builderRaw).toMatch(/row\.type === 'extract' && row\.source === 'body'/)
  })

  it('the header/cookie lanes render the name field (no invisible required field)', () => {
    // The anchored input itself must bind row.name (segment from its opening tag),
    // so a static scan cannot be satisfied by some other field further down.
    const idx = builderRaw.indexOf('`assertion-extract-name-${i}`')
    expect(idx, '缺提取支名称输入锚').toBeGreaterThan(-1)
    const open = builderRaw.lastIndexOf('<el-input', idx)
    const block = builderRaw.slice(open, builderRaw.indexOf('/>', idx))
    expect(block).toMatch(/v-model="row\.name"/)
    expect(block).toMatch(/row\.source !== 'body'/)
  })

  it('emitted rows carry the lane only when it is not the default (legacy JSON byte-stable)', () => {
    expect(builderRaw).toMatch(/out\.source = row\.source/)
    expect(builderRaw).toMatch(/row\.source !== 'body'/)
  })

  it('the name write-back covers extract lanes, not just header assertions', () => {
    expect(builderRaw).toMatch(/row\.type === 'header' \|\| \(row\.type === 'extract' && row\.source !== 'body'\)/)
  })
})
