// Contract guard for the user-reported misalignment on the forced password
// change screen: the stacked full-width buttons must not inherit Element
// Plus's default sibling margin (.el-button+.el-button { margin-left: 12px }),
// which shifted the logout button 12px right of the submit button.
// Source-on-disk reading posture per tests/ui convention (no jsdom here).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '../../src/views/PwdExpiredView.vue')

describe('PwdExpiredView button alignment contract', () => {
  const src = readFileSync(SRC, 'utf-8')

  it('keeps both action buttons full-width stacked in the card', () => {
    expect(src).toContain('class="pwd-submit"')
    expect(src).toContain('class="pwd-logout"')
    for (const cls of ['pwd-submit', 'pwd-logout']) {
      const rule = new RegExp(`\\.${cls}\\s*\\{[^}]*width:\\s*100%[^}]*\\}`)
      expect(src, `missing width:100% for .${cls}`).toMatch(rule)
    }
  })

  it('zeroes the EP sibling margin-left for the vertical button stack', () => {
    // the guard rule must target sibling buttons inside the form scope
    expect(src).toMatch(/\.pwd-form\s+\.el-button\s*\+\s*\.el-button\s*\{[^}]*margin-left:\s*0\b[^}]*\}/)
  })

  it('keeps data-test anchors intact for E2E specs', () => {
    expect(src).toContain('data-test="pwd-expired-submit"')
    expect(src).toContain('data-test="pwd-expired-logout"')
  })
})
