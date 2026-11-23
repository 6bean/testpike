/**
 * Chat scroll-frame budget .
 *
 * The windowed thread keeps DOM small, but a scroll frame that takes longer
 * than the budget is still a felt stutter. This composable owns ONE reading
 * of the governance key `chat.scroll-budget-ms` (K8 hot-updated, factory 16
 * — the classic 60fps frame budget) and exposes it to ChatPanel's scroll
 * accounting so no component copies the key or the default (R40).
 *
 * Reading posture mirrors chatPasteEnhance.loadPasteTableConvertSwitch: the
 * permission gates the REMOTE value only — without it (or on any failure)
 * the default keeps the accounting honest either way (fail-soft, R32).
 */
import { ref } from 'vue'
import { listConfigs } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

const SCROLL_BUDGET_KEY = 'chat.scroll-budget-ms'
export const SCROLL_BUDGET_DEFAULT_MS = 16

/** Frame budget in ms as read live; default until (and unless) the key lands. */
export const chatScrollBudgetMs = ref<number>(SCROLL_BUDGET_DEFAULT_MS)

let budgetLoadStarted = false

/**
 * Pull the budget once per page load. A key outside 1..1000 (typo or stale
 * row) is ignored — the server-side write gate owns range enforcement; the
 * client keeps the default rather than amplifying a bad value.
 */
export async function loadChatScrollBudget(): Promise<void> {
  if (budgetLoadStarted) return
  if (!useAuthStore().hasPerm('perm:system:system-config')) return
  budgetLoadStarted = true
  try {
    // Group literal = the tp_sys_config seed contract (\u7cfb\u7edf = the
    // shared "system" group the other R32 feature switches read).
    const rows = await listConfigs('\u7cfb\u7edf')
    const raw = Number(rows.find((r) => r.configKey === SCROLL_BUDGET_KEY)?.configValue)
    if (Number.isFinite(raw) && raw >= 1 && raw <= 1000) chatScrollBudgetMs.value = raw
  } catch {
    // fail-soft: the default budget keeps the honest ledger meaningful.
  }
}

/** Test seam: forget the latch so a fresh mount can re-read the key. */
export function resetChatScrollBudgetLoad(): void {
  budgetLoadStarted = false
  chatScrollBudgetMs.value = SCROLL_BUDGET_DEFAULT_MS
}
