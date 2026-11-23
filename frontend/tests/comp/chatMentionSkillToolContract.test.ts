/**
 * @-mention skill/tool candidate contract .
 *
 * The palette must offer exactly what the run can consume: the admin door
 * GET /ai/chat/projects/{id}/mention-candidates returns the core assembly
 * rows (skills then tools, server order), and the composer merges them into
 * the existing grouped popup. Red lines pinned here:
 * - the chip stays the single truth of a reference (payload derives from
 * chips only — typed "@text" is never parsed);
 * - skill/tool ride the SAME (kind,id,title) triple as the four existing
 * families (one channel, no second reference mechanism);
 * - the candidate door rides the shared request wrapper (JWT + /api/v1
 * proxy) — the browser never talks to ai-service directly;
 * - a down candidate door degrades honestly: the other groups still land
 * (Promise.allSettled posture), the popup never goes blank pretending the
 * set is complete;
 * - both locale files carry the two new group words (i18n dual track).
 *
 * Same ?raw source-pinning posture as chatComposerEnterContract: the live
 * behaviour is covered by the E2E spectrum; this contract keeps a later
 * refactor from silently dropping the wiring.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import candidatesRaw from '@/composables/chatObjectCandidates.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('mention-candidate door (T464 skill/tool)', () => {
  it('the reference channel widens to skill and tool kinds', () => {
    expect(apiRaw).toMatch(/kind: 'case' \| 'defect' \| 'batch' \| 'doc' \| 'skill' \| 'tool'/)
  })

  it('the candidates ride the admin door through the shared wrapper', () => {
    expect(apiRaw).toMatch(/export function fetchMentionCandidates/)
    expect(apiRaw).toMatch(/\/ai\/chat\/projects\/\$\{projectId\}\/mention-candidates/)
    // No direct ai-service door: the module never carries the raw port or a
    // hard-coded base URL (service-tiering red line — prose mentioning the
    // service name in a comment is fine, wiring it is not).
    expect(apiRaw).not.toMatch(/18084|http:\/\/localhost|baseURL/)
  })

  it('the assembler fetches the skill/tool door as a fourth settled group', () => {
    expect(candidatesRaw).toMatch(/fetchMentionCandidates/)
    // Four doors, still allSettled: one down never poisons the others.
    expect(candidatesRaw).toMatch(/Promise\.allSettled/)
    expect(candidatesRaw).toMatch(/fetchMentionCandidates\(projectId\),/)
  })

  it('the popup keeps skills before tools (server order, never shuffled)', () => {
    // The merge preserves the assembly order core returned — the palette is
    // deterministic between two identical opens (rows land in arrival order,
    // no client-side re-sorting that could invent a second visibility
    // verdict), capped per family at the same head size as other groups.
    expect(candidatesRaw).toMatch(/for \(const c of mention\.value\) \{/)
    expect(candidatesRaw).not.toMatch(/mention\.value\.sort/)
  })
})

describe('i18n dual track for the two new groups', () => {
  it('both locale files carry groupSkill and groupTool', () => {
    expect(zhRaw).toMatch(/groupSkill: '技能'/)
    expect(zhRaw).toMatch(/groupTool: '工具'/)
    expect(enRaw).toMatch(/groupSkill: 'Skills'/)
    expect(enRaw).toMatch(/groupTool: 'Tools'/)
  })
})
