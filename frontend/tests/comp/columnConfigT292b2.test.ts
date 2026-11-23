/**
 * (batches 1+2) layout contract: the column-config baseline
 * (useColumnConfig + ColumnSettings — the single shared piece, CaseListView is
 * the reference posture, no second implementation allowed) must be wired into
 * the priority inventory of main list pages: BatchListView, UserListView
 * (batch 1) plus TagListView, AuditLogView, ConfigListView (batch 2).
 *
 * Enforced per page (?raw source mirror, same posture as
 * ownerEchoWiringT396b.test.ts):
 * 1. the page imports the shared composable and instantiates it with a stable
 * viewKey and a ColumnDef list covering the data columns;
 * 2. the main table is the DataTablePage shell fed with
 * :column-config, so dragged header widths write back and persist through
 * the single shell channel;
 * 3. data columns render through one v-for over colCfg.visibleColumns so
 * hide/reorder/pin apply everywhere;
 * 4. density binds :size="colCfg.state.density";
 * 5. the ColumnSettings popover (toggle/move/density/reset/pin) is hosted in
 * the page header area;
 * 6. the fixed-right action column stays outside the configurable defs.
 */
import { describe, expect, it } from 'vitest'

import batchRaw from '@/views/batch/BatchListView.vue?raw'
import userRaw from '@/views/user/UserListView.vue?raw'
import tagRaw from '@/views/tag/TagListView.vue?raw'
import auditRaw from '@/views/system/AuditLogView.vue?raw'
import configRaw from '@/views/system/ConfigListView.vue?raw'
import requirementRaw from '@/views/requirement/RequirementListView.vue?raw'
import testReportRaw from '@/views/report/TestReportListView.vue?raw'
import dataFactoryRaw from '@/views/datafactory/DataFactoryListView.vue?raw'
import executionRaw from '@/views/execution/ExecutionCenterView.vue?raw'
import hostRaw from '@/composables/tabScopedColumnSettings?raw'

/** Disk source of the tab-scoped host . */
const hostSrc = hostRaw as string

interface PageWiring {
  name: string
  src: string
  viewKey: string
  /** data-test anchor of the main table */
  tableTest: string
  /**
 * (R25 mode 1): tabbed pages route the wiring through a pane-bound
 * settings view instead of a page-level colCfg. When present, the pattern
 * assertions below use these identifiers (pane tab key + pane var).
 */
  paneTabKey?: string
  paneVar?: string
}

const pages: PageWiring[] = [
  { name: 'BatchListView', src: batchRaw, viewKey: 'batches', tableTest: 'batch-table' },
  { name: 'UserListView', src: userRaw, viewKey: 'users', tableTest: 'user-table' },
  { name: 'TagListView', src: tagRaw, viewKey: 'tags', tableTest: 'tag-table', paneTabKey: 'tags', paneVar: 'tagPane' },
  { name: 'AuditLogView', src: auditRaw, viewKey: 'audit', tableTest: 'audit-table' },
  { name: 'ConfigListView', src: configRaw, viewKey: 'configs', tableTest: 'config-table' },
  // batch 3: requirement / test report / data factory / execution-center main tables
  { name: 'RequirementListView', src: requirementRaw, viewKey: 'requirements', tableTest: 'req-table' },
  { name: 'TestReportListView', src: testReportRaw, viewKey: 'testReports', tableTest: 'report-table' },
  { name: 'DataFactoryListView', src: dataFactoryRaw, viewKey: 'dataFactories', tableTest: 'datafactory-table', paneTabKey: 'factory', paneVar: 'factoryPane' },
  { name: 'ExecutionCenterView', src: executionRaw, viewKey: 'execTasks', tableTest: 'exec-task-table' },
]

describe('T423 S-9 companion: the tab-scoped host must reuse useColumnConfig (single source)', () => {
  it('tabScopedColumnSettings composes useColumnConfig, no merge-math fork', () => {
    // browser-mode vitest has no __dirname: the disk source rides the ?raw import
    expect(hostSrc).toMatch(/import \{[^}]*useColumnConfig[^}]*\} from '\.\/useColumnConfig'/)
    // routing + metadata only: the host must not re-implement the storage or
    // merge math (doc comments may name localStorage; calls must not appear)
    expect(hostSrc).not.toMatch(/localStorage\.(get|set)Item/)
    expect(hostSrc).not.toMatch(/JSON\.(parse|stringify)/)
  })
})

describe('T292 blade 2: column-config wiring (priority list pages)', () => {
  for (const p of pages) {
    describe(p.name, () => {
      it('imports the shared column-config composable (single source)', () => {
        if (p.paneVar) {
          // tabbed pages reach useColumnConfig through the tab-scoped host
          expect(p.src).toMatch(/import \{ useTabScopedSettings \} from '@\/composables\/tabScopedColumnSettings'/)
        } else {
          expect(p.src).toMatch(/import \{ useColumnConfig[^}]*\} from '@\/composables\/useColumnConfig'/)
        }
      })
      it(`instantiates the config with viewKey '${p.viewKey}'`, () => {
        if (p.paneVar) {
          expect(p.src).toContain(`settingsFor('${p.paneTabKey}', '${p.viewKey}'`)
        } else {
          expect(p.src).toContain(`useColumnConfig('${p.viewKey}'`)
        }
      })
      it('declares a ColumnDef[] covering the data columns', () => {
        expect(p.src).toMatch(/: ColumnDef\[\] = \[/)
      })
      it('main table is the DataTablePage shell fed with the column config (drag widths persist)', () => {
        const tag = mainTableTag(p.src, p.tableTest)
        expect(tag).toMatch(/<DataTablePage\b/)
        expect(tag).toMatch(p.paneVar ? q(`:column-config="${p.paneVar}.cfg"`) : /:column-config="colCfg"/)
      })
      it('renders data columns through one v-for over visibleColumns', () => {
        expect(p.src).toMatch(
          p.paneVar
            ? q(`v-for="col in ${p.paneVar}.visibleColumns.value"`)
            : /v-for="col in colCfg\.visibleColumns\.value"/,
        )
      })
      it('binds table density from the shared state', () => {
        const tag = mainTableTag(p.src, p.tableTest)
        expect(tag).toMatch(p.paneVar ? q(`:size="${p.paneVar}.state.density"`) : /:size="colCfg\.state\.density"/)
      })
      it('hosts the ColumnSettings popover wired to the shared state', () => {
        expect(p.src).toMatch(/import ColumnSettings from '@\/components\/ColumnSettings\.vue'/)
        expect(p.src).toMatch(
          p.paneVar
            ? /<ColumnSettings[\s\S]{0,600}@reset="currentPane\.reset"/
            : /<ColumnSettings[\s\S]{0,400}@reset="colCfg\.reset"/,
        )
      })
    })
  }

  for (const p of pages) {
    it(`keeps the fixed-right action column out of configurable defs (${p.name})`, () => {
      expect(p.src).toMatch(/fixed="right"/)
      const defs = defsBlock(p.src)
      expect(defs).not.toMatch(/key: '(operation|action)'/)
    })
  }
})

/** RegExp from a literal string . */
function q(literal: string): RegExp {
  return new RegExp(literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
}

/** Opening tag (with multi-line attrs) of the component carrying data-test. */
function mainTableTag(src: string, tableTest: string): string {
  const anchor = src.indexOf(`data-test="${tableTest}"`)
  expect(anchor, `table ${tableTest} not found`).toBeGreaterThan(-1)
  const start = src.lastIndexOf('<', anchor)
  const end = src.indexOf('>', anchor)
  return src.slice(start, end + 1)
}

/** The ColumnDef[] literal body, so locked keys can be asserted absent. */
function defsBlock(src: string): string {
  const start = src.search(/const \w+: ColumnDef\[\] = \[/)
  expect(start, 'ColumnDef[] block not found').toBeGreaterThan(-1)
  // segment from the array literal's own '[' (searching from `start` would hit
  // the ']' inside the `ColumnDef[]` type annotation and return a vacuous block)
  const arrStart = src.indexOf('= [', start)
  const end = src.indexOf(']', arrStart)
  return src.slice(arrStart, end)
}
