/**
 * Credential vault "test now" wiring contract (11-16 user ruling package):
 * api mirror of the two new admin endpoints, the row button opening the
 * form dialog, and the trail drawer riding the shared tail composable -
 * plus the red line that the vault plaintext has no surface anywhere in
 * the request/response echo (server masks; the front end only ever carries
 * the template and the token).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const apiSrc = readFileSync(join(SRC, 'api', 'appauto.ts'), 'utf8')
const viewSrc = readFileSync(join(SRC, 'views', 'appauto', 'CredentialVaultView.vue'), 'utf8')

describe('凭据保险箱「立即测试」接线契约', () => {
  it('api 镜像两个新端点（test + test-events 游标窗）', () => {
    expect(apiSrc).toMatch(/export function testCredential\(/)
    expect(apiSrc).toMatch(/export function listCredentialTestEvents\(/)
    expect(apiSrc).toMatch(/url: `\/appauto\/credentials\/\$\{id\}\/test`[\s\S]{0,120}method: 'post'/)
    expect(apiSrc).toMatch(/url: `\/appauto\/credentials\/\$\{id\}\/test-events`[\s\S]{0,120}method: 'get'/)
  })

  it('行钮→表单对话框→共享 tail 抽屉齐面', () => {
    expect(viewSrc).toContain('credential-test-btn')
    expect(viewSrc).toContain('credential-test-dialog')
    expect(viewSrc).toContain('credential-test-url')
    expect(viewSrc).toContain('credential-test-method')
    expect(viewSrc).toContain('credential-test-body')
    expect(viewSrc).toContain('TaskTailDrawer')
    expect(viewSrc).toContain('useTestRunTail')
    // the default body template uses the ecosystem placeholder shape
    expect(viewSrc).toContain('{{CRED:')
    // and the bound row's own key is interpolated into the prefill
    // (credKey is server-mandatory, so no Chinese fallback literal)
    expect(viewSrc).toMatch(/"\{\{CRED:' \+ row\.credKey \+ '\}\}/)
  })

  it('明文无面：视图层不出现 reveal/plaintext 通道，只携带模板与 token', () => {
    // doors, not prose: no reveal endpoint or secret component may surface
    expect(viewSrc).not.toMatch(/\/reveal|revealSecret|credentials\/\$\{\w+\.id\}\/reveal/)
    expect(apiSrc).not.toMatch(/\/reveal/)
    // outcome shape carries no secret component either way
    expect(apiSrc).toMatch(/interface CredentialProbeOutcome \{\n  runToken/)
  })
})
