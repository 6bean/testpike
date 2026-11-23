/**
 * P5 dict contract: every AI task status has a rendered label.
 *
 * The task cards read `AI_TASK_STATUS[status]?.label ?? status`, so a status
 * missing from the dictionary renders as a bare number — the user sees "5"
 * instead of a word for a task they just stopped. Covers the five backend
 * states (queued / generating / done / failed / cancelled) and pins that the
 * cancelled label resolves through the dictionary translation seam (the
 * locale key has a real consumer, no orphan entry).
 */
import { describe, expect, it } from 'vitest'
import { AI_TASK_STATUS } from '@/api/ai'
import aiApiRaw from '@/api/ai?raw'

describe('AI task status label dictionary', () => {
  it('covers all five backend task states including the cancelled door', () => {
    expect(Object.keys(AI_TASK_STATUS).sort()).toEqual(['1', '2', '3', '4', '5'])
  })

  it('renders a word (never a raw number) for a stopped task', () => {
    const cancelled = AI_TASK_STATUS[5]
    expect(cancelled).toBeDefined()
    expect(cancelled.label).toBe('已停止')
    expect(String(cancelled.label)).not.toBe('5')
  })

  it('keeps every status label behind the translation seam', () => {
    for (const [status, entry] of Object.entries(AI_TASK_STATUS)) {
      expect(entry.label, `status ${status}`).toBeTruthy()
      expect(entry.label, `status ${status}`).not.toMatch(/^dict\./)
      expect(['info', 'warning', 'success', 'danger']).toContain(entry.type)
    }
  })

  it('consumes the cancelled dictionary key so the locale entry is not an orphan', () => {
    expect(aiApiRaw).toContain("td('dict.ai.task.cancelled')")
  })
})
