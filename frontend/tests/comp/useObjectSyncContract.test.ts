/**
 * BG batch — single-source page-consumption contract.
 *
 * Iron rules 2 and 3 of the BG ruling file: a page that displays a landed
 * object must re-read it silently (no toast, no confirm, no page jump), and
 * an open editor must swap only the fields the user has not touched while
 * keeping their draft input — the version baseline advances so a later save
 * rides the existing column-level merge instead of a false 40901 refusal.
 *
 * This file pins the shared composable pages consume (useObjectSync) plus the
 * field-level baseline merge helper, in real Chromium against the real
 * bus — the wiring pages may not copy this logic (R40).
 */
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { emitObjectSync, subscribeObjectSync, syncSubscriberCount } from '@/composables/chatSyncBus'
import {
  fieldSnapshots,
  mergeUntouchedFields,
  useObjectSync,
  type ChatObjectSyncEvent,
} from '@/composables/useObjectSync'

function mountConsumer(options: Parameters<typeof useObjectSync>[0]) {
  return mount(defineComponent({
    setup() {
      useObjectSync(options)
      return () => null
    },
  }))
}

describe('useObjectSync (BG T630 consumption single source)', () => {
  it('subscribes while mounted, filters watch-set and anchor, unhooks on unmount', async () => {
    const base = syncSubscriberCount()
    const seen: ChatObjectSyncEvent[] = []
    const host = mountConsumer({
      watch: ['CASE_WRITE', 'SCRIPT_REPAIR'],
      anchor: (e) => e.objectId === 42,
      onSync: (e) => seen.push(e),
    })
    expect(syncSubscriberCount()).toBe(base + 1)
    emitObjectSync({ objectType: 'TAG_WRITE', projectId: 7, objectId: 42 })
    emitObjectSync({ objectType: 'CASE_WRITE', projectId: 7, objectId: 99 })
    emitObjectSync({ objectType: 'CASE_WRITE', projectId: 7, objectId: 42 })
    await expect.poll(() => seen.length, { timeout: 400 }).toBe(1)
    expect(seen[0]).toEqual({ objectType: 'CASE_WRITE', projectId: 7, objectId: 42 })
    host.unmount()
    expect(syncSubscriberCount()).toBe(base)
    emitObjectSync({ objectType: 'CASE_WRITE', projectId: 7, objectId: 42 })
    await new Promise((r) => setTimeout(r, 120))
    expect(seen).toHaveLength(1)
  })

  it('without an anchor predicate every watched-type landing reaches the page', async () => {
    const seen: string[] = []
    const host = mountConsumer({
      watch: ['CASE_WRITE'],
      onSync: (e) => seen.push(e.objectType),
    })
    emitObjectSync({ objectType: 'CASE_WRITE', projectId: null, objectId: null })
    emitObjectSync({ objectType: 'PLAN_WRITE', projectId: null, objectId: null })
    await expect.poll(() => seen.length, { timeout: 400 }).toBe(1)
    host.unmount()
  })

  it('delivers cross-tab channel traffic through the same subscription', async () => {
    const seen: ChatObjectSyncEvent[] = []
    const un = subscribeObjectSync((e) => seen.push(e))
    const channel = new BroadcastChannel('tp-chat-object-sync')
    channel.postMessage({ objectType: 'CASE_WRITE', projectId: 1, objectId: 5, __tab: 'foreign-tab' })
    await expect.poll(() => seen.length, { timeout: 500 }).toBe(1)
    expect(seen[0].objectId).toBe(5)
    channel.close()
    un()
  })
})

describe('mergeUntouchedFields (BG iron rule 3)', () => {
  it('swaps untouched fields, keeps user-touched drafts, counts applications', () => {
    const form = { title: 'server', steps: 'a', priority: 1 }
    const baseline = fieldSnapshots(form)
    form.steps = 'user draft'
    const applied = mergeUntouchedFields(
      form as unknown as Record<string, unknown>,
      baseline,
      { title: 'landed title', steps: 'landed steps', priority: 1 },
    )
    expect(applied).toBe(1)
    expect(form.title).toBe('landed title')
    expect(form.steps).toBe('user draft')
    expect(form.priority).toBe(1)
  })

  it('advances the version baseline only for fields the user has not touched', () => {
    // Untouched version field rides the new server number into the map.
    const form = { version: 4, title: 'server title' }
    const baseline = fieldSnapshots(form)
    const appliedClean = mergeUntouchedFields(
      form as unknown as Record<string, unknown>,
      baseline,
      { version: 9, title: 'server title' },
    )
    expect(appliedClean).toBe(1)
    expect(form.version).toBe(9)
    expect(baseline.version).toBe('9')
    // A user-typed sibling field survives; only its own key stays dirty.
    const userForm = { version: 4, title: 'stale server title' }
    const userBaseline = fieldSnapshots(userForm)
    userForm.title = 'typing'
    const appliedDirty = mergeUntouchedFields(
      userForm as unknown as Record<string, unknown>,
      userBaseline,
      { version: 9, title: 'fresh server title' },
    )
    expect(appliedDirty).toBe(1)
    expect(userForm.version).toBe(9)
    expect(userForm.title).toBe('typing')
    expect(userBaseline.title).toBe('"stale server title"')
  })

  it('skips absent fresh keys and reports zero when everything is dirty', () => {
    const form = { a: 1, b: 2 }
    const baseline = fieldSnapshots(form)
    form.a = 99
    expect(mergeUntouchedFields(form as unknown as Record<string, unknown>, baseline, { b: undefined })).toBe(0)
    expect(form.b).toBe(2)
    const untouched = { a: 1, b: 2 }
    const untouchedBase = fieldSnapshots(untouched)
    expect(mergeUntouchedFields(untouched as unknown as Record<string, unknown>, untouchedBase, { b: 5 })).toBe(1)
    expect(untouched.b).toBe(5)
  })
})
