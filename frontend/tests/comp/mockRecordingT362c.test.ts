/**
 * layout contract for the new cassette-recording page
 * (?raw source-scan mirror of the mockHeaderLayoutT396 posture).
 * Contract authorities:
 * - AK/ layout law: header-actions carries no selector and no help slot;
 * the space picker + refresh live in the filter-bar row;
 * - ownership-echo iron rule: the open-box dialog holds a read-only
 * ProjectOwnerEcho and the create entry is gated by !projectId;
 * - global context: the page consumes useProjectPageContext (deep-link
 * ?projectId lands on entry, no second selector copy);
 * - playback plane: preview GET carries mode=strict|fuzzy, the confirm call
 * is the POST playback through the admin bridge only (no direct core).
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/mock/MockRecordingView.vue?raw'
import apiRaw from '@/api/mock?raw'

describe('T362c recording page: layout law', () => {
  it('keeps zero selectors and no help slot in the action region (R23 stage-2: shell retired; the write entry rides the filter-bar leading slot)', () => {
    // R23 stage-2: the title shell and its header-actions row are gone.
    expect(viewRaw).not.toMatch(/<div class="header-actions">/)
    expect(viewRaw).not.toMatch(/data-test="mock-recording-open"[\s\S]{0,60}?<el-select/)
    expect(viewRaw).not.toMatch(/<el-icon[^>]*mock-recording-help-btn/)
  })

  it('space screening lives in the filter-bar row with refresh in filter-bar-actions', () => {
    expect(viewRaw).toMatch(/<div[^>]*class="filter-bar"[^>]*>[\s\S]*?<el-select[\s\S]*?recording-space-select/)
    expect(viewRaw).toContain('filter-bar-actions')
  })

  it('consumes the global project context through useProjectPageContext', () => {
    expect(viewRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(viewRaw).toMatch(/const \{ projectId[^}]*\} = useProjectPageContext\(\)/)
  })

  it('open-box dialog echoes ownership and the entry is gated without a project', () => {
    expect(viewRaw).toContain('ProjectOwnerEcho')
    // the create entry must hard-gate on the missing header project (the
    // space pick gating rides the same disabled expression)
    expect(viewRaw).toMatch(/:disabled="!projectId(\s*\|\|[^"]*)?"/)
  })
})

describe('T362c recording page: api mirror of the admin bridge', () => {
  it('api/mock.ts mirrors the six recording endpoints on the spaces sub-path', () => {
    expect(apiRaw).toContain('/mock/spaces/${spaceId}/recordings')
    expect(apiRaw).toContain('/playback/preview')
    expect(apiRaw).toContain('export function listRecordings')
    expect(apiRaw).toContain('export function startRecording')
    expect(apiRaw).toContain('export function stopRecording')
    expect(apiRaw).toContain('export function previewPlayback')
    expect(apiRaw).toContain('export function playRecordingBack')
  })

  it('playback reuses the single draft mirror (red line 6: one MockStubDraftItem, no second copy)', () => {
    const seg = /export interface MockPlaybackResult \{([\s\S]*?)\}/.exec(apiRaw)
    expect(seg, 'MockPlaybackResult mirror of the admin view must exist').not.toBeNull()
    expect(seg![1]).toContain('createdStubIds')
    expect(seg![1]).toContain('skippedEndpointKeys')
    expect(seg![1]).toContain('promotedCount')
    // preview must return the existing draft interface, never a fork of it
    const fn = /export function previewPlayback[\s\S]*?Promise<MockStubDraftItem\[\]>/.exec(apiRaw)
    expect(fn).not.toBeNull()
  })

  it('recording box mirror stays field-for-field with the admin MockRecordingView', () => {
    const seg = /export interface MockRecordingItem \{([\s\S]*?)\}/.exec(apiRaw)
    expect(seg, 'MockRecordingItem must exist').not.toBeNull()
    for (const field of ['id', 'spaceId', 'targetBaseUrl', 'status', 'summaryJson', 'cassetteRef', 'promotedCount', 'startedAt', 'endedAt']) {
      expect(seg![1]).toContain(field)
    }
  })
})
