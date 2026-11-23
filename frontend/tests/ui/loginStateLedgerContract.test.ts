/**
 * Login-state ledger page + API mirror contract (AV facade segment).
 *
 * Disk truth = source text (same posture as the tag merge-band contract):
 * the admin LoginStateController owns the ledger endpoints (list/page/detail/
 * register/update/resolve/enabled/delete/probe trail); the front-end layer
 * must mirror every one of them, and the ledger page must keep the
 * cross-page UI iron rules visible in structure:
 * - merge band (no page-header shell, create rides the trailing slot);
 * - project segment only from the header context (no in-page picker);
 * - the four-state validity light is surfaced for every row;
 * - secret material never appears on the consumer side (vault red line).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const apiSrc = readFileSync(join(SRC, 'api', 'sessionState.ts'), 'utf8')
const viewSrc = readFileSync(join(SRC, 'views', 'session', 'LoginStateListView.vue'), 'utf8')

function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'view must carry a template block').toBeGreaterThan(-1)
  let right = src.indexOf('<script', start)
  const styleAt = src.indexOf('<style', start)
  if (styleAt > start && (right < 0 || styleAt < right)) right = styleAt
  return src.slice(start, right < 0 ? src.length : right)
}

describe('sessionState api mirror (admin /session-states contract)', () => {
  it('exports one wrapper per controller endpoint', () => {
    for (const fn of [
      'listSessionStates',
      'getSessionState',
      'registerSessionState',
      'updateSessionState',
      'resolveSessionState',
      'setSessionStateEnabled',
      'deleteSessionState',
      'probeLoginState',
      'listProbeEvents',
    ]) {
      expect(apiSrc, `missing wrapper ${fn}`).toMatch(new RegExp(`export function ${fn}\\(`))
    }
  })

  it('URLs and methods match the controller mappings', () => {
    expect(apiSrc).toContain("'/session-states'")
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}`[\s\S]{0,80}method: 'get'/)
    expect(apiSrc).toMatch(/url: '\/session-states'[\s\S]{0,80}method: 'post'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}`[\s\S]{0,80}method: 'put'/)
    expect(apiSrc).toMatch(/url: '\/session-states\/resolve'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}\/enabled`[\s\S]{0,80}method: 'put'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}`[\s\S]{0,80}method: 'delete'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}\/probe`[\s\S]{0,120}method: 'post'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}\/probe-events`[\s\S]{0,120}method: 'get'/)
  })

  it('edit door: row button opens the shared dialog prefilled and guards unchanged saves', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl).toContain('login-state-edit-btn')
    expect(tpl).toContain("'login-state-edit-dialog'")
    expect(viewSrc, 'edit prefill reads the fresh row from the server').toMatch(/await getSessionState\(row\.id\)/)
    // R28 idempotency: the save button stays disabled against the baseline
    // snapshot taken at open, with a pointer instead of a fake "saved" toast.
    expect(tpl).toMatch(/:disabled="editingId !== null && !formDirty"/)
    expect(tpl, 'disabled save must carry its pointer').toContain('sessionState.noChangeHint')
    // the edit door always ships the explicit header list — deleting every
    // row is a real intent to clear the combined headers.
    const editBranch = viewSrc.slice(viewSrc.indexOf('if (editingId.value !== null)'))
    expect(editBranch.slice(0, editBranch.indexOf('return')), 'edit sends refs explicitly').toContain('extraAuthRefs: refs')
  })

  it('「立即测试」面在页内接通：行钮→对话框→tail 抽屉（11-16 用户口令包）', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl).toContain('login-state-probe-btn')
    expect(tpl).toContain('login-state-probe-dialog')
    expect(tpl).toContain('login-state-probe-run')
    expect(tpl).toContain('TaskTailDrawer')
    expect(viewSrc).toContain("useTestRunTail")
    expect(viewSrc).toContain("probeLoginState")
    // iron rule: the drawer consumes the shared tail composable, no bespoke tail loop
    expect(viewSrc, 'tail must ride the shared composable').toMatch(/openRun: probeOpenRun/)
  })

  it('probe run-shape mirrors the vault test door grammar (11-19: POST + body)', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl, 'closed verb enumeration is chosen, never typed').toContain('login-state-probe-method')
    expect(tpl).toContain(`v-if="probeMethod !== 'GET'"`)
    expect(tpl).toContain('login-state-probe-body')
    expect(viewSrc).toMatch(/const run = probeMethod\.value === 'GET'\s*\n?\s*\?\s*undefined/)
    // GET keeps the historical door byte-identical: no run payload on the wire
    const fn = apiSrc.slice(apiSrc.indexOf('export function probeLoginState'))
    expect(fn.slice(0, 400)).toContain('data: run')
    expect(fn.slice(0, 400)).toContain('params: url')
  })

  it('register payload mirrors LoginStateRegisterRequest component names', () => {
    const payload = apiSrc.slice(apiSrc.indexOf('RegisterPayload'))
    for (const key of ['projectId', 'stateName', 'roleScope', 'accountLabel', 'kind', 'credKey', 'secret', 'stateObjectKey', 'scopeOrigins', 'remark']) {
      expect(payload, `register payload missing ${key}`).toContain(key)
    }
  })
})

describe('login state ledger page skeleton (merge band + slice + light)', () => {
  const tpl = templateOf(viewSrc)

  it('wraps content in the shared page card and drops the header shell', () => {
    expect(tpl).toMatch(/class="[^"]*page-card[^"]*"/)
    expect(tpl).not.toMatch(/class="[^"]*page-header[^"]*"/)
  })

  it('create action rides the filter-bar trailing slot before view-state actions', () => {
    const band = tpl.slice(tpl.indexOf('filter-bar-actions'))
    const createAt = band.indexOf('login-state-create-btn')
    const settingsAt = band.indexOf('ColumnSettings')
    expect(createAt, 'create button must live in the trailing slot').toBeGreaterThan(-1)
    expect(settingsAt, 'column settings must follow create in the same slot').toBeGreaterThan(createAt)
  })

  it('consumes the header project slice and never renders a second picker', () => {
    expect(viewSrc).toContain('useProjectPageContext')
    expect(viewSrc).not.toMatch(/pick_project|project-select|projectIdSelect/i)
  })

  it('surfaces the four-state light and both lifecycle actions per row', () => {
    expect(tpl).toContain('login-state-light')
    for (const state of ['VALID', 'EXPIRING', 'EXPIRED', 'UNKNOWN']) {
      expect(viewSrc, `light missing state ${state}`).toContain(state)
    }
    expect(tpl).toContain('login-state-toggle-btn')
    expect(tpl).toContain('login-state-delete-btn')
  })

  it('never binds secret material on the consumer surface (vault red line)', () => {
    expect(tpl).not.toMatch(/row\.secret|\bv-model[^>]*secret[^>]*view/i)
  })

  it('renders all visible copy through locale keys', () => {
    expect(tpl).toMatch(/t\('sessionState\./)
    expect(tpl).not.toMatch(/>[^<{]*[\u4e00-\u9fff][^<}]*</)
  })

  it('keeps ledger cell text complete outside datetime columns', () => {
    // Regression lock (2026-11-13 ledger E2E): cellText once sliced every
    // column to 19 chars, cutting state names in the DOM. Datetime columns
    // may keep the ISO trim; the fallback branch must render the full value.
    const helper = viewSrc.match(/function cellText[\s\S]*?\n\}/)
    expect(helper, 'cellText helper must exist').toBeTruthy()
    const body = helper![0]
    expect(body, 'datetime columns keep the ISO trim').toMatch(/DATETIME_COLUMNS\.has\(key\)/)
    expect(body, 'fallback branch renders the raw value').toMatch(/return text\n\}/)
    expect(body).not.toMatch(/return String\(value\)[\s\S]{0,40}slice/)
  })
})

describe('ledger paging + search wiring (S-group worst-size face, 三-1)', () => {
  const tpl = templateOf(viewSrc)

  it('api mirrors the paged door with {list,total} and every query component', () => {
    expect(apiSrc, 'missing paged wrapper').toMatch(/export function listSessionStatesPage\(/)
    expect(apiSrc).toMatch(/url: '\/session-states\/page'/)
    const fn = apiSrc.slice(apiSrc.indexOf('listSessionStatesPage'))
    for (const p of ['projectId', 'keyword', 'status', 'sortBy', 'sortDir', 'page', 'size']) {
      expect(fn, `paged wrapper missing param ${p}`).toContain(p)
    }
    expect(apiSrc).toMatch(/LoginStatePage/)
  })

  it('filter bar carries keyword + status conditions with the query button inline at the group end', () => {
    expect(tpl).toContain('login-state-keyword-input')
    expect(tpl).toContain('login-state-status-select')
    const beforeSlot = tpl.slice(0, tpl.indexOf('class="filter-bar-actions"'))
    expect(beforeSlot, 'keyword condition must live in the condition flow').toContain('login-state-keyword-input')
    expect(beforeSlot, 'query button must sit inline before the trailing slot').toContain('login-state-query-btn')
  })

  it('pagination bar renders against the server total', () => {
    expect(tpl).toMatch(/class="pagination-bar"[\s\S]*login-state-pager/)
    expect(viewSrc).toMatch(/const total = ref\(/)
  })

  it('page state round-trips through the URL query (F1)', () => {
    expect(viewSrc).toContain('useRoute')
    expect(viewSrc).toMatch(/router\.replace\(/)
    const urlBlock = viewSrc.slice(viewSrc.indexOf('function syncUrl'))
    for (const key of ['page', 'keyword', 'status', 'sortBy', 'sortDir']) {
      expect(urlBlock, `URL query missing ${key}`).toContain(key)
    }
  })

  it('only server-whitelisted columns are sortable; derived/vault columns refuse the header affordance (R25)', () => {
    expect(tpl, 'sortable must be a whitelist binding, not a blanket custom').not.toMatch(/sortable="custom"/)
    const list = viewSrc.match(/const SERVER_SORTABLE[^;]*;/s)?.[0] ?? ''
    expect(list, 'sort whitelist missing').toBeTruthy()
    for (const banned of ['light', 'credKey', 'scopeOrigins']) {
      expect(list, `${banned} is not a ledger column and must stay unsortable`).not.toContain(`'${banned}'`)
    }
    for (const allowed of ['stateName', 'expiresAt', 'status']) {
      expect(list, `${allowed} must stay sortable`).toContain(`'${allowed}'`)
    }
  })

  it('sort and filter changes go through the server door, never a client re-order of one pageful', () => {
    const handler = viewSrc.match(/function onSortChange[\s\S]*?\n\}/)
    expect(handler, 'onSortChange must exist').toBeTruthy()
    expect(handler![0]).toMatch(/load\(/)
    expect(viewSrc, 'client-side whole-list sort is gone').not.toMatch(/function sortValue\(/)
  })

  it('create dialog word-count affordance is symmetric across maxlength inputs (三-4)', () => {
    // Floor is 5 since the SE5 digestion: the kind field became an el-select
    // (closed enumeration is chosen, never typed), so it left the maxlength
    // input population. The invariant under test is unchanged: every input
    // that can overflow must show its word counter.
    const inputs = tpl.match(/<el-input[^>]*maxlength="(?:64|32|200)"[^>]*>/gs) ?? []
    expect(inputs.length).toBeGreaterThanOrEqual(5)
    const withoutLimit = inputs.filter((tag) => !tag.includes('show-word-limit'))
    expect(withoutLimit, `inputs missing show-word-limit: ${withoutLimit.join(' | ')}`).toHaveLength(0)
  })
})

describe('locale wording (三-6 R55 plain description)', () => {
  it('zh never compresses credential wording into the coined 实值', () => {
    const zh = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
    expect(zh).not.toContain('实值')
    expect(zh).toContain('密码或验证码本身')
  })

  it('en mirrors the plain description', () => {
    const en = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
    expect(en).not.toMatch(/code values?/i)
  })
})
