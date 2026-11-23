/**
 * T294d add-pool wizard contract (frontend .
 *
 * The wizard turns the executor-pool page into a write entry: step 1 picks an
 * existing pool or creates a new one through the single tag channel (a pool is a
 * type=7 tag; the page must reuse api/tags createTag and never POST /tags by
 * hand — R40 single source), step 2 mints one-time enrollment tokens through
 * the admin enroll-token bridge (api/cluster mirror of GET/POST/POST-revoke)
 * and shows the plaintext exactly once with a copy action, step 3 lands the
 * user back on a refreshed page with honest empty-state guidance.
 *
 * Credential discipline is pinned hard: the plaintext token may never be
 * persisted to storage, echoed into a URL or router param (frozen doc: the
 * token is one-time + TTL + fingerprint-bound). The ledger table only ever
 * carries the structurally plaintext-free view fields, and its status value
 * domain (0 pending / 1 redeemed / 2 expired / 3 revoked) is rendered via
 * i18n keys.
 *
 * The suite also closes the carried matrix-band ticket: the scale-event column
 * header must name the event time, not a bare "time".
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { apiSrc, viewSrc, wizardSrc, fleetKeysSrc, fleetDocumentedKeys } from './fleetPageSources'

const WIZARD_KEYS = [
  'wizardAction', 'wizardTitle', 'wizardStepPool', 'wizardStepEnroll', 'wizardStepDone',
  'wizardNextFromPool', 'wizardNextFromEnroll', 'wizardFinishClose',
  'wizardPoolChoiceExisting', 'wizardPoolChoiceCreate', 'wizardPoolNameRequired',
  'wizardEnrollIssue', 'wizardEnrollSkip', 'wizardTokenOnce', 'wizardTokenCopy',
  'wizardTokenCopied', 'wizardEnrollCommand', 'wizardLedgerTitle', 'wizardRevoke',
  'wizardDoneHint',
  'wizardFormLabel', 'wizardFormBare', 'wizardFormK8s', 'wizardFormBareHint',
  'wizardFormK8sHint', 'wizardCommandLabel', 'wizardCommandHint',
  'wizardCommandCopy', 'wizardCommandCopied', 'wizardCommandCopyFailed',
  'wizardStatusPending', 'wizardStatusRedeemed', 'wizardStatusExpired', 'wizardStatusRevoked',
]

describe('T294d add-pool wizard contract', () => {
  it('api mirror carries the three enroll-token bridge endpoints with the admin record fields', () => {
    expect(apiSrc).toMatch(/export function listFleetEnrollTokens/)
    expect(apiSrc).toMatch(/export function issueFleetEnrollToken/)
    expect(apiSrc).toMatch(/export function revokeFleetEnrollToken/)
    expect(apiSrc).toMatch(/url: '\/cluster\/enroll-tokens', method: 'get'/)
    expect(apiSrc).toMatch(/url: '\/cluster\/enroll-tokens', method: 'post'/)
    expect(apiSrc).toMatch(/\/cluster\/enroll-tokens\/\$\{id\}\/revoke/)
    // Ledger view mirror = admin FleetEnrollTokenView components (plaintext-free).
    for (const field of ['id', 'role', 'projectScope', 'maxUses', 'usedCount', 'status',
      'boundFingerprint', 'expiresAt', 'remark', 'createdAt', 'updatedAt']) {
      expect(apiSrc, `FleetEnrollToken 缺字段 ${field}`).toMatch(new RegExp(`export interface FleetEnrollToken[\\s\\S]*?\\b${field}\\b`))
    }
    // Issuance answer mirror = admin FleetEnrollTokenIssuedView components.
    for (const field of ['id', 'token', 'role', 'projectScope', 'maxUses', 'expiresAt']) {
      expect(apiSrc, `FleetEnrollTokenIssued 缺字段 ${field}`).toMatch(new RegExp(`export interface FleetEnrollTokenIssued[\\s\\S]*?\\b${field}\\b`))
    }
    // Smuggling guard: the bridge input carries no actorRole slot on the frontend
    // (the session's first role is injected server-side; a body field would let a
    // caller claim a role it does not hold).
    expect(apiSrc).not.toMatch(/actorRole/)
  })

  it('wizard step 1 creates pools only through the single tag channel with type 7', () => {
    expect(wizardSrc.length, 'FleetPoolWizard.vue 尚未落地').toBeGreaterThan(0)
    expect(wizardSrc).toMatch(/import \{ createTag \} from '@\/api\/tags'/)
    expect(wizardSrc).toMatch(/type: 7/)
    expect(wizardSrc).toMatch(/listFleetEnrollTokens/)
    expect(wizardSrc).toMatch(/issueFleetEnrollToken/)
    expect(wizardSrc).toMatch(/revokeFleetEnrollToken/)
    // Negative pin: no hand-rolled POST to /tags beside the api package.
    expect(wizardSrc).not.toMatch(/url: '\/tags'/)
  })

  it('wizard surfaces the token plaintext exactly once and never persists it', () => {
    for (const anchor of ['pool-wizard-dialog', 'pool-wizard-step-pool', 'pool-wizard-step-enroll',
      'pool-wizard-step-done', 'pool-wizard-token', 'pool-wizard-token-copy']) {
      expect(wizardSrc, `缺向导锚 ${anchor}`).toContain(anchor)
    }
    // Credential red line: plaintext stays in component state only.
    expect(wizardSrc).not.toMatch(/localStorage|sessionStorage/)
    expect(wizardSrc).not.toMatch(/token=\$\{|[?&]token=/)
    // The one-time warning sits beside the plaintext block.
    expect(wizardSrc).toMatch(/t\('pool\.wizardTokenOnce'\)/)
  })

  it('wizard footer labels name the action instead of echoing the step titles', () => {
    // Vision round T294d: the step-0 primary button read exactly like the
    // step-2 title, and the step-1 primary exactly like the step-3 title,
    // so one screen carried the same word as both a stage name and a verb
    // button in both locales (token-issue phrase, and the bare 'Done').
    expect(wizardSrc).toContain("step === 0 ? t('pool.wizardNextFromPool')")
    expect(wizardSrc).toContain("t('pool.wizardNextFromEnroll')")
    for (const loc of [zhCN, enUS]) {
      const get = (k: string) => lookup(loc, `pool.${k}`) as string
      expect(get('wizardNextFromPool'), '步钮与步名同词=文案混用').not.toBe(get('wizardStepEnroll'))
      expect(get('wizardNextFromEnroll'), '步钮与步名同词=文案混用').not.toBe(get('wizardStepDone'))
      expect(get('wizardNextFromPool')).not.toBe(get('wizardStepPool'))
      expect(get('wizardNextFromEnroll')).not.toBe(get('wizardStepEnroll'))
      expect(get('wizardNextFromPool').length).toBeGreaterThan(0)
      expect(get('wizardNextFromEnroll').length).toBeGreaterThan(0)
    }
    // Terminal control carries an object too — the wizard's last primary
    // action must not fall back to the shared bare confirm label (W group).
    expect(wizardSrc).toContain("t('pool.wizardFinishClose')")
    expect(wizardSrc).not.toMatch(/pool-wizard-finish"[\s\S]{0,80}common\.confirm/)
  })

  it('page carries the wizard entry into the action band ahead of view-state actions and refreshes after landing', () => {
    expect(viewSrc).toMatch(/FleetPoolWizard from '\.\/FleetPoolWizard\.vue'/)
    const openIdx = viewSrc.indexOf('pool-wizard-open')
    const refreshIdx = viewSrc.indexOf('pool-refresh')
    expect(openIdx, '缺新建池入口锚').toBeGreaterThan(-1)
    expect(refreshIdx).toBeGreaterThan(-1)
    expect(openIdx, '新建（页面级动作）必须排在视图态动作之前').toBeLessThan(refreshIdx)
    // Landing the wizard reloads the pool list (the page keeps one list source).
    expect(viewSrc).toMatch(/@landed=/)
    expect(lookup(zhCN, 'pool.timelineColTime')).toBe('发生时间')
  })

  it('i18n dual track carries the wizard family with identical shape', () => {
    for (const key of WIZARD_KEYS) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
      expect((lookup(zhCN, `pool.${key}`) as string).length, `zh pool.${key} 空值`).toBeGreaterThan(0)
      expect((lookup(enUS, `pool.${key}`) as string).length, `en pool.${key} 空值`).toBeGreaterThan(0)
    }
  })

  it('the wizard turns the issuance into a copy-ready access command without inventing installer keys', () => {
    // Form choice + command block live beside the issued token so the node
    // gets its pairing command in the same pane it got the secret (16 T294d:
    // pick the deployment form, then generate the access command.
    for (const anchor of ['pool-wizard-form-group', 'pool-wizard-form-bare', 'pool-wizard-form-k8s',
      'pool-wizard-command', 'pool-wizard-command-copy']) {
      expect(wizardSrc, `缺接入命令锚 ${anchor}`).toContain(anchor)
    }
    // The command invokes the repo's own fleet scripts — the executable
    // single source; the page never reimplements installation logic.
    expect(wizardSrc).toContain('deploy/fleet/bare/install-executor-node.sh')
    expect(wizardSrc).toContain('deploy/fleet/k8s/install-executor-pool.sh')
    // R40 key parity against the documented surface (fleet-keys.env.example,
    // kept in parity with the scripts by tools/install_config_gate.py): every
    // EXECUTOR_*/FLEET_* key named by the wizard must already be documented.
    expect(fleetKeysSrc.length, 'deploy/fleet/fleet-keys.env.example 不可读').toBeGreaterThan(0)
    const documented = fleetDocumentedKeys(fleetKeysSrc)
    const referenced = [...wizardSrc.matchAll(/\bEXECUTOR_[A-Z0-9_]+\b|\bFLEET_[A-Z0-9_]+\b/g)].map((m) => m[0])
    expect(referenced.length, '向导未引用任何装机键').toBeGreaterThan(0)
    for (const key of referenced) {
      expect(documented.has(key), `向导键 ${key} 未见于 fleet-keys.env.example`).toBe(true)
    }
    // The platform address is prefilled from the page origin and the hint
    // names the replace path when the api endpoint differs (R63 fix-path).
    expect(wizardSrc).toMatch(/window\.location\.origin/)
    expect(wizardSrc).toMatch(/t\('pool\.wizardCommandHint'\)/)
    // The token only enters the command text from component state; no
    // plaintext literal, no storage, no URL param (credential red line).
    expect(wizardSrc).toMatch(/EXECUTOR_NODE_ENROLL_TOKEN/)
    expect(wizardSrc).not.toMatch(/localStorage|sessionStorage/)
    // Clipboard refusal gives a manual-copy exit, not silence (R63).
    expect(wizardSrc).toMatch(/t\('pool\.wizardCommandCopyFailed'\)/)
  })

  it('the enrollment step dialog uses the wide tier so aspect stays in the 1.2-1.8 band', () => {
    // The command block + ledger stacked the token pane at 720px into a
    // 720x844 portrait dialog (vision capture facts aspect=0.85, below the
    // AGENTS red-line 10 aspect band 1.2-1.8). The wide tier keeps width/height in range
    // without cutting the ledger viewport; other steps stay on --md.
    expect(wizardSrc).toMatch(/step === 1 \? 'tp-dialog--lg' : 'tp-dialog--md'/)
    // Ledger viewport stays bounded so a long token history cannot re-grow
    // the dialog vertically past the band.
    expect(wizardSrc).toMatch(/max-height="260"/)
  })

  it('the pool empty state guides a first add through the same wizard entry', () => {
    // Landing on an empty pool list must offer the wizard action directly,
    // not just describe the emptiness (16 T294d empty-state guidance).
    expect(viewSrc).toMatch(/data-test="pool-empty-add"/)
    expect(viewSrc).toMatch(/pool-empty[\s\S]{0,320}wizardVisible/)
  })
})
