/**
 * Page write-risk ledger face contract .
 *
 * Disk truth = source text (same posture as the login-state ledger
 * contract): the admin PageWriteProfileController serves a read door
 * (GET /api/v1/page-write-profiles with page/pageSize/keyword) plus exactly
 * one POST — /case-draft, which files a pending draft in the draft ledger
 * without touching the risk row itself. The front-end mirror must match
 * both, keep the row face edit-free (risk facts are probe observations,
 * not editable rows), and keep the worst-scale + downgrade rules visible in
 * the panel structure:
 * - server-side paging + inline keyword (S-group);
 * - the sticky snapshot downgrade is surfaced per row (guard honesty);
 * - failure counts ride the same row so the health view needs no second
 * round-trip;
 * - the panel reloads with the drawer refresh so a finished probe is visible
 * without reopening (R62 no-manual-refresh posture).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const apiSrc = readFileSync(join(SRC, 'api', 'pageWriteProfiles.ts'), 'utf8')
const panelSrc = readFileSync(join(SRC, 'views', 'webelement', 'PageWriteRiskPanel.vue'), 'utf8')
const drawerSrc = readFileSync(join(SRC, 'views', 'webelement', 'SiteProfileDrawer.vue'), 'utf8')

describe('pageWriteProfiles api mirror (admin /page-write-profiles contract)', () => {
  it('mirrors the single read door with the paged query shape', () => {
    expect(apiSrc).toMatch(/export function listPageWriteProfiles\(/)
    expect(apiSrc).toContain("url: '/page-write-profiles'")
    expect(apiSrc).toContain("method: 'get'")
    expect(apiSrc).toMatch(/projectId: number/)
    expect(apiSrc).toMatch(/page\?: number[\s\S]*pageSize\?: number[\s\S]*keyword\?: string/)
  })

  it('stays edit-free on the row face: no risk-row write wrappers exist', () => {
    for (const verb of ['create', 'update', 'delete']) {
      expect(apiSrc, `risk ledger rows must stay read-only (${verb})`)
        .not.toMatch(new RegExp(`export function ${verb}PageWriteProfile\\(`))
    }
    // Amended posture : the ledger keeps exactly one POST —
    // the failure-to-draft door. It writes nothing to the risk row; it files
    // a pending card into the draft ledger behind the human confirmation
    // door. PUT/DELETE (row edits) stay forbidden, and no second POST may
    // appear without re-adjudicating this guard.
    expect(apiSrc).not.toMatch(/method: '(put|delete)'/)
    const postDoors = apiSrc.match(/method: 'post'/g) ?? []
    expect(postDoors, 'only the case-draft conversion door may POST').toHaveLength(1)
    expect(apiSrc).toContain("url: '/page-write-profiles/case-draft'")
  })
})

describe('PageWriteRiskPanel structure (worst-scale + downgrade honesty)', () => {
  it('pages server-side and restarts at page one on keyword change', () => {
    expect(panelSrc).toMatch(/page:\s*page\.value/)
    expect(panelSrc).toMatch(/pageSize:\s*PAGE_SIZE/)
    expect(panelSrc).toMatch(/page\.value = 1[\s\S]{0,80}void load\(\)/)
    expect(panelSrc).toContain('el-pagination')
  })

  it('surfaces the sticky snapshot downgrade and both fact counts', () => {
    expect(panelSrc).toContain('risk-snapshot-tag')
    expect(panelSrc).toMatch(/row\.snapshotOnly \? t\('siteProfile\.riskSnapshotOn'\)/)
    expect(panelSrc).toContain('row.writeEvents.length')
    expect(panelSrc).toContain('row.netFailures.length')
  })

  it('keeps an honest empty state and an error door with a retry pointer', () => {
    expect(panelSrc).toContain('data-test="risk-empty"')
    expect(panelSrc).toMatch(/bizErrorMsg\(err, t\('siteProfile\.riskLoadFailed'\)\)/)
  })
})

describe('interface health card (per-page failure list inside the ledger face)', () => {
  it('api rows carry the failure classification fields booked at source', () => {
    expect(apiSrc).toMatch(/errorType\?: number \| null/)
    expect(apiSrc).toMatch(/failureText\?: string \| null/)
  })

  it('expands the per-page failure list with the product/environment split', () => {
    expect(panelSrc).toContain('data-test="risk-health-card"')
    expect(panelSrc).toMatch(/v-for="\(f, i\) in row\.netFailures"/)
    expect(panelSrc).toMatch(/f\.errorType === 3 \? t\('siteProfile\.riskTypeEnv'\) : t\('siteProfile\.riskTypeProduct'\)/)
  })

  it('names the endpoint and shows status or the failure reason per row', () => {
    expect(panelSrc).toMatch(/\{\{ f\.method \}\} \{\{ f\.url \}\}/)
    expect(panelSrc).toMatch(/v-if="f\.status"[\s\S]{0,40}\{\{ f\.status \}\}/)
    expect(panelSrc).toMatch(/v-if="f\.failureText"/)
  })

  it('keeps an honest no-failure state instead of an empty hole', () => {
    expect(panelSrc).toContain('data-test="risk-fail-none"')
  })

  it('every health-card label exists in both locales', () => {
    const zh = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
    const en = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
    for (const key of ['riskFailHead', 'riskFailNone', 'riskTypeProduct', 'riskTypeEnv', 'riskHealthFoot']) {
      expect(zh, `zh missing ${key}`).toContain(`${key}:`)
      expect(en, `en missing ${key}`).toContain(`${key}:`)
    }
  })
})

describe('failure-to-draft conversion door (one-click entry per booked failure)', () => {
  it('api mirror exposes the three-key POST returning the draft ledger id', () => {
    expect(apiSrc).toMatch(/export function toCaseDraftFromFailure\(/)
    expect(apiSrc).toMatch(/projectId: number[\s\S]{0,80}profileId: number[\s\S]{0,80}failureIndex: number/)
    expect(apiSrc).toMatch(/Promise<number>/)
    expect(apiSrc).toMatch(/params: \{[^}]*failureIndex/)
  })

  it('every failure row in the health list carries the conversion button', () => {
    expect(panelSrc).toContain('data-test="risk-to-draft-btn"')
    expect(panelSrc).toMatch(/import \{[^}]*toCaseDraftFromFailure[^}]*\} from '@\/api\/pageWriteProfiles'/)
    expect(panelSrc).toMatch(/await toCaseDraftFromFailure\(\{[\s\S]{0,120}failureIndex: index[\s\S]*\}\)/)
  })

  it('single-flight guards double clicks: one busy key drives loading plus disabled', () => {
    expect(panelSrc).toMatch(/const draftBusy = ref<string \| null>\(null\)/)
    expect(panelSrc).toMatch(/:loading="draftBusy === draftKey\(row\.id, i\)"/)
    expect(panelSrc).toMatch(/:disabled="draftBusy !== null"/)
    expect(panelSrc).toMatch(/if \(!props\.projectId \|\| draftBusy\.value !== null\) return/)
  })

  it('success receipt points at the pinned pending group by its screen literal', () => {
    const zh = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
    const en = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
    expect(panelSrc).toMatch(/t\('siteProfile\.riskToDraftOk'/)
    // The pointer must quote what the pin panel really renders (fake exits
    // are defects, R63): it pins the zh/en pending-groups title literals that
    // PinnedPendingGroups shows, so the toast and the panel cannot drift apart.
    expect(zh).toMatch(/riskToDraftOk:[^\n]*待处置的未提交改动/)
    expect(en).toMatch(/riskToDraftOk:[^\n]*Un-landed changes to dispose/)
    expect(panelSrc).toMatch(/bizErrorMsg\(err, t\('siteProfile\.riskToDraftFailed'\)\)/)
  })

  it('conversion labels exist in both locales', () => {
    const zh = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
    const en = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
    for (const key of ['riskToDraft', 'riskToDraftOk', 'riskToDraftFailed']) {
      expect(zh, `zh missing ${key}`).toContain(`${key}:`)
      expect(en, `en missing ${key}`).toContain(`${key}:`)
    }
  })
})

describe('drawer mount (single slice source, refresh fan-out)', () => {
  it('mounts the panel with the header-derived project slice only', () => {
    expect(drawerSrc).toContain('<PageWriteRiskPanel ref="riskPanelRef" :project-id="props.projectId" />')
    expect(drawerSrc).not.toMatch(/riskPanelRef[^>]*v-if/)
  })

  it('refresh button re-reads the risk face together with the other two', () => {
    expect(drawerSrc).toMatch(/function refreshAll\(\)[\s\S]*riskPanelRef\.value\?\.reload\(\)[\s\S]*\}/)
  })
})
