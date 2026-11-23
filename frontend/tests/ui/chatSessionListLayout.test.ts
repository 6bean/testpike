/**
 * stage 2 — session-library layout contract (?raw pinning precedent =
 * chatDockWiring.test.ts; render behavior itself is covered by the E2E close
 * package).
 *
 * Pinned structural rules (adjudication § sixteen D1 + recon card ):
 * - the api layer ships list/rename/archive/group-days doors on the admin
 * path through the request wrapper — the browser NEVER talks to ai-service;
 * - the shared ChatSessionList consumes the server page contract
 * (page/pageSize/keyword) and the windowed list piece (500-session
 * worst-case scale, DOM rows stay inside one window);
 * - archived rows are fetched with an explicit status (core returns only
 * active rows when status is absent — the default face is not the full set);
 * - group buckets come from the hot-key door, never a hardcoded array in the
 * component;
 * - the dock list view carries NO in-page title row (R23: the name rides the
 * dock header only) and reuses the same ChatSessionList piece (zero copy);
 * - the standalone page mounts the list beside the conversation kernel and
 * drives it through exposed doors only (one kernel, no second state copy);
 * - zero CJK in component sources — bilingual locales carry the wording.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import listRaw from '@/components/ChatSessionList.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import pageRaw from '@/views/ai/AiChatView.vue?raw'

describe('session doors of the api layer', () => {
  it('pages, searches, renames, archives and reads buckets through the admin door', () => {
    expect(apiRaw).toMatch(/export function listChatSessions/)
    expect(apiRaw).toMatch(/url: '\/ai\/chat\/sessions', method: 'get'/)
    expect(apiRaw).toMatch(/export function renameChatSession/)
    expect(apiRaw).toMatch(/method: 'patch'/)
    expect(apiRaw).toMatch(/export function archiveChatSession/)
    expect(apiRaw).toMatch(/\/archive/)
    expect(apiRaw).toMatch(/export function getChatSessionGroupDays/)
    expect(apiRaw).toMatch(/group-days'/)
  })

  it('never reaches for ai-service directly', () => {
    expect(apiRaw).not.toMatch(/18084|8084/)
    expect(listRaw).not.toMatch(/18084|8084|localhost/)
  })
})

describe('shared session list piece', () => {
  it('windowizes rows instead of rendering the whole accumulation', () => {
    expect(listRaw).toMatch(/useWindowedList/)
  })

  it('asks the server for page/pageSize/keyword slices', () => {
    expect(listRaw).toMatch(/listChatSessions\(/)
    expect(listRaw).toMatch(/pageSize/)
    expect(listRaw).toMatch(/keyword/)
  })

  it('queries the archived face with an explicit status', () => {
    expect(listRaw).toMatch(/status: ARCHIVED/)
  })

  it('reads date-group buckets from the hot-key door, never a private constant', () => {
    expect(listRaw).toMatch(/getChatSessionGroupDays\(\)/)
    expect(listRaw).not.toMatch(/buckets\s*=\s*\[\s*1,\s*7,\s*30\s*\]/)
  })

  it('is fully i18n (zero CJK in the source)', () => {
    // eslint-disable-next-line no-control-regex
    expect(listRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

describe('dock list view (whole-body swap, no split, no title row)', () => {
  it('toggles between conversation and the shared list piece', () => {
    expect(panelRaw).toMatch(/historyOpen/)
    expect(panelRaw).toMatch(/ChatSessionList/)
  })

  // A template tag without a static import compiles to resolveComponent and
  // renders NOTHING at runtime (Vue only warns) — vue-tsc does not catch it.
  // Pinned here after the real field miss: the dock list silently failed to
  // mount for exactly this reason.
  it('imports the shared list piece statically (a template tag alone is not enough)', () => {
    expect(panelRaw).toMatch(/import ChatSessionList from '@\/components\/ChatSessionList\.vue'/)
  })

  it('opens and seals conversations through its own single state', () => {
    expect(panelRaw).toMatch(/defineExpose/)
    expect(panelRaw).toMatch(/openSession/)
    expect(panelRaw).toMatch(/newConversation/)
  })
})

describe('standalone page mounts the list beside the kernel', () => {
  it('two-column shape: session list + conversation panel, one source each', () => {
    expect(pageRaw).toMatch(/ChatSessionList/)
    expect(pageRaw).toMatch(/ChatPanel/)
    expect(pageRaw).toMatch(/openSession/)
  })
})
