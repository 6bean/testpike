/**
 *
 * Every dialog write kind must announce its landing exactly once from the
 * receipt doors in src/api/aiChat.ts, and the kind value must ride the
 * receipt (server-returned objectType), never a hand-copied roster here. The
 * covered set is derived programmatically from the core DraftObjectType enum
 * — adding a new write kind needs no edit in this file.
 *
 * Bidirectional fixtures keep the gate honest (R43): the detector must go red
 * on a crafted door WITHOUT the broadcast and green on the same door WITH it,
 * and the enum parse must never silently return an empty roster.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const API_SOURCE = readFileSync(
  new URL('../../src/api/aiChat.ts', import.meta.url),
  'utf8',
)
const ENUM_SOURCE = readFileSync(
  new URL(
    '../../../core/src/main/java/com/sixbean/testpike/core/aichat/domain/component/DraftObjectType.java',
    import.meta.url,
  ),
  'utf8',
)

/** The four receipt doors the BG ruling names as the ONLY broadcast points. */
const LANDING_DOORS = [
  'applyRepairDraft',
  'applyGroupDrafts',
  'applyGroupScopeDrafts',
  'rollbackEvolutionProposal',
] as const

/** Function name → its body text (up to the next top-level export). */
function extractDoorBody(source: string, name: string): string | null {
  const start = source.indexOf(`export function ${name}`)
  if (start < 0) return null
  const rest = source.slice(start)
  const next = rest.indexOf('\nexport ', '\n'.length)
  return next > 0 ? rest.slice(0, next) : rest
}

/**
 * The detector: gaps in the broadcast wiring of one api source, against the
 * registered write kinds. Empty list = covered. Kept as a pure function so
 * the red/green fixtures below exercise exactly what the real-source
 * assertion exercises.
 */
export function collectBroadcastGaps(source: string, enumNames: string[]): string[] {
  const gaps: string[] = []
  if (enumNames.length === 0) {
    // A silently empty roster would make every later assertion vacuous green.
    return ['DraftObjectType parse returned zero constants — the gate itself is blind']
  }
  for (const door of LANDING_DOORS) {
    const body = extractDoorBody(source, door)
    if (!body) {
      gaps.push(`${door}: receipt door vanished from src/api/aiChat.ts`)
      continue
    }
    if (!body.includes('broadcastDraftSync(')) {
      gaps.push(`${door}: landed receipt leaves the door without a bus announcement`)
    }
  }
  // Declared kinds (the rollback door, whose receipt carries no kind field)
  // must be members of the core enum roster.
  const literalRe = /broadcastDraftSync\([^\n]*?,\s*([^\n)]*?)'([A-Z][A-Z0-9_]*)'\s*\)/g
  let m: RegExpExecArray | null
  while ((m = literalRe.exec(source)) !== null) {
    const kind = m[2]
    if (!enumNames.includes(kind)) {
      gaps.push(`broadcast call declares "${kind}" which is not a core DraftObjectType`)
    }
  }
  // source means somebody copied the roster instead of riding the receipt.
  const copiedRoster = /new Set\(\[([^\]]*)\]\)/g
  let s: RegExpExecArray | null
  while ((s = copiedRoster.exec(source)) !== null) {
    const literals = enumNames.filter((n) => s![1].includes(`'${n}'`))
    if (literals.length >= 3) {
      gaps.push(`api/aiChat.ts hardcodes a write-kind roster (${literals.join(', ')}) — derive from the receipt`)
    }
  }
  return gaps
}

/** Programmatic derivation of the registered write kinds from the core enum. */
function parseEnumNames(javaSource: string): string[] {
  const names: string[] = []
  const re = /^\s{4}([A-Z][A-Z0-9_]*)\(/gm
  let m: RegExpExecArray | null
  while ((m = re.exec(javaSource)) !== null) {
    names.push(m[1])
  }
  return names
}

const enumNames = parseEnumNames(ENUM_SOURCE)

describe('chat landing broadcast coverage gate (BG T629)', () => {
  it('derives a non-empty write-kind roster from the core enum (R43 anti-blind)', () => {
    expect(enumNames.length).toBeGreaterThanOrEqual(26)
    for (const kind of [
      'SCRIPT_REPAIR', 'CASE_WRITE', 'TAG_WRITE', 'PROMPT_TPL_WRITE', 'SKILL_WRITE',
      'CASE_REVIEW', 'PLAN_WRITE', 'BATCH_WRITE', 'REPORT_WRITE', 'APITEST_WRITE',
      'APISPEC_WRITE', 'PERF_LAUNCH', 'RESERVATION_WRITE', 'POPUP_RULE_LEARN',
      'MOCK_SPACE_WRITE', 'MOCK_STUB_WRITE', 'AIGEN_BATCH_WRITE', 'DEFECT_WRITE',
      'DATA_FACTORY_WRITE', 'EXPLORE_CREATE', 'EVOLUTION_PROPOSAL', 'FLAKY_ISOLATION',
      'REQUIREMENT_WRITE', 'CASE_ROLLBACK', 'API_ACCESS_WRITE', 'LOGIN_STATE_WRITE',
    ]) {
      expect(enumNames, `core enum missing ${kind}`).toContain(kind)
    }
    // will cover one automatically the day it is registered; nothing here
    // may special-case it.
    expect(enumNames).not.toContain('PROJECT_WRITE')
  })

  it('the real receipt doors all broadcast, with zero gaps', () => {
    expect(collectBroadcastGaps(API_SOURCE, enumNames)).toEqual([])
  })

  it('fixture RED: a door without the announcement is named', () => {
    const stripped = API_SOURCE.replace(
      'broadcastDraftSync(receipt)',
      'void receipt',
    )
    const gaps = collectBroadcastGaps(stripped, enumNames)
    expect(gaps.some((g) => g.includes('applyRepairDraft'))).toBe(true)
  })

  it('fixture GREEN: the same door with the announcement passes', () => {
    expect(collectBroadcastGaps(API_SOURCE, enumNames)).toEqual([])
  })

  it('fixture RED: a declared kind outside the core roster is refused', () => {
    const tainted = API_SOURCE.replace(
      "broadcastDraftSync({ targetId: receipt.id }, null, 'EVOLUTION_PROPOSAL')",
      "broadcastDraftSync({ targetId: receipt.id }, null, 'PROJECT_WRITE')",
    )
    expect(tainted).not.toBe(API_SOURCE)
    const gaps = collectBroadcastGaps(tainted, enumNames)
    expect(gaps.some((g) => g.includes('PROJECT_WRITE'))).toBe(true)
  })

  it('fixture RED: a copied kind roster inside the api source is refused', () => {
    const tainted = API_SOURCE.replace(
      'export interface DraftApplyReceipt {',
      "const COPIED = new Set(['SCRIPT_REPAIR', 'CASE_WRITE', 'TAG_WRITE'])\n\nexport interface DraftApplyReceipt {",
    )
    expect(tainted).not.toBe(API_SOURCE)
    const gaps = collectBroadcastGaps(tainted, enumNames)
    expect(gaps.some((g) => g.includes('hardcodes a write-kind roster'))).toBe(true)
  })

  it('the wire card roster in the reducer stays inside the core enum', () => {
    const reducer = readFileSync(
      new URL('../../src/composables/chatDockReducer.ts', import.meta.url),
      'utf8',
    )
    const m = reducer.match(/const KNOWN_DRAFT_KINDS = new Set\(\[([^\]]*)\]\)/)
    expect(m, 'KNOWN_DRAFT_KINDS roster not found').not.toBeNull()
    const wireKinds = [...m![1].matchAll(/'([A-Z][A-Z0-9_]*)'/g)].map((x) => x[1])
    expect(wireKinds.length).toBeGreaterThan(0)
    for (const kind of wireKinds) {
      expect(enumNames, `card kind ${kind} is not a core DraftObjectType`).toContain(kind)
    }
    // than the strategy family; the gate records the two counts instead of
    // forcing either side to hardcode the other.
    expect(wireKinds.length).toBeLessThan(enumNames.length)
  })
})
