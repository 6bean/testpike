/**
 * stage 5 fix draft-card fold on reload (TC-AO lineage).
 *
 * Pinned rules:
 * - a landed draft ledger row riding a sealed message folds back into the
 * turn's repair cards through the ONE parser the live stream uses
 * (chatDockReducer.parseRepairDraft) — the mapper never re-invents a
 * second JSON fold (?raw contract below);
 * - a pending card reloads confirmable (draftId addresses the two click
 * doors), an applied card reloads with its ledger receipt (status /
 * appliedVersion / rolledBack) instead of pretending it is new;
 * - an anchored repair row whose optimistic base version never landed in
 * the ledger does NOT fold (a half-shape card the diff has nothing to
 * aim at must never appear);
 * - rows without drafts keep an honest empty list — a draft never leaks
 * onto its neighbour's turn.
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import { parseRepairDraft } from '@/composables/chatDockReducer'
import mapperRaw from '@/composables/chatHistoryMapper.ts?raw'

const ASSISTANT_BASE = {
  sessionId: 1, reasoning: null, status: 'done', seqFrom: 2, seqTo: 9,
  promptTokens: 10, completionTokens: 20,
}

function assistantRow(id: number, drafts?: NonNullable<Parameters<typeof mapHistoryToTurns>[0][number]['drafts']>) {
  return { id, role: 'assistant', content: '答案正文', ...ASSISTANT_BASE, drafts }
}

describe('reload draft-card fold (sealed ledger rows -> confirmable cards)', () => {
  it('a pending landed draft folds back into a confirmable card', () => {
    const turns = mapHistoryToTurns([assistantRow(12, [
      {
        id: 88, objectType: 'EVOLUTION_PROPOSAL', summary: '提示词模板修订提案',
        payloadJson: '{"subKind":"PROMPT_TPL","domain":"dialog","name":"dialog-tone"}',
        status: 1, targetId: null, appliedVersion: null, rolledBack: false, baseVersion: null,
      },
    ])], 0)
    expect(turns[0].repairs).toHaveLength(1)
    const card = turns[0].repairs[0]
    expect(card.draftId).toBe(88)
    expect(card.objectType).toBe('EVOLUTION_PROPOSAL')
    expect(card.subKind).toBe('PROMPT_TPL')
    expect(card.targetName).toBe('dialog-tone')
    expect(card.actionJson).toContain('PROMPT_TPL')
    expect(card.landed?.status).toBe(1)
  })

  it('an applied draft rides back with its ledger receipt, never a client claim', () => {
    const turns = mapHistoryToTurns([assistantRow(13, [
      {
        id: 89, objectType: 'CASE_WRITE', summary: '用例修改草案',
        payloadJson: '{"title":"t"}', status: 2, targetId: 501,
        appliedVersion: 7, rolledBack: false, baseVersion: 4,
      },
    ])], 0)
    expect(turns[0].repairs).toHaveLength(1)
    const card = turns[0].repairs[0]
    expect(card.caseId).toBe(501)
    expect(card.baseVersion).toBe(4)
    expect(card.landed).toEqual({ status: 2, appliedVersion: 7, rolledBack: false })
  })

  it('a rolled-back applied card keeps the once-only mark through the fold', () => {
    const turns = mapHistoryToTurns([assistantRow(14, [
      {
        id: 90, objectType: 'EVOLUTION_PROPOSAL', summary: '提示词模板修订提案',
        payloadJson: '{"subKind":"PROMPT_TPL","name":"a"}', status: 2, targetId: null,
        appliedVersion: 5, rolledBack: true, baseVersion: null,
      },
    ])], 0)
    expect(turns[0].repairs[0].landed?.rolledBack).toBe(true)
  })

  it('an evolution card never borrows the case chip: targetId is a template row, not a case', () => {
    // Real defect found by the stage-5 vision pass: the applied prompt-family
    // draft carries target_id = tp_prompt_template row id; the reload fold
    // /cases/741 — a wrong-object jump link. EVOLUTION_PROPOSAL is
    // anchor-free: its receipt names the template, the chip must stay off.
    const turns = mapHistoryToTurns([assistantRow(15, [
      {
        id: 91, objectType: 'EVOLUTION_PROPOSAL', summary: '提示词模板修订提案',
        payloadJson: '{"subKind":"PROMPT_TPL","name":"dialog-answer-prompt"}',
        status: 2, targetId: 741, appliedVersion: 2, rolledBack: false, baseVersion: null,
      },
    ])], 0)
    const card = turns[0].repairs[0]
    expect(card.objectType).toBe('EVOLUTION_PROPOSAL')
    expect(card.caseId).toBe(0)
    expect(card.targetName).toBe('dialog-answer-prompt')
  })

  it('an anchored repair row without its base version never folds', () => {
    const turns = mapHistoryToTurns([assistantRow(15, [
      {
        id: 91, objectType: 'SCRIPT_REPAIR', summary: '脚本修复草案',
        payloadJson: '{"steps":[]}', status: 1, targetId: 5,
        appliedVersion: null, rolledBack: false, baseVersion: null,
      },
    ])], 0)
    expect(turns[0].repairs).toHaveLength(0)
  })

  it('drafts fold only onto their own message and never leak to neighbours', () => {
    const turns = mapHistoryToTurns([
      { id: 16, role: 'user', content: '问题', sessionId: 1, reasoning: null, status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0, drafts: [] },
      assistantRow(17, [
        {
          id: 92, objectType: 'CASE_WRITE', summary: '用例新建草案',
          payloadJson: '{"title":"t"}', status: 1, targetId: null,
          appliedVersion: null, rolledBack: false, baseVersion: null,
        },
      ]),
    ], 0)
    expect(turns[0].repairs).toHaveLength(0)
    expect(turns[1].repairs).toHaveLength(1)
  })

  it('the mapper folds through the single shared parser with no second JSON fold', () => {
    expect(mapperRaw).toContain('parseRepairDraft')
    expect(mapperRaw).not.toMatch(/JSON\.parse\(/)
  })
})

describe('requirement-write draft fold (AL batch writeback cards)', () => {
  // D10 (vision pass): a REQUIREMENT_WRITE ledger row was NOT in the reducer's
  // registered-kind set, so the fold defaulted it to SCRIPT_REPAIR — the card
  // titled as a script repair, fetched the anchored id as a CASE script, and
  // fold as its own family: anchor-free (a dialog create has no row yet),
  // no case chip, and the requirement anchor rides its own field.
  it('an edit requirement draft folds as its own family without borrowing the case chip', () => {
    const turns = mapHistoryToTurns([assistantRow(16, [
      {
        id: 92, objectType: 'REQUIREMENT_WRITE', summary: '编辑需求点 #705',
        payloadJson: '{"title":"t2","acceptanceCriteria":"ac"}',
        status: 2, targetId: 705, appliedVersion: 4, rolledBack: false, baseVersion: 3,
      },
    ])], 0)
    expect(turns[0].repairs).toHaveLength(1)
    const card = turns[0].repairs[0]
    expect(card.objectType).toBe('REQUIREMENT_WRITE')
    expect(card.caseId).toBe(0)
    expect(card.requirementId).toBe(705)
    expect(card.actionKind).toBe('edit')
    expect(card.landed).toEqual({ status: 2, appliedVersion: 4, rolledBack: false })
  })

  it('a create requirement draft folds without any row anchor', () => {
    const turns = mapHistoryToTurns([assistantRow(17, [
      {
        id: 93, objectType: 'REQUIREMENT_WRITE', summary: '新建需求点「登录风控」',
        payloadJson: '{"title":"登录风控"}',
        status: 1, targetId: null, appliedVersion: null, rolledBack: false, baseVersion: null,
      },
    ])], 0)
    expect(turns[0].repairs).toHaveLength(1)
    const card = turns[0].repairs[0]
    expect(card.objectType).toBe('REQUIREMENT_WRITE')
    expect(card.requirementId).toBe(0)
    expect(card.actionKind).toBe('create')
  })

  it('the live meta wire shape (targetId, no caseId alias) folds the same card', () => {
    // Emitted by ai-service writeDraftMeta: the generic anchor key is
    // targetId; the caseId alias rides only for CASE_WRITE. Both the frame
    // stream and the reload synthesis must land the same folded family —
    // one folding semantics (the mapper's pinned rule).
    const card = parseRepairDraft(JSON.stringify({
      draftId: 94, objectType: 'REQUIREMENT_WRITE', targetId: 705, baseVersion: 3,
      toolName: 'requirement.update', actionJson: '{"title":"t"}',
    }), '编辑需求点 #705')
    expect(card).not.toBeNull()
    expect(card!.objectType).toBe('REQUIREMENT_WRITE')
    expect(card!.requirementId).toBe(705)
    expect(card!.caseId).toBe(0)
  })
})
