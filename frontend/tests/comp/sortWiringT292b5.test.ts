/**
 * (ledger batch A): server-side sort wiring contract.
 *
 * These three list pages talk to backends whose query DTO already accepts
 * sortBy/sortDir through the admin->core chain (McpServerQuery, SkillQuery,
 * ToolQuery with SortColumnPolicy whitelists). The page-level main table must
 * therefore expose sortable columns and forward @sort-change into the list
 * request — no second sorting mechanism, no client-only fake sort.
 *
 * Enforced per page (?raw source mirror, same posture as columnConfigT292b2):
 * 1. the main table listens @sort-change;
 * 2. at least one data column carries sortable="custom";
 * 3. one SORT_KEY_MAP (column prop -> core whitelist key) is the single source
 * for both the column decision and the request payload;
 * 4. load forwards sortBy/sortDir so the ORDER BY happens in SQL.
 */
import { describe, expect, it } from 'vitest'

import mcpRaw from '@/views/mcp/McpServerView.vue?raw'
import skillRaw from '@/views/skill/SkillListView.vue?raw'
import toolRaw from '@/views/tool/ToolListView.vue?raw'
import batchRaw from '@/views/batch/BatchListView.vue?raw'
import planRaw from '@/views/plan/PlanListView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import defectRaw from '@/views/defect/DefectListView.vue?raw'
import requirementRaw from '@/views/requirement/RequirementListView.vue?raw'
import userRaw from '@/views/user/UserListView.vue?raw'
import tagRaw from '@/views/tag/TagListView.vue?raw'
import mockEndpointsRaw from '@/views/mock/MockEndpointsView.vue?raw'
import mockInspectorRaw from '@/views/mock/MockInspectorView.vue?raw'
import mockScenariosRaw from '@/views/mock/MockScenarioListView.vue?raw'
import scheduleFiresRaw from '@/views/schedule/ScheduleFireListView.vue?raw'
import schedulerHealthRaw from '@/views/system/SchedulerHealthView.vue?raw'
import notifCenterRaw from '@/views/notification/NotificationCenterView.vue?raw'
import execCenterRaw from '@/views/execution/ExecutionCenterView.vue?raw'
import processRaw from '@/views/execution/ProcessView.vue?raw'
import exploreRaw from '@/views/explore/ExploreListView.vue?raw'
import auditRaw from '@/views/system/AuditLogView.vue?raw'
import usageRaw from '@/views/system/UsageCalibrationView.vue?raw'
import documentRaw from '@/views/ai/DocumentListView.vue?raw'
import mockRecordingRaw from '@/views/mock/MockRecordingView.vue?raw'
import perfWorkbenchRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import projectRaw from '@/views/project/ProjectListView.vue?raw'
import dataFactoryRaw from '@/views/datafactory/DataFactoryListView.vue?raw'
import judgeReportRaw from '@/views/judge/JudgeReportView.vue?raw'
import knowledgeDocRaw from '@/views/knowledge/KnowledgeDocListView.vue?raw'
import trainingSampleRaw from '@/views/sample/TrainingSampleListView.vue?raw'
import deleteRequestRaw from '@/views/case/DeleteRequestView.vue?raw'
import reportCenterRaw from '@/views/report-center/ReportCenterView.vue?raw'
import testReportRaw from '@/views/report/TestReportListView.vue?raw'

interface SortPage {
  name: string
  src: string
  /** whitelist keys the map must reference (core SORT_COLUMNS subset) */
  coreKeys: string[]
  /** shell-converted pages render `sortable` through a dynamic binding inside
   * the shared column-config loop (map lookup / set includes / inline deny)
   * instead of a static attribute per column */
  dynamicColumns?: boolean
  /** server contract has no page/pageSize (tag list returns the full segment) —
 * the "sorting resets to page 1" assertion does not apply (honest exemption) */
  noPaging?: boolean
  /** pages whose counter is not named `page` (e.g. judge diff uses `diffPage`)
   * pin the reset through this pattern instead of the shared default regex */
  pageResetPattern?: RegExp
}

const pages: SortPage[] = [
  { name: 'McpServerView', src: mcpRaw, coreKeys: ['name', 'transport'] },
  { name: 'SkillListView', src: skillRaw, coreKeys: ['type', 'updated_at'], dynamicColumns: true },
  { name: 'ToolListView', src: toolRaw, coreKeys: ['type', 'updated_at'], dynamicColumns: true },
  {
    name: 'BatchListView',
    src: batchRaw,
    coreKeys: ['name', 'batch_no', 'created_at'],
    dynamicColumns: true,
  },
  {
    name: 'PlanListView',
    src: planRaw,
    coreKeys: ['name', 'status', 'created_at', 'case_count'],
    dynamicColumns: true,
  },
  {
    name: 'SuiteListView',
    src: suiteRaw,
    coreKeys: ['name', 'status', 'case_count', 'last_run_at'],
  },
  {
    name: 'DefectListView',
    src: defectRaw,
    coreKeys: ['title', 'severity', 'status', 'created_at'],
    dynamicColumns: true,
  },
  {
    name: 'RequirementListView',
    src: requirementRaw,
    coreKeys: ['title', 'source', 'source_id', 'status', 'updated_at'],
    dynamicColumns: true,
  },
  {
    name: 'UserListView',
    src: userRaw,
    coreKeys: ['username', 'role', 'status', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Tag list has no server-side paging (full project segment contract) — the
    // page-reset assertion is exempted, sorting itself must still be server-side.
    // Page exposes only name/caseCount columns; core whitelist is 4 keys but the
    // map carries exactly the rendered ones (no dead entries).
    name: 'TagListView',
    src: tagRaw,
    coreKeys: ['name', 'case_count'],
    dynamicColumns: true,
    noPaging: true,
  },
  {
    // Mock serving inventory : static columns over the
    // paged project-wide window; five rendered data columns all carry
    // whitelisted keys, the composed url/action columns stay unsorted.
    name: 'MockEndpointsView',
    src: mockEndpointsRaw,
    coreKeys: ['path_pattern', 'space_name', 'space_status', 'hits_7d', 'updated_at'],
  },
  {
    // Hit ledger inspector : every rendered column of
    // the paged ledger maps to a core hit-ledger whitelist key (physical
    // columns plus the masked jsonb facets method/path/stubName).
    name: 'MockInspectorView',
    src: mockInspectorRaw,
    coreKeys: ['hit_at', 'method', 'path', 'matched', 'stub_name', 'latency_ms'],
  },
  {
    // Scenario list : the paged machine window sorts
    // server-side through physical columns (name/updated_at) plus the scope
    // facet and the counted states/transitions facets — the same numbers the
    // rows render, counted by core from the declaration instead of shuffled
    // client-side over one window. The composed health column stays unsorted.
    name: 'MockScenarioListView',
    src: mockScenariosRaw,
    coreKeys: ['name', 'scope', 'states_count', 'transitions_count', 'updated_at'],
  },
  {
    // Schedule fire ledger: all five sortable columns
    // are physical columns of tp_schedule_fire; the free-text message column
    // honestly stays unsorted (no whitelist entry, no client-only fake sort).
    name: 'ScheduleFireListView',
    src: scheduleFiresRaw,
    coreKeys: ['fire_time', 'kind', 'ref_id', 'status', 'created_at'],
  },
  {
    // Scheduler health card : the catalog x shedlock
    // merge is composed in core memory, and the same whitelist gate orders the
    // composed rows. The four data columns map to core keys (job_name /
    // description / locked_by / locked_at / lock_until); the composed status
    // badge stays honestly unsorted (derived from lockedBy+running, no key).
    name: 'SchedulerHealthView',
    src: schedulerHealthRaw,
    coreKeys: ['job_name', 'description', 'locked_by', 'locked_at', 'lock_until'],
    dynamicColumns: true,
  },
  {
    // Notification center list : single-table read over
    // tp_notification, four rendered data columns (type/title/read-flag/time)
    // carry whitelisted keys; the free-text content column honestly stays
    // unsorted (no whitelist entry, no client-only fake sort).
    name: 'NotificationCenterView',
    src: notifCenterRaw,
    coreKeys: ['event_type', 'title', 'read_flag', 'created_at'],
  },
  {
    // Execution center main table : the virtual-scroll
    // window over tp_exec_task orders server-side through six physical columns
    // (id/case_id/status/schema_version/duration_ms/created_at) — all six
    // rendered data columns map, the fixed action column stays outside colCfg.
    name: 'ExecutionCenterView',
    src: execCenterRaw,
    coreKeys: ['id', 'case_id', 'status', 'schema_version', 'duration_ms', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Running-process table : the cross-batch status=2
    // window orders server-side through the running whitelist (id / case_id /
    // batch_id / executor_id / started_at / elapsed_ms — the last one is the
    // computed alias the row renders). The budget column stays honestly
    // unsorted (it is a constant K8-derived budget, no independent key).
    name: 'ProcessView',
    src: processRaw,
    coreKeys: ['id', 'case_id', 'batch_id', 'executor_id', 'started_at', 'elapsed_ms'],
  },
  {
    // Explore session list : the project window orders
    // server-side through the explore SORT_COLUMNS whitelist (id / goal_text /
    // start_url / status / steps_used / cost_cents). The pages-visited half of
    // the steps column shares the steps_used key window and the actions column
    // stays honestly unsorted.
    name: 'ExploreListView',
    src: exploreRaw,
    coreKeys: ['id', 'goal_text', 'start_url', 'status', 'steps_used', 'cost_cents'],
    dynamicColumns: true,
  },
  {
    // Audit log list : the append-only audit window
    // orders server-side through the audit SORT_COLUMNS whitelist (id /
    // operator_id / object_type / object_id / action / created_at — all six
    // rendered columns map, the composed diff/detail columns stay outside the
    // table). The fixed-right action column stays honestly unsorted.
    name: 'AuditLogView',
    src: auditRaw,
    coreKeys: ['id', 'operator_id', 'object_type', 'object_id', 'action', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Usage calibration observation : the detail table
    // (paged sample window) orders server-side through the usage SORT_COLUMNS
    // whitelist — all nine rendered columns map to physical columns (deviation
    // is the generated column the rows show). The aggregate table above is a
    // full-window GROUP BY ranking without a paging contract, so it stays
    // honestly unsorted (exemption note lives beside the table in the page).
    name: 'UsageCalibrationView',
    src: usageRaw,
    coreKeys: ['created_at', 'model', 'provider', 'chain', 'project_id', 'est_tokens',
      'usage_prompt_tokens', 'deviation_rate', 'latency_ms', 'cached_tokens'],
  },
  {
    // Document ledger list : the paged project window
    // orders server-side through the document SORT_COLUMNS whitelist — all
    // seven sortable rendered columns map to physical columns (failReason is
    // free text and stays honestly unsorted). The change-analysis and spec
    // tables talk to other endpoints (exemption note lives in the page).
    name: 'DocumentListView',
    src: documentRaw,
    coreKeys: ['name', 'file_type', 'size_bytes', 'chunk_count', 'doc_category',
      'status', 'file_hash'],
  },
  {
    // Data factory list : the paged project window
    // orders server-side through the dataFactory SORT_COLUMNS whitelist
    // (id / name / type / status / created_at). Columns render sortable
    // through the shared SORT_KEY_MAP lookup (dynamic-column shell);
    // envName/createdByName are join-enriched displays kept out of the
    // whitelist, and the pool/history tables talk to other endpoints
    // (exemption note lives beside SORT_KEY_MAP in the page).
    name: 'DataFactoryListView',
    src: dataFactoryRaw,
    coreKeys: ['name', 'type', 'status', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Project list : the paged admin window orders
    // server-side through the project SORT_COLUMNS whitelist (id / name / code /
    // created_at). The three rendered sortable columns map name/code/createdAt;
    // remark (description, no prop) and the fixed action column stay honestly
    // unsorted. The archived-projects dialog talks to another endpoint
    // (exemption note lives beside the main table in the page).
    name: 'ProjectListView',
    src: projectRaw,
    coreKeys: ['name', 'code', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Judge report diff table : the two-run comparison
    // orders server-side through the judge SORT_COLUMNS whitelist (sample_id /
    // score_a / score_b / delta) over the whole merged window in core memory.
    // The verdict column is a derived display and stays honestly unsorted; the
    // card-flow/session tables talk to other endpoints (no paging contract of
    // their own sortable set — exemption notes live beside the table in the page).
    name: 'JudgeReportView',
    src: judgeReportRaw,
    coreKeys: ['sample_id', 'score_a', 'score_b', 'delta'],
    pageResetPattern: /diffPage\.value = 1/,
  },
  {
    // Knowledge ledger list : the paged filtered
    // window orders server-side through the knowledge SORT_COLUMNS whitelist.
    // Six sortable rendered columns map to physical columns; the snippet
    // column is an 80-char view over long text (honestly unsorted). The
    // detail-drawer chunk/hit tables talk to other endpoints (exemption note
    // lives beside the main table in the page).
    name: 'KnowledgeDocListView',
    src: knowledgeDocRaw,
    coreKeys: ['id', 'title', 'source_type', 'project_id', 'status', 'updated_at'],
    dynamicColumns: true,
  },
  {
    // Training sample ledger : the read-only D2
    // ledger orders server-side through the sample SORT_COLUMNS whitelist.
    // Six sortable rendered columns map to physical columns (case_no and
    // project_id derive through the tp_test_case join); sample_json stays off
    // the list because the table renders no snippet column for it. The
    // detail drawer shows one loaded row — no second table, no second query.
    name: 'TrainingSampleListView',
    src: trainingSampleRaw,
    coreKeys: ['id', 'case_no', 'project_id', 'sample_type', 'source_version', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Delete-approval ledger : the paged project
    // window orders server-side through the delreq SORT_COLUMNS whitelist.
    // Four rendered columns map to physical columns (target summary/status/
    // created/handled); reason and reject_reason stay honestly unsorted —
    // free text the reviewer reads, not a ranking dimension. No joins in the
    // query, so the whitelist values are bare physical columns.
    name: 'DeleteRequestView',
    src: deleteRequestRaw,
    coreKeys: ['target_summary', 'status', 'created_at', 'handled_at'],
    dynamicColumns: true,
  },
  {
    // Report-center snapshot ledger : the paged
    // tp_report window orders server-side through the report-center
    // SORT_COLUMNS whitelist (period_start/generated_at/report_type among
    // the seven keys; id tie-break pinned core-side). The scope column is
    // honestly unsorted page-side: it renders a project name, ordering by
    // the hidden scope_id would mislead the reader. The detail-drawer
    // caliber/distribution tables are payload-derived slices — second-table
    // exemption, no list endpoint behind them.
    name: 'ReportCenterView',
    src: reportCenterRaw,
    coreKeys: ['report_type', 'period_start', 'generated_at'],
  },
  {
    // Test-summary ledger : the
    // paged tp_test_report window orders server-side through the testreport
    // SORT_COLUMNS whitelist (title/range_type/created_by_name/created_at among
    // the five keys; id tie-break pinned core-side, join qualifiers carried in
    // the map values). rangeLabel is honestly unsorted page-side: it renders a
    // composed label string, not a ranking column. The detail-drawer defect/env
    // tables are payload-derived slices — second-table exemption.
    name: 'TestReportListView',
    src: testReportRaw,
    coreKeys: ['title', 'range_type', 'created_by_name', 'created_at'],
    dynamicColumns: true,
  },
  {
    // Recording cassette ledger page 1 of the B-group wiring
    // queue): the paged tp_mock_recording window orders server-side through
    // the mock recording SORT_COLUMNS whitelist. Actions column is honestly
    // unsortable (no data behind it); the drawer's draft list is a payload
    // segment — second-table exemption, same posture as the A-group pages.
    name: 'MockRecordingView',
    src: mockRecordingRaw,
    coreKeys: ['target_base_url', 'status', 'promoted_count', 'started_at', 'ended_at'],
  },
  {
    // Perf-workbench scenario ledger , page 2 of the B-group
    // wiring queue): the paged tp_perf_scenario window orders server-side
    // through the perf SORT_COLUMNS whitelist (jsonb payload columns stay
    // off-list). Row fields arrive as core snake_case columns, so the map
    // keys and values coincide. The actions column is honestly unsortable
    // (no data behind it); the run-history drawer is a div list — window
    // cap handled in , second-table exemption posture unchanged.
    name: 'PerfWorkbenchView',
    src: perfWorkbenchRaw,
    coreKeys: ['name', 'pressure_model', 'login_strategy', 'driver_limit', 'updated_at'],
    dynamicColumns: true,
  },
]

describe('T292 blade 5: server-side sort wiring (ledger batch A/B)', () => {
  for (const p of pages) {
    describe(p.name, () => {
      it('main table listens @sort-change (N-2 baseline)', () => {
        expect(p.src).toMatch(/@sort-change="onSortChange"/)
      })
      it('exposes sortable="custom" columns through one SORT_KEY_MAP', () => {
        if (p.dynamicColumns) {
          // dynamic-column shells bind sortable inside the loop; whichever
          // pinned shape the binding takes, el-table-column receives the
          // literal 'custom' header value when the key is whitelisted
          // (Element Plus server-sort marker). The loop wiring itself is
          // pinned by the skillLedger/toolLedger column-config wiring tests.
          expect(p.src).toMatch(/:sortable="[^"\n]*'custom'/)
        } else {
          expect(p.src).toMatch(/sortable="custom"/)
        }
        expect(p.src).toMatch(/const SORT_KEY_MAP: Record<string, string>/)
      })
      it('SORT_KEY_MAP targets core whitelist keys (no invented column names)', () => {
        const block = p.src.match(/const SORT_KEY_MAP[^}]*\}/)
        expect(block, 'SORT_KEY_MAP block present').toBeTruthy()
        for (const key of p.coreKeys) {
          expect(block![0], `maps core key ${key}`).toContain(`'${key}'`)
        }
      })
      it('forwards sortBy/sortDir into the list request', () => {
        expect(p.src).toMatch(/sortBy: query\.sortBy/)
        // either the plain form or a cast for the narrow 'asc'|'desc' DTO type
        expect(p.src).toMatch(/sortDir: \(?\s*query\.sortDir/)
      })
      it('no-paging contract stays honest (no fake page reset invented)', () => {
        if (p.noPaging) {
          // The tag list endpoint returns the whole project segment — the page
          // must not pretend to manage a page counter on sort.
          expect(p.src).not.toMatch(/const page = ref\(/)
        }
      })
      it('sorting resets to page 1 (server paging stays consistent)', () => {
        if (p.noPaging) {
          // Contract has no paging at all (full project segment): nothing to reset.
          // The exemption itself is pinned by the 'no paging params' it below.
          return
        }
        const fn = p.src.match(/function onSortChange[\s\S]{0,600}?\n\}/)
        expect(fn, 'onSortChange present').toBeTruthy()
        // shells keep either a query.page field or a standalone page ref;
        // pages with a differently-named counter pin it via pageResetPattern
        expect(fn![0]).toMatch(p.pageResetPattern ?? /(query\.page = 1|page\.value = 1)/)
      })
      it('cancel-sort falls back to the backend default order', () => {
        const fn = p.src.match(/function onSortChange[\s\S]{0,600}?\n\}/)
        expect(fn![0]).toMatch(/order === null[\s\S]*query\.sortBy = ''/)
      })
    })
  }

  // the executor-task tab (second table in ExecutionCenterView)
  // sorts server-side through the same core whitelist. The static columns carry
  // props that are exactly SORT_KEY_MAP keys, the table listens @sort-change on
  // its own handler, and the request forwards the pair — no second map, no
  // client-only fake sort.
  describe('ExecutionCenterView executor-task table (second table)', () => {
    const src = execCenterRaw
    it('second table listens @sort-change on its own handler', () => {
      expect(src).toMatch(/data-test="exec-tasks-table"[\s\S]{0,80}@sort-change="onExecSortChange"/)
    })
    it('exposes sortable="custom" columns reusing the one SORT_KEY_MAP', () => {
      expect(src).toMatch(/:label="t\('exec\.taskId'\)" width="110" sortable="custom"/)
      const handler = src.match(/function onExecSortChange[\s\S]{0,700}?\n\}/)
      expect(handler, 'onExecSortChange present').toBeTruthy()
      // the handler reads the shared map (no second SORT_KEY_MAP declaration)
      expect(handler![0]).toMatch(/SORT_KEY_MAP\[prop\]/)
      expect(src.match(/const SORT_KEY_MAP/g)?.length, 'exactly one SORT_KEY_MAP').toBe(1)
    })
    it('forwards execQuery sortBy/sortDir into the executor list request', () => {
      expect(src).toMatch(/sortBy: execQuery\.sortBy/)
      expect(src).toMatch(/sortDir: execQuery\.sortDir/)
    })
    it('sorting resets the executor page to 1', () => {
      const handler = src.match(/function onExecSortChange[\s\S]{0,700}?\n\}/)
      expect(handler![0]).toMatch(/execPageNum\.value = 1/)
    })
    it('cancel-sort falls back to the backend default order', () => {
      const handler = src.match(/function onExecSortChange[\s\S]{0,700}?\n\}/)
      expect(handler![0]).toMatch(/order === null[\s\S]*execQuery\.sortBy = ''/)
    })
  })

  // the run-history drawer used to pull every run of the
  // scenario in one unbounded list (S-2 display-scale debt). It is a
  // headerless div list — no sortable columns exist, so the sort whitelist
  // stays exempted *with this recorded reason* — but the transport must gain
  // a bounded window with a truthful total behind the load-more affordance.
  describe('PerfWorkbenchView run drawer window (T292 blade 5.8b)', () => {
    const src = perfWorkbenchRaw
    it('drawer consumes the paged runs door, not the unbounded list', () => {
      expect(src).toMatch(/pagePerfRuns\(/)
      expect(src, 'the drawer must not keep the unbounded list door').not.toMatch(/listPerfRuns\(/)
    })
    it('api layer mirrors the core paged contract', async () => {
      const perfApi = (await import('@/api/perf?raw')).default
      expect(perfApi).toMatch(/url: '\/perf\/runs\/page'/)
      expect(perfApi).toMatch(/interface PerfRunPage/)
      expect(perfApi).toMatch(/pagePerfRuns\(/)
    })
    it('load-more appends the next window and hides once everything is shown', () => {
      expect(src).toMatch(/data-test="perf-runs-loadmore"[\s\S]{0,120}@click="loadMoreRuns"/)
      expect(src).toMatch(/v-if="runs\.length < runsTotal"/)
    })
    it('count line tells shown vs total so the ledger size stays honest', () => {
      expect(src).toMatch(/data-test="perf-runs-count"/)
      expect(src).toMatch(/t\('perf\.runsCount'/)
    })
    it('window opens on page 1 and appends without blanking existing rows', () => {
      const load = src.match(/async function loadRuns[\s\S]{0,700}?\n\}/)
      expect(load, 'loadRuns present').toBeTruthy()
      expect(load![0]).toMatch(/targetPage <= 1 \? res\.list : \[\.\.\.runs\.value, \.\.\.res\.list\]/)
    })
  })
})
