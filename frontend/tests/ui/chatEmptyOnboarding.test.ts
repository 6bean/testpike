/**
 * empty-ledger onboarding and the click-count contract.
 *
 * Contract (ruling 16 §97 : independent-page sidebar keeps New at the
 * column head; the empty ledger guides STRAIGHT into a new conversation;
 * the "menu/dock to first question" click ledger is revised in-batch):
 * - ChatSessionList empty state (active face, project segment present)
 * carries a direct new-conversation action — data-test session-empty-new,
 * labelled with the ONE sessionList.newChat key (same concept, same word,
 * no second string), and it rides the SAME create door as the toolbar
 * button (the host owns the single newConversation owner). The archived
 * face and the no-slice face stay honest hints: no dead button pointing
 * at a creation the current context cannot serve.
 * - The standalone page pins the library column head: the aside renders the
 * shared ChatSessionList, whose toolbar (New first) precedes the scroller
 * in template order — the button never scrolls away with the rows.
 * - Click ledger: from the dock header New or from the empty-ledger
 * direct button, ONE activation lands the user on the composer —
 * newConversation collapses the history view, focuses the input and never
 * navigates anywhere (no router hop between the door and the composer).
 *
 * Wiring assertions are source-as-text (the chatDockPin precedent), so the
 * ledger holds even when the components render under stubs.
 */
import { describe, expect, it } from 'vitest'
import listRaw from '@/components/ChatSessionList.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import viewRaw from '@/views/ai/AiChatView.vue?raw'

describe('empty ledger guides straight into a new conversation', () => {
  it('the active empty state carries the direct new action on the ONE door', () => {
    expect(listRaw).toContain('session-empty-new')
    // rides the shared create door (the host's newConversation owner), never a
    // second creation path inside the list itself
    const door = listRaw.slice(listRaw.indexOf('session-empty-new'))
    expect(door.slice(0, 400)).toContain("emit('create')")
    // one concept, one word — reuses the toolbar's locale key
    expect(door.slice(0, 400)).toContain('sessionList.newChat')
  })

  it('the button is gated: archived face and missing slice never show a dead door', () => {
    const anchor = listRaw.indexOf('session-empty-new')
    const block = listRaw.slice(anchor - 320, anchor + 320)
    expect(block).toContain('!archived')
    expect(block).toContain('ctx.primaryId')
  })

  it('the column head stays pinned: toolbar (New first) precedes the scroller', () => {
    expect(listRaw.indexOf('session-toolbar')).toBeLessThan(listRaw.indexOf('session-scroller'))
    expect(listRaw.indexOf('session-new')).toBeLessThan(listRaw.indexOf('session-search'))
    // the standalone page mounts the shared piece as the side column's content
    const aside = viewRaw.indexOf('chat-page-side')
    const list = viewRaw.indexOf('ChatSessionList', aside)
    const main = viewRaw.indexOf('chat-page-main')
    expect(aside).toBeGreaterThanOrEqual(0)
    expect(list).toBeGreaterThan(aside)
    expect(list).toBeLessThan(main)
  })
})

describe('click ledger from dock to first question (one activation)', () => {
  it('both new-conversation doors land on the composer, not a second view', () => {
    // header New (main position, and the history list's create door
    // both call the SAME owner — no third path
    expect(panelRaw).toContain('@create="newConversation"')
    expect(panelRaw).toContain('data-test="chat-dock-new"')
    const fn = panelRaw.slice(panelRaw.indexOf('function newConversation'))
    const body = fn.slice(0, fn.indexOf('\n}\n') + 2)
    // one click collapses the ledger view back to the thread…
    expect(body).toContain('historyOpen.value = false')
    // …focuses the composer (the first question is a keystroke away)…
    expect(body).toContain('composerEl.value?.focus')
    // …and never navigates away (a router hop would add a page-change to the ledger)
    expect(body).not.toContain('router.push')
  })

  it('the composer renders on the thread side only (history view holds no input)', () => {
    // footer input band sits behind the !historyOpen gate: landing on the
    // thread after one activation is structural, not optimistic
    const footer = panelRaw.slice(panelRaw.indexOf('<footer'))
    expect(footer.slice(0, 200)).toContain('v-if="!historyOpen"')
    expect(panelRaw).toContain('data-test="chat-dock-input"')
  })
})
