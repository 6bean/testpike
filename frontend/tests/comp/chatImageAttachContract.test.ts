/**
 * Chat composer user-image attach contract (AO batch P4 ,
 * the door, the payload channel and the two-locale sentences must never be
 * silently dropped by a later refactor. Same ?raw source-pinning posture as
 * chatComposerEnterContract: the live behaviour itself is covered by the
 * E2E spectrum; this guard keeps the shape.
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('composer photo attach door', () => {
  it('renders the attach button and the hidden file input with data-test anchors', () => {
    expect(panelRaw).toMatch(/data-test="chat-image-attach"/)
    expect(panelRaw).toMatch(/data-test="chat-image-input"/)
    expect(panelRaw).toMatch(/accept="image\/png,image\/jpeg,image\/gif,image\/webp"/)
  })

  it('the door is disabled without a header slice (named pointer, zero silent no-op)', () => {
    expect(panelRaw).toMatch(/data-test="chat-image-attach"[\s\S]{0,160}:disabled="ctx\.primaryId == null \|\| running"/)
  })

  it('picked files upload through the door immediately and the strip shows each state', () => {
    expect(panelRaw).toMatch(/uploadChatImage\(projectId, file\)/)
    expect(panelRaw).toMatch(/data-test="chat-image-strip"/)
    expect(panelRaw).toMatch(/chat-image-item-\$\{img\.status\}/)
  })

  it('send carries ready images on the stream body and consumes the strip', () => {
    expect(panelRaw).toMatch(/attachments: imagePayload\.length > 0 \? imagePayload : undefined/)
    expect(panelRaw).toMatch(/pendingImages\.value = \[\]/)
  })

  it('an in-flight upload blocks the send with a named hint; failed picks drop by name', () => {
    expect(panelRaw).toMatch(/p\.status === 'uploading'[\s\S]{0,120}layout\.chatDock\.image\.uploadingTip/)
    expect(panelRaw).toMatch(/layout\.chatDock\.image\.droppedTip/)
  })

  it('switching the project slice drops pending images with the chips (same reset point)', () => {
    expect(panelRaw).toMatch(/refs\.value = \[\]\s*\n\s*mentionItems\.value = \[\]\s*\n\s*pendingImages\.value = \[\]/)
  })
})

describe('stream wire mirror', () => {
  it('the request shape gained the optional attachments channel (resume never carries it)', () => {
    expect(apiRaw).toMatch(/attachments\?: ChatImagePayload\[\]/)
    expect(apiRaw).toMatch(/interface ChatImagePayload \{/)
  })

  it('upload rides the shared multipart helper against the admin door', () => {
    expect(apiRaw).toMatch(/postMultipart<ChatImageView>\('\/ai\/chat\/images', file, \{ projectId: String\(projectId\) \}\)/)
  })
})

describe('two-locale parity', () => {
  const keys = ['attachTip', 'uploadingSuffix', 'uploadingTip', 'uploadFailed', 'droppedTip']
  it.each(keys)('layout.chatDock.image.%s exists in zh and en', (k) => {
    const block = (raw: string) => raw.match(/image: \{([\s\S]{0,600}?)\},\n/) as RegExpMatchArray
    expect(block(zhRaw)[1]).toContain(`${k}:`)
    expect(block(enRaw)[1]).toContain(`${k}:`)
  })
})
