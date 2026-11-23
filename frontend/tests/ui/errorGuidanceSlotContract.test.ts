/**
 * R63 frontend mechanism contract :
 * the next-step slot must show whenever guidance exists and must NOT render an
 * empty shell when it does not. Both directions asserted — a slot that only
 * asserts presence would pass a permanently-rendering bug.
 */
import { describe, expect, it } from 'vitest'
import { parseErrorGuidance, buildGuidanceHtml, escapeHtml } from '../../src/utils/errorGuidance'

describe('parseErrorGuidance - R63 split', () => {
  it('splits a fully-formed guidance sentence (action word + path literal)', () => {
    const { reason, guidance } = parseErrorGuidance(
      'LLM 服务未配置，无法发起分析。下一步：可在「系统设置 → AI 配置」打开参数 ai.llm.redact-enabled 后重试',
    )
    expect(reason).toContain('LLM 服务未配置')
    expect(guidance).toContain('「系统设置 → AI 配置」')
    expect(guidance).toContain('ai.llm.redact-enabled')
  })

  it('bare error without any pointer keeps guidance empty (slot must not render)', () => {
    const { reason, guidance } = parseErrorGuidance('保存失败')
    expect(reason).toBe('保存失败')
    expect(guidance).toBe('')
  })

  it('action word alone is not guidance (two-element rule, mirrors the gate)', () => {
    const { guidance } = parseErrorGuidance('请检查字段后重试')
    expect(guidance).toBe('')
  })

  it('config-key alone without an action word is not guidance', () => {
    const { guidance } = parseErrorGuidance('参数 ai.doc.group-max-tokens 超限')
    expect(guidance).toBe('')
  })

  it('never swallows the cause line: all-guidance input falls back to plain reason', () => {
    const raw = '可在「设置」打开参数 ai.x.y-z'
    const { reason, guidance } = parseErrorGuidance(raw)
    expect(reason).toBe(raw)
    expect(guidance).toBe('')
  })

  it('collects multiple guidance sentences and keeps the reason sentences apart', () => {
    const { reason, guidance } = parseErrorGuidance(
      '提取被拒。原文超过预算。建议在「文档管理」调整 ai.doc.group-max-tokens；或前往「系统设置 → 模型清单」提高 max_input_tokens',
    )
    expect(reason).toContain('提取被拒')
    expect(reason).toContain('原文超过预算')
    expect(guidance).toContain('「文档管理」')
    expect(guidance).toContain('「系统设置 → 模型清单」')
  })
})

describe('buildGuidanceHtml - slot presence and safety', () => {
  const LABEL = 'Next step'

  it('renders the next-step slot when guidance exists', () => {
    const html = buildGuidanceHtml('配额已用尽', '请在「系统设置 → 令牌」查看 ai.quota.calls-per-token-day', LABEL)
    expect(html).toContain('data-test="error-notice-next-step"')
    expect(html).toContain(`${LABEL}：`)
    expect(html).toContain('配额已用尽')
  })

  it('renders no next-step markup at all when guidance is absent (no empty shell)', () => {
    const html = buildGuidanceHtml('保存失败', '', LABEL)
    expect(html).not.toContain('error-notice-next-step')
    expect(html).toBe('保存失败')
  })

  it('escapes backend text before embedding into HTML', () => {
    const html = buildGuidanceHtml('<script>alert(1)</script>', '请打开「设置」 ai.a.b-c', LABEL)
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('escapeHtml covers the ampersand/quote forms too', () => {
    expect(escapeHtml('a & b "c"')).toBe('a &amp; b &quot;c&quot;')
  })
})
