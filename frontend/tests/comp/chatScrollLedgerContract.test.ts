/**
 * Long-session scroll ledger contract .
 *
 * The windowed thread already keeps DOM small (TC-AO-06 proves the 500-row
 * window live); this batch adds the felt-smooth half: a scroll frame over
 * the hot-key budget must surface as an honest attribute instead of being
 * silently swallowed, and the window size the DOM bound is measured against
 * must be observable from the DOM itself (no second source for the bound).
 *
 * Same ?raw source-pinning posture as chatComposerEnterContract: the live
 * behaviour is gated by tests/e2e/test_chat_long_session_spectrum.py; this
 * file keeps the wiring from being silently dropped by a later refactor.
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import budgetRaw from '@/composables/chatScrollBudget.ts?raw'

describe('chat scroll-frame ledger', () => {
  it('the scroller publishes the window size and the live budget as DOM attributes', () => {
    expect(panelRaw).toMatch(/:data-window-size="windowSize"/)
    expect(panelRaw).toMatch(/:data-scroll-budget="chatScrollBudgetMs"/)
    expect(panelRaw).toMatch(/:data-scroll-over="scrollOverBudget \? 'true' : undefined"/)
  })

  it('every scroll burst samples one frame against the budget', () => {
    expect(panelRaw).toMatch(/function onBodyScroll\(\)[\s\S]{0,400}sampleScrollFrame\(\)/)
    expect(panelRaw).toMatch(/performance\.now\(\) - origin > chatScrollBudgetMs\.value/)
    // a clean thread never re-latches once honest accounting flagged it
    expect(panelRaw).toMatch(/scrollOverBudget\.value = false/)
  })

  it('the budget key is read through one composable with the 16ms default', () => {
    expect(budgetRaw).toMatch(/chat\.scroll-budget-ms/)
    expect(budgetRaw).toMatch(/SCROLL_BUDGET_DEFAULT_MS = 16/)
    // R32 posture: remote value only with the config permission, fail-soft default
    expect(budgetRaw).toMatch(/perm:system:system-config/)
  })
})
