// Contract tests for the question-generation wizard's mapping face:
// the pure functions in '@/api/evalSet' are the only place where a
// generated QA item becomes a publish sample, and where a stored sample
// is echoed back for carry-forward. The wizard dialog and the manual
// door both consume these helpers (R40 single source), so the field
// shape is pinned here instead of in browser tests.
import { describe, expect, it } from 'vitest'
import { qaItemToPublishSample, sampleToPublishInput } from '@/api/evalSet'

describe('qaItemToPublishSample (AW3 -> AW2 mapping)', () => {
  it('maps question/answer/anchor onto input/expected/referenceDocs', () => {
    const out = qaItemToPublishSample({
      question: '  密码策略有哪些要求？ ',
      answer: '至少 8 位并包含字母与数字。',
      sourceAnchor: 'chunk#12',
    })
    expect(out.input).toBe('密码策略有哪些要求？')
    expect(out.expected).toBe('至少 8 位并包含字母与数字。')
    expect(out.referenceDocs).toEqual(['chunk#12'])
    expect(out.source).toBe('ai-generated')
    expect(out.sensitive).toBe(0)
  })

  it('keeps a missing answer null and a blank anchor out of referenceDocs', () => {
    const out = qaItemToPublishSample({ question: 'q', sourceAnchor: '   ' })
    expect(out.expected).toBeNull()
    expect(out.referenceDocs).toBeNull()
  })

  it('never invents a source outside the core closed set', () => {
    const out = qaItemToPublishSample({ question: 'q', answer: 'a' })
    expect(['manual', 'batch-export', 'training-raise', 'ai-generated']).toContain(out.source)
  })
})

describe('sampleToPublishInput (carry-forward echo)', () => {
  it('passes stored fields through and defaults an empty source to manual', () => {
    const base = {
      id: 1, setName: 's', version: 2, snapshotDigest: null,
      input: 'i', expected: 'e', referenceDocs: ['r'],
      source: null, sensitive: null, createdBy: null, createdAt: null,
    }
    const out = sampleToPublishInput(base)
    expect(out).toEqual({ input: 'i', expected: 'e', referenceDocs: ['r'], source: 'manual', sensitive: 0 })
    const kept = sampleToPublishInput({ ...base, source: 'ai-generated', sensitive: 1 })
    expect(kept.source).toBe('ai-generated')
    expect(kept.sensitive).toBe(1)
  })
})
