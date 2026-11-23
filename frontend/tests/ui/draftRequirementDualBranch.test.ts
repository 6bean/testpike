/**
 * Dual-payload requirement draft-card contract (AL batch , the either-or
 * door burned into the card by the shared duplicate gate).
 *
 * Pinned rules:
 * - a woven {create, update} payload folds its readable fields off the
 * create branch (both branches write the same content, only the landing
 * row differs) and shows two named doors: Accept change #id (the
 * recommended primary) and Accept new;
 * - the branch choice rides the apply body end to end: the frontend api
 * mirror, the admin request DTO, the remote payload record, and the core
 * request DTO all carry the selectedAction field;
 * - the two-step confirmation door is untouched: a branch click only opens
 * the one-shot ticket first (red line: nothing ever self-applies — Enter
 * defaults to the recommended branch choice, not to a write).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('requirement dual-payload branch door', () => {
  it('folds the woven payload and names both branch doors', () => {
    expect(cardRaw).toContain('requirementDual')
    expect(cardRaw).toContain('chat-draft-branch-update')
    expect(cardRaw).toContain("chooseBranch('update')")
    expect(cardRaw).toContain('chat-draft-branch-create')
    expect(cardRaw).toContain("chooseBranch('create')")
    expect(cardRaw).toContain('chat-draft-dual-diff')
  })

  it('rides the chosen branch onto the apply body through the shared mirror', () => {
    expect(apiRaw).toContain('selectedAction?: \'create\' | \'update\'')
    expect(apiRaw).toContain('data: { confirmToken, selectedAction }')
    expect(cardRaw).toContain('switchBranch')
    // the ticket still gates the write: a branch click only opens it
    expect(cardRaw).toContain('requestDraftConfirmTicket')
  })

  it('keeps the Enter door on the recommended branch, never an auto-apply', () => {
    expect(cardRaw).toContain('@keydown.enter="onEnter"')
    expect(cardRaw).toContain("if (stage.value === 'idle') chooseBranch('update')")
  })

  it('walks the new branch wording in both locales', () => {
    for (const key of ['branchUpdate', 'branchCreate', 'applyBranch', 'dualHint', 'dualDiff']) {
      expect(lookup(zhCN, `layout.chatDock.draft.${key}`)).toBeTruthy()
      expect(lookup(enUS, `layout.chatDock.draft.${key}`)).toBeTruthy()
    }
  })

  it('transport mirrors the branch field admin-side and core-side', () => {
    const root = new URL('../../../', import.meta.url)
    const read = (path: string) => readFileSync(new URL(path, root), 'utf8')
    expect(read('admin/src/main/java/com/sixbean/testpike/admin/aichat/api/dto/DraftApplyRequest.java'))
      .toContain('String selectedAction')
    expect(read('admin/src/main/java/com/sixbean/testpike/admin/aichat/infrastructure/remote/client/ApplyDraftPayload.java'))
      .toContain('String selectedAction')
    expect(read('core/src/main/java/com/sixbean/testpike/core/aichat/api/dto/ApplyDraftRequest.java'))
      .toContain('String selectedAction')
  })
})
