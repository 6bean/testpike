import type { LocationQuery, LocationQueryRaw, RouteRecordRaw } from 'vue-router'

/**
 * AN-4 (menu IA second pass): the four mock screening pages merged into
 * the per-space workbench (/mock/spaces/:id?tab=…). Retired paths keep
 * resolving for bookmarks and the §12a seamless links — the redirect is
 * functional (not a static string) so the caller query survives the hop.
 * A URL-carried spaceId lands on that space + tab; without one the visitor
 * falls back to the spaces list to pick a space honestly (never a guessed
 * space id). One helper for all four tabs (reuse >= 2 → extracted, red line 6).
 */
function mockWorkbenchRedirect(tab: string) {
  return (to: { query: LocationQuery }) => {
    const raw = Array.isArray(to.query.spaceId) ? null : to.query.spaceId
    const id = Number(raw)
    const passable: LocationQueryRaw = {}
    for (const [k, v] of Object.entries(to.query)) passable[k] = v as LocationQueryRaw[string]
    return Number.isInteger(id) && id > 0 && raw != null
      ? { path: `/mock/spaces/${id}`, query: { ...passable, tab } }
      // AN-4 (leaf retirement step): the no-space-id fallback carries the
      // tab intent onto the spaces list, which forwards it when the user opens
      // a workbench row — otherwise a door without an id (e.g. an origin
      // badge) dead-ends on the default tab and loses which screen the caller
      // wanted.
      : { path: '/mock/spaces', query: { ...passable, tab } }
  }
}


export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { titleKey: 'layout.loginTitle', public: true },
  },
  {
    path: '/password-expired',
    name: 'PwdExpired',
    component: () => import('@/views/PwdExpiredView.vue'),
    meta: { titleKey: 'layout.pwdExpired', public: true },
  },
  {
    // P2 public read-only dialog snapshot (TC-AO-20).
    // Top-level + meta.public = outside the authenticated layout — a link
    // holder never mounts menus nor carries a JWT. The token in the path is
    // the only credential; the page shows core's frozen copy or core's
    // refusal sentence.
    path: '/share/chat/:token',
    name: 'ShareChat',
    component: () => import('@/views/ShareChatView.vue'),
    props: true,
    meta: { titleKey: 'layout.shareChat.title', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { titleKey: 'layout.dashboard', icon: 'Odometer' },
      },
      {
        path: 'projects',
        name: 'ProjectList',
        component: () => import('@/views/project/ProjectListView.vue'),
        meta: { titleKey: 'layout.projects', icon: 'FolderOpened' },
      },
      {
        path: 'projects/:id',
        name: 'ProjectDetail',
        component: () => import('@/views/project/ProjectDetailView.vue'),
        meta: { titleKey: 'layout.projectDetail', hidden: true, parent: '/projects' },
      },
      {
        path: 'programs',
        name: 'ProgramList',
        component: () => import('@/views/program/ProgramListView.vue'),
        meta: { titleKey: 'layout.programs', icon: 'FolderOpened' },
      },
      {
        // Y-wave : program-set quality dashboard. The menu leaf and its
        // help chapter (V438) land in the same batch, so the route is visible now.
        path: 'program-dashboard',
        name: 'ProgramDashboard',
        component: () => import('@/views/report/ProgramDashboardView.vue'),
        meta: { titleKey: 'layout.programDashboard', icon: 'Odometer' },
      },
      {
        path: 'cases',
        name: 'CaseList',
        component: () => import('@/views/case/CaseListView.vue'),
        meta: { titleKey: 'layout.cases', icon: 'Document' },
      },
      {
        path: 'cases/:id',
        name: 'CaseDetail',
        component: () => import('@/views/case/CaseDetailView.vue'),
        meta: { titleKey: 'layout.caseDetail', hidden: true, parent: '/cases' },
      },
      {
        path: 'explore',
        name: 'ExploreList',
        component: () => import('@/views/explore/ExploreListView.vue'),
        meta: { titleKey: 'layout.explore', icon: 'Compass' },
      },
      {
        path: 'explore/:id',
        name: 'ExploreDetail',
        component: () => import('@/views/explore/ExploreDetailView.vue'),
        meta: { titleKey: 'layout.exploreDetail', hidden: true, parent: '/explore' },
      },
      {
        path: 'delete-requests',
        name: 'DeleteRequestList',
        component: () => import('@/views/case/DeleteRequestView.vue'),
        meta: { titleKey: 'layout.deleteRequests', icon: 'DocumentDelete' },
      },
      {
        path: 'review',
        name: 'ReviewWorkbench',
        component: () => import('@/views/review/ReviewWorkbenchView.vue'),
        meta: { titleKey: 'layout.review', icon: 'Checked' },
      },
      {
        // AL batch (requirement ledger read surface): the standalone page is
        // back — the version-chain history dialog needs a stable host and a
        // full entity page deserves its own leaf instead of an extraction tab.
        path: 'requirements',
        name: 'RequirementList',
        component: () => import('@/views/requirement/RequirementListView.vue'),
        meta: { titleKey: 'layout.requirements', icon: 'List' },
      },
      {
        path: 'ai/generate',
        name: 'AiGenerate',
        component: () => import('@/views/ai/AiGenerateView.vue'),
        meta: { titleKey: 'layout.aiGenerate', icon: 'MagicStick' },
      },
      {
        // stage 3: the standalone chat page (entry surface source=2)
        // shares the dock kernel. Route perm mirrors the backend door key.
        path: 'ai/chat',
        name: 'AiChat',
        component: () => import('@/views/ai/AiChatView.vue'),
        meta: { titleKey: 'layout.aiChat', icon: 'ChatDotRound', perm: 'perm:aichat:use' },
      },
      {
        // AN-5 merged the documents page into /extraction; the leaf still
        // resolves here — upload-docs IS the extraction page (AL batch moved the
        // requirement ledger back out to its own /requirements page).
        path: 'documents',
        redirect: (to) => ({ path: '/extraction', query: { ...to.query } }),
      },
      {
        path: 'execution',
        name: 'ExecutionCenter',
        component: () => import('@/views/execution/ExecutionCenterView.vue'),
        meta: { titleKey: 'layout.execution', icon: 'VideoPlay' },
      },
      {
        // (P-1 ruling): the process page merged into /execution as a tab.
        // The leaf is gone from the menu but the path keeps resolving — old
        // bookmarks/links land on the running-tasks tab instead of 404ing.
        path: 'process',
        redirect: '/execution?tab=process',
      },
      {
        path: 'execution/pools',
        name: 'ExecutorPools',
        component: () => import('@/views/execution/ExecutorPoolView.vue'),
        meta: { titleKey: 'layout.executorPools', icon: 'Grid', perm: 'perm:project:executor-pool' },
      },
      {
        path: 'appauto/devices',
        name: 'DeviceWall',
        component: () => import('@/views/appauto/DeviceWallView.vue'),
        meta: { titleKey: 'layout.deviceWall', icon: 'Cellphone' },
      },
      {
        path: 'appauto/reservations',
        name: 'DeviceReservationCalendar',
        component: () => import('@/views/appauto/DeviceReservationCalendarView.vue'),
        meta: { titleKey: 'layout.reservationCalendar', icon: 'Clock' },
      },
      {
        path: 'appauto/healing',
        name: 'StepHealing',
        component: () => import('@/views/appauto/StepHealingView.vue'),
        meta: { titleKey: 'layout.stepHealing', icon: 'MagicStick' },
      },
      {
        path: 'appauto/samples',
        name: 'AiSampleBoard',
        component: () => import('@/views/appauto/AiSampleBoardView.vue'),
        meta: { titleKey: 'layout.aiSampleBoard', icon: 'DataAnalysis' },
      },
      {
        path: 'appauto/engines',
        name: 'EngineCenter',
        component: () => import('@/views/appauto/EngineCenterView.vue'),
        meta: { titleKey: 'layout.engineCenter', icon: 'Cpu', perm: 'perm:appauto:engine:view' },
      },
      {
        path: 'appauto/matrix-report',
        name: 'MatrixReport',
        component: () => import('@/views/appauto/MatrixReportView.vue'),
        meta: { titleKey: 'layout.matrixReport', icon: 'Histogram' },
      },
      {
        path: 'appauto/credentials',
        name: 'CredentialVault',
        component: () => import('@/views/appauto/CredentialVaultView.vue'),
        meta: { titleKey: 'layout.credentialVault', icon: 'Lock' },
      },
      {
        // Login-state ledger (AV batch facade): the read gate mirrors the
        // backend key perm:session:read; secrets never leave the server side.
        path: 'login-states',
        name: 'LoginStateLedger',
        component: () => import('@/views/session/LoginStateListView.vue'),
        meta: { titleKey: 'layout.loginStates', icon: 'Key', perm: 'perm:session:read' },
      },
      {
        path: 'appauto/packages',
        name: 'AppPackage',
        component: () => import('@/views/appauto/AppPackageView.vue'),
        meta: { titleKey: 'layout.appPackage', icon: 'Files' },
      },
      {
        // Element library (/api/v1/appauto/elements contract): reads ride the
        // project-view gate plus the core-echoed owner check, writes the manage
        // gate — all server-side single point, so no meta.perm (same posture as
        // DeviceWall/CredentialVault). Validation/uniqueness live in core only.
        path: 'appauto/elements',
        name: 'ElementLibrary',
        component: () => import('@/views/appauto/ElementLibraryView.vue'),
        meta: { titleKey: 'layout.elementLibrary', icon: 'Collection' },
      },
      {
        // Web element ledger (/api/v1/web-elements contract, WE batch S0):
        // reads ride the project-view gate, writes the manage gate on the
        // row's own project — every check server-side, so no meta.perm.
        path: 'web-elements',
        name: 'WebElementLedger',
        component: () => import('@/views/webelement/WebElementLedgerView.vue'),
        meta: { titleKey: 'layout.webElementLedger', icon: 'Collection' },
      },
      {
        // UI Flow canvas view (design segment B3): read-only projection of an APP case
        // step tree onto the shared mermaid flowchart — server-emitted nodes pairs are
        // the only clicked-node to step mapping. Reads ride the project-view gate on
        // the flowchart endpoint, so no static meta.perm (DeviceWall/ElementLibrary
        // precedent: validation is a server single point).
        path: 'appauto/flow-canvas',
        name: 'FlowCanvas',
        component: () => import('@/views/appauto/FlowCanvasView.vue'),
        meta: { titleKey: 'layout.flowCanvas', icon: 'Share' },
      },
      {
        path: 'appauto/popup-rules',
        name: 'PopupRuleApproval',
        component: () => import('@/views/appauto/PopupRuleApprovalView.vue'),
        meta: { titleKey: 'layout.popupRules', icon: 'MagicStick' },
      },
      {
        path: 'cluster/upgrade',
        name: 'UpgradeCenter',
        component: () => import('@/views/cluster/UpgradeCenterView.vue'),
        meta: { titleKey: 'layout.upgradeCenter', icon: 'Upload', perm: 'perm:cluster:manifest:view' },
      },
      {
        path: 'plans',
        name: 'PlanList',
        component: () => import('@/views/plan/PlanListView.vue'),
        meta: { titleKey: 'layout.plans', icon: 'Calendar' },
      },
      {
        path: 'plans/board',
        name: 'PlanBoard',
        component: () => import('@/views/plan/BoardView.vue'),
        meta: { titleKey: 'layout.board', icon: 'TrendCharts' },
      },
      {
        path: 'plans/:id',
        name: 'PlanDetail',
        component: () => import('@/views/plan/PlanDetailView.vue'),
        meta: { titleKey: 'layout.planDetail', hidden: true, parent: '/plans' },
      },
      {
        path: 'batches',
        name: 'BatchList',
        component: () => import('@/views/batch/BatchListView.vue'),
        meta: { titleKey: 'layout.batches', icon: 'Operation' },
      },
      {
        path: 'batches/:id',
        name: 'BatchDetail',
        component: () => import('@/views/batch/BatchDetailView.vue'),
        meta: { titleKey: 'layout.batchDetail', hidden: true, parent: '/batches' },
      },
      {
        path: 'defects',
        name: 'DefectLibrary',
        component: () => import('@/views/defect/DefectListView.vue'),
        meta: { titleKey: 'layout.defects', icon: 'Warning' },
      },
      {
        // AN-5: the regression-suites page merged into /cases as the
        // "suites" tab (suites are case bundles — same workbench). Functional
        // redirect keeps bookmarks and ⌘K deep links resolving.
        path: 'suites',
        redirect: (to) => ({ path: '/cases', query: { ...to.query, tab: 'suites' } }),
      },
      {
        // half B (P-1 ruling): the fires page merged into /batches as a
        // tab. The leaf is gone from the menu but the path keeps resolving —
        // old bookmarks/links land on the timed-trigger tab instead of 404ing.
        path: 'schedule/fires',
        redirect: '/batches?tab=fires',
      },
      {
        path: 'tags',
        name: 'TagList',
        component: () => import('@/views/tag/TagListView.vue'),
        meta: { titleKey: 'layout.tags', icon: 'PriceTag' },
      },
      {
        path: 'report',
        name: 'Report',
        component: () => import('@/views/report/ReportView.vue'),
        meta: { titleKey: 'layout.reports', icon: 'DataAnalysis' },
      },
      {
        path: 'test-report',
        name: 'TestReportList',
        component: () => import('@/views/report/TestReportListView.vue'),
        meta: { titleKey: 'layout.testReport', icon: 'Document' },
      },
      {
        path: 'data-factories',
        name: 'DataFactoryList',
        component: () => import('@/views/datafactory/DataFactoryListView.vue'),
        meta: { titleKey: 'layout.dataFactory', icon: 'MagicStick' },
      },
      {
        // AN-3 half A (report-domain merge): the coverage-matrix page
        // merged into /report as a tab. The leaf is gone from the menu but the
        // path keeps resolving — old bookmarks and the AI-generate deep link
        // (router.push /coverage?projectId=…) land on the coverage tab. The
        // redirect is functional (not a static string) so the caller's query
        // (projectId) survives the hop; tab wins over any stale tab param.
        path: 'coverage',
        redirect: (to) => ({ path: '/report', query: { ...to.query, tab: 'coverage' } }),
      },
      {
        path: 'cost',
        name: 'CostDashboard',
        component: () => import('@/views/cost/CostDashboardView.vue'),
        meta: { titleKey: 'layout.cost', icon: 'Coin' },
      },
      {
        // AN-3 half B (report-domain merge): the O10 report-center page
        // merged into /report as the export/snapshot tab. The leaf is gone
        // from the menu but the path keeps resolving — bookmarks and ⌘K deep
        // links land on the tab. Functional redirect (not a static string) so
        // the caller's query survives the hop; tab wins over any stale param.
        // Permission semantics unchanged: the route carried no perm key before
        // the merge (backend single-point guards).
        path: 'report-center',
        redirect: (to) => ({ path: '/report', query: { ...to.query, tab: 'export' } }),
      },
      {
        path: 'roles',
        name: 'RoleList',
        component: () => import('@/views/rbac/RoleListView.vue'),
        meta: { titleKey: 'layout.roles', icon: 'UserFilled', perm: 'perm:system:role-manage' },
      },
      {
        path: 'users',
        name: 'UserList',
        component: () => import('@/views/user/UserListView.vue'),
        meta: { titleKey: 'layout.users', icon: 'User', perm: 'perm:system:user-manage' },
      },
      {
        path: 'configs',
        name: 'ConfigList',
        component: () => import('@/views/system/ConfigListView.vue'),
        meta: { titleKey: 'layout.configs', icon: 'Setting', perm: 'perm:system:system-config' },
      },
      {
        path: 'llm-configs',
        name: 'LlmConfigList',
        component: () => import('@/views/system/LlmConfigListView.vue'),
        meta: { titleKey: 'layout.llmConfigs', icon: 'Cpu', perm: 'perm:system:llm-config' },
      },
      {
        // AN-6 (cost-domain merge): the Token calibration page merged
        // into /cost as the calibration tab. The leaf is gone from the menu
        // but the path keeps resolving — bookmarks and ⌘K deep links land on
        // the tab. Functional redirect (not a static string) so the caller's
        // query survives the hop; tab wins over any stale param. Permission
        // semantics unchanged: the route guard key (perm:system:system-config)
        // rides the tab (tab-gated mirror, R24 face ④).
        path: 'usage-calibration',
        redirect: (to) => ({ path: '/cost', query: { ...to.query, tab: 'calibration' } }),
      },
      {
        path: 'scheduler-health',
        name: 'SchedulerHealth',
        component: () => import('@/views/system/SchedulerHealthView.vue'),
        meta: { titleKey: 'layout.schedulerHealth', icon: 'Timer', perm: 'perm:system:system-config' },
      },
      {
        path: 'audit-logs',
        name: 'AuditLogList',
        component: () => import('@/views/system/AuditLogView.vue'),
        meta: { titleKey: 'layout.auditLogs', icon: 'Document', perm: 'perm:detail:audit-view' },
      },
      {
        // : dead-letter ops page (read doors only; replay/void doors land with
        // behind their own permission keys). Backlog basis = ledger rows, never Rabbit depth.
        path: 'mq-dead-letter',
        name: 'DeadLetterQueue',
        component: () => import('@/views/system/DeadLetterQueueView.vue'),
        meta: { titleKey: 'layout.deadLetterQueue', icon: 'Warning', perm: 'perm:system:dead-letter-read' },
      },
      {
        path: 'notifications',
        name: 'NotificationCenter',
        component: () => import('@/views/notification/NotificationCenterView.vue'),
        meta: { titleKey: 'layout.notifications', hidden: true },
      },
      {
        // AN-5: the SKILL market merged in as the host's second tab.
        path: 'skills',
        name: 'SkillList',
        component: () => import('@/views/skill/SkillListView.vue'),
        meta: { titleKey: 'layout.skills', icon: 'MagicStick' },
      },
      {
        // AN-5: market leaf retired into the /skills tab host.
        path: 'skill-market',
        redirect: (to) => ({ path: '/skills', query: { ...to.query, tab: 'market' } }),
      },
      {
        path: 'tools',
        name: 'ToolList',
        component: () => import('@/views/tool/ToolListView.vue'),
        meta: { titleKey: 'layout.tools', icon: 'SetUp' },
      },
      {
        // AN-5: market leaf retired into the /tools tab host.
        path: 'tool-market',
        redirect: (to) => ({ path: '/tools', query: { ...to.query, tab: 'market' } }),
      },
      {
        // AN-5 (extensions merge): the plugin list and the MCP server
        // pages merged into /extensions as tabs. The plugin route used to
        // mirror perm:project:plugin-framework in meta; after the merge the
        // leaf carries NO menu perm (that would narrow the MCP visible
        // domain), so the key now gates the plugin tab inside the host via
        // hasPerm — zero permission semantics change, only the surface moves.
        path: 'plugins',
        redirect: (to) => ({ path: '/extensions', query: { ...to.query, tab: 'plugins' } }),
      },
      {
        path: 'mcp-servers',
        redirect: (to) => ({ path: '/extensions', query: { ...to.query, tab: 'mcp' } }),
      },
      {
        path: 'extensions',
        name: 'ExtensionsHost',
        component: () => import('@/views/extension/ExtensionsHostView.vue'),
        meta: { titleKey: 'layout.extensions', icon: 'Grid' },
      },
      {
        path: 'extraction',
        name: 'ExtractionHost',
        component: () => import('@/views/ai/DocumentListView.vue'),
        meta: { titleKey: 'layout.extraction', icon: 'List' },
        // Merged-tab-era bookmarks asking for the requirements tab hop to the
        // standalone ledger; caller query is kept, the stale tab key drops.
        beforeEnter: (to) => {
          if (to.query.tab === 'requirements') {
            const rest = { ...to.query }
            delete rest.tab
            return { path: '/requirements', query: rest }
          }
          return true
        },
      },
      {
        path: 'knowledge',
        name: 'KnowledgeDocList',
        component: () => import('@/views/knowledge/KnowledgeDocListView.vue'),
        meta: { titleKey: 'layout.knowledge', icon: 'Notebook', perm: 'perm:knowledge:mcp-manage' },
      },
      {
        path: 'training-samples',
        name: 'TrainingSampleList',
        component: () => import('@/views/sample/TrainingSampleListView.vue'),
        meta: { titleKey: 'layout.trainingSamples', icon: 'Collection', perm: 'perm:platform:data-mask' },
      },
      {
        path: 'prompt-templates',
        name: 'PromptTemplateList',
        component: () => import('@/views/prompt/PromptTemplateListView.vue'),
        meta: { titleKey: 'layout.promptTemplates', icon: 'DocumentCopy', perm: 'perm:feedback:model-update' },
      },
      {
        // Evaluation-set management page (AW1/AW2 door): admin bridge
        // /api/v1/eval-sets family; route meta.perm mirrors the backend key
        // perm:judge:evalset:view so menu and route visibility stay equal.
        path: 'judge/evalset',
        name: 'JudgeEvalset',
        component: () => import('@/views/judge/EvalSetListView.vue'),
        meta: { titleKey: 'layout.judgeEvalset', icon: 'Collection', perm: 'perm:judge:evalset:view' },
      },
      {
        // Run-history door (AW4): create runs over the pinned evalset
        // version x rubric anchors and dispatch pending runs; the report
        // deep link ?runId= stays owned by JudgeReportView (K2 same slot).
        path: 'judge/runs',
        name: 'JudgeRunList',
        component: () => import('@/views/judge/JudgeRunListView.vue'),
        meta: { titleKey: 'layout.judgeRuns', icon: 'Timer', perm: 'perm:judge:run:view' },
      },
      {
        path: 'judge/report',
        name: 'JudgeReport',
        component: () => import('@/views/judge/JudgeReportView.vue'),
        meta: { titleKey: 'layout.judgeReport', icon: 'DataAnalysis', perm: 'perm:judge:run:view' },
      },
      {
        path: 'api-tokens',
        name: 'ApiTokenList',
        component: () => import('@/views/token/ApiTokenView.vue'),
        meta: { titleKey: 'layout.apiTokens', icon: 'Key' },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/ProfileView.vue'),
        meta: { titleKey: 'layout.profile', hidden: true },
      },
      {
        path: 'api-test',
        name: 'ApiTestList',
        component: () => import('@/views/apitest/ApiTestView.vue'),
        meta: { titleKey: 'layout.apiTest', icon: 'Connection' },
      },
      {
        path: 'role-bindings',
        name: 'RoleBindingEditor',
        component: () => import('@/views/apitest/RoleBindingEditorView.vue'),
        meta: { titleKey: 'layout.roleCard', icon: 'UserFilled' },
      },
      {
        // AH batch W1c interface inventory page (docs/02 contract:
        // /api/v1/api-inventory/{endpoints,coverage}; route meta.perm mirrors
        // the admin gate perm:apitest:inventory:read).
        path: 'api-inventory',
        name: 'ApiInventory',
        component: () => import('@/views/apitest/ApiInventoryView.vue'),
        meta: { titleKey: 'layout.apiInventory', icon: 'Tickets', perm: 'perm:apitest:inventory:read' },
      },
      {
        // AH batch contract change-analysis review queue (docs/02 contract:
        // /api/v1/api-spec-change-analyses). Permission = backend single point
        // (list = project member, disposition = the requirement write face of the
        // row's owning project) — route meta.perm stays unattached, api-test precedent.
        path: 'api-spec-change-analysis',
        name: 'SpecChangeAnalysis',
        component: () => import('@/views/apitest/SpecChangeAnalysisView.vue'),
        meta: { titleKey: 'layout.specChange', icon: 'DocumentChecked' },
      },
      {
        // V240 endpoint inventory (02 contract: /api/v1/mock/serving-inventory/page;
        // read gate mirrors the admin perm:mock:read, same family as spaces/docs).
        path: 'mock/endpoints',
        name: 'MockEndpoints',
        component: () => import('@/views/mock/MockEndpointsView.vue'),
        meta: { titleKey: 'layout.mockEndpoints', icon: 'Compass', perm: 'perm:mock:read' },
      },
      {
        // AN-4: the traffic inspector merged into the space workbench as
        // the inspector tab. The tab carries its own perm:mock:traffic gate
        // inside the workbench (same code the retired route meta had).
        path: 'mock/inspector',
        redirect: mockWorkbenchRedirect('inspector'),
      },
      {
        // AG batch space workbench (02 v2.59/v2.63 contract: spaces CRUD +
        // lifecycle transition + serving-url; route meta.perm mirrors the
        // admin gate perm:mock:read).
        path: 'mock/spaces',
        name: 'MockSpaces',
        component: () => import('@/views/mock/MockSpacesView.vue'),
        meta: { titleKey: 'layout.mockSpaces', icon: 'Guide', perm: 'perm:mock:read' },
      },
      {
        // AN-4: per-space workbench hosting the docs / scenarios /
        // recording / inspector tabs (four menu leaves collapse into this
        // hidden detail page — the group goes 6 leaves → 2). perm mirrors
        // the admin read gate; the traffic tab keeps its own perm:mock:traffic
        // gate inside the page (a leaf route gate must not widen traffic).
        path: 'mock/spaces/:id',
        name: 'MockSpaceWorkbench',
        component: () => import('@/views/mock/MockSpaceWorkbenchView.vue'),
        meta: { titleKey: 'layout.mockSpaces', hidden: true, parent: '/mock/spaces', perm: 'perm:mock:read' },
      },
      {
        // AN-4: the Mock-as-documentation page merged into the space
        // workbench as the docs tab. Query survives the hop (§12a links carry
        // projectId/spaceId/source), so deep links land on the right space.
        path: 'mock/docs',
        redirect: mockWorkbenchRedirect('docs'),
      },
      {
        // AN-4: the standalone scenario list merged into the workbench
        // scenarios tab (the scenario detail keeps its own addressable path).
        path: 'mock/scenarios',
        redirect: mockWorkbenchRedirect('scenarios'),
      },
      {
        // scenario detail: one state-machine graph per screen (hidden
        // leaf under the workbench, space/project ride the query — deep-linkable).
        path: 'mock/scenarios/:id',
        name: 'MockScenarioDetail',
        component: () => import('@/views/mock/MockScenarioDetailView.vue'),
        meta: { titleKey: 'layout.mockScenarios', hidden: true, parent: '/mock/spaces/:id', perm: 'perm:mock:read' },
      },
      {
        // AN-4: cassette recording merged into the workbench recording tab.
        path: 'mock/recording',
        redirect: mockWorkbenchRedirect('recording'),
      },
      {
        path: 'monitor',
        name: 'MonitorList',
        component: () => import('@/views/monitor/MonitorView.vue'),
        meta: { titleKey: 'layout.monitor', icon: 'Monitor' },
      },
      {
        path: 'perf',
        name: 'PerfWorkbench',
        component: () => import('@/views/perf/PerfWorkbenchView.vue'),
        meta: { titleKey: 'layout.perf', icon: 'Odometer' },
      },
      {
 // Project-wide load-run ledger : every run of the header project
        // segment including history whose scenario was deleted. Read door = the
        // same server-side member guard as the run drawer — no menu/route perm.
        path: 'perf/runs',
        name: 'PerfRunLedger',
        component: () => import('@/views/perf/PerfRunLedgerView.vue'),
        meta: { titleKey: 'layout.perfRuns', icon: 'Histogram' },
      },
      {
        path: 'perf/run/:id',
        name: 'PerfRunDetail',
        component: () => import('@/views/perf/PerfRunDetailView.vue'),
        meta: { titleKey: 'layout.perf', hidden: true, parent: '/perf' },
      },
      {
        // Shared asset pool : /api/v1/shared-assets bridge;
        // pool read is global by product rule, the owner fence for withdrawal
        // stays server-side (route meta mirrors the pool-read key).
        path: 'asset-pool',
        name: 'AssetPool',
        component: () => import('@/views/asset/AssetPoolView.vue'),
        meta: { titleKey: 'layout.assetPool', icon: 'Box', perm: 'perm:asset:pool:read' },
      },
      {
        // Journey orchestration : cross-project journey plans under a
        // program; the wall is paged by the core bridge (S-group contract).
        path: 'journeys',
        name: 'JourneyWall',
        component: () => import('@/views/journey/JourneyWallView.vue'),
        meta: { titleKey: 'layout.journeys', icon: 'Guide', perm: 'perm:project:project-view' },
      },
      {
        // Ordered segment table editor (form-style UX by product ruling, no canvas).
        path: 'journeys/:id',
        name: 'JourneyPlan',
        component: () => import('@/views/journey/JourneyPlanDetailView.vue'),
        meta: { titleKey: 'layout.journeys', hidden: true, parent: '/journeys' },
      },
      {
        // Full-chain run timeline: per-segment traffic lights + owner-only exit sign-off.
        path: 'journeys/runs/:runId',
        name: 'JourneyRunTimeline',
        component: () => import('@/views/journey/JourneyRunTimelineView.vue'),
        meta: { titleKey: 'layout.journeys', hidden: true, parent: '/journeys' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]
