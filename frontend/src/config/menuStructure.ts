


export interface MenuLeaf {
  path: string
  icon: string
  titleKey: string
 
  dataTest: string
  perm?: string
 
  helpDomain: string
  
  helpTipKey: string
  
  helpAnchorKey?: string
}

/** Top-level journey group: items = direct leaves. R16 site-wide ruling caps menu
 *  nesting at two levels (group -> leaf); the three-level sub-group construct was
 *  removed entirely so it cannot come back. */
export interface JourneyGroup {
  index: string
  icon: string
  titleKey: string
  items?: MenuLeaf[]
}


export const journeyGroups: JourneyGroup[] = [
  {
    index: 'workbench', icon: 'Odometer', titleKey: 'layout.journeyWorkbench',
    items: [
      { path: '/dashboard', icon: 'Odometer', titleKey: 'layout.dashboard', dataTest: 'menu-dashboard', helpDomain: 'O', helpTipKey: 'layout.leafHelp.dashboard' },
    ],
  },
  {
    // AN-5: /requirements + /documents retired as leaves — both merged
    // /extraction keeps documents; the requirement ledger is its own leaf (AL batch).
    index: 'design', icon: 'EditPen', titleKey: 'layout.journeyDesign',
    items: [
      { path: '/extraction', icon: 'List', titleKey: 'layout.extraction', dataTest: 'menu-extraction', helpDomain: 'F', helpTipKey: 'layout.leafHelp.extraction', helpAnchorKey: 'layout.leafHelpAnchor.extraction' },
      { path: '/requirements', icon: 'Document', titleKey: 'layout.requirements', dataTest: 'menu-requirements', helpDomain: 'F', helpTipKey: 'layout.leafHelp.requirements', helpAnchorKey: 'layout.leafHelpAnchor.requirements' },
      { path: '/ai/generate', icon: 'MagicStick', titleKey: 'layout.aiGenerate', dataTest: 'menu-ai-generate', helpDomain: 'A', helpTipKey: 'layout.leafHelp.aiGenerate' },
      { path: '/cases', icon: 'Document', titleKey: 'layout.cases', dataTest: 'menu-cases', helpDomain: 'C', helpTipKey: 'layout.leafHelp.cases' },
      { path: '/review', icon: 'Checked', titleKey: 'layout.review', dataTest: 'menu-review', helpDomain: 'C', helpTipKey: 'layout.leafHelp.review' },
      { path: '/web-elements', icon: 'Collection', titleKey: 'layout.webElementLedger', dataTest: 'menu-web-element-ledger', helpDomain: 'F', helpTipKey: 'layout.leafHelp.webElementLedger', helpAnchorKey: 'layout.leafHelpAnchor.webElementLedger' },
    ],
  },
  {
    index: 'aiCapabilities', icon: 'MagicStick', titleKey: 'layout.journeyAi',
    items: [
      // stage 3: the standalone chat page joined as a leaf (same
      // kernel as the dock); its key mirrors the backend session door.
      { path: '/ai/chat', icon: 'ChatDotRound', titleKey: 'layout.aiChat', dataTest: 'menu-ai-chat', perm: 'perm:aichat:use', helpDomain: 'AI', helpTipKey: 'layout.leafHelp.aiChat' },
      { path: '/knowledge', icon: 'Notebook', titleKey: 'layout.knowledge', dataTest: 'menu-knowledge', perm: 'perm:knowledge:mcp-manage', helpDomain: 'G', helpTipKey: 'layout.leafHelp.knowledge', helpAnchorKey: 'layout.leafHelpAnchor.knowledge' },
      { path: '/prompt-templates', icon: 'DocumentCopy', titleKey: 'layout.promptTemplates', dataTest: 'menu-prompt-templates', perm: 'perm:feedback:model-update', helpDomain: 'D', helpTipKey: 'layout.leafHelp.promptTemplates', helpAnchorKey: 'layout.leafHelpAnchor.promptTemplates' },
      { path: '/skills', icon: 'MagicStick', titleKey: 'layout.skills', dataTest: 'menu-skills', helpDomain: 'S', helpTipKey: 'layout.leafHelp.skills' },
      { path: '/tools', icon: 'SetUp', titleKey: 'layout.tools', dataTest: 'menu-tools', helpDomain: 'U', helpTipKey: 'layout.leafHelp.tools' },
      // AN-5: plugin list + MCP servers retired as leaves — merged into the
      // /extensions host tabs. No menu perm here by ruling: the plugin key
      // rides the in-page tab gate (hasPerm), attaching it to the leaf would
      // narrow the MCP visible domain = permission-surface drift.
      { path: '/extensions', icon: 'Grid', titleKey: 'layout.extensions', dataTest: 'menu-extensions', helpDomain: 'G', helpTipKey: 'layout.leafHelp.extensions', helpAnchorKey: 'layout.leafHelpAnchor.extensions' },
      { path: '/training-samples', icon: 'Collection', titleKey: 'layout.trainingSamples', dataTest: 'menu-training-samples', perm: 'perm:platform:data-mask', helpDomain: 'D', helpTipKey: 'layout.leafHelp.trainingSamples', helpAnchorKey: 'layout.leafHelpAnchor.trainingSamples' },
    ],
  },
  {
    index: 'execute', icon: 'VideoCamera', titleKey: 'layout.journeyExecute',
    items: [
      { path: '/plans', icon: 'Calendar', titleKey: 'layout.plans', dataTest: 'menu-plans', helpDomain: 'L', helpTipKey: 'layout.leafHelp.plans' },
      { path: '/plans/board', icon: 'TrendCharts', titleKey: 'layout.board', dataTest: 'menu-board', helpDomain: 'L', helpTipKey: 'layout.leafHelp.board' },
      { path: '/batches', icon: 'Operation', titleKey: 'layout.batches', dataTest: 'menu-batches', helpDomain: 'I', helpTipKey: 'layout.leafHelp.batches', helpAnchorKey: 'layout.leafHelpAnchor.batches' },
      { path: '/journeys', icon: 'Guide', titleKey: 'layout.journeys', dataTest: 'menu-journeys', perm: 'perm:project:project-view', helpDomain: 'I', helpTipKey: 'layout.leafHelp.journeys', helpAnchorKey: 'layout.leafHelpAnchor.journeys' },
      { path: '/execution', icon: 'VideoPlay', titleKey: 'layout.execution', dataTest: 'menu-execution', helpDomain: 'I', helpTipKey: 'layout.leafHelp.execution', helpAnchorKey: 'layout.leafHelpAnchor.execution' },
      { path: '/api-test', icon: 'Connection', titleKey: 'layout.apiTest', dataTest: 'menu-api-test', helpDomain: 'T', helpTipKey: 'layout.leafHelp.apiTest', helpAnchorKey: 'layout.leafHelpAnchor.apiTest' },
      { path: '/api-inventory', icon: 'Tickets', titleKey: 'layout.apiInventory', dataTest: 'menu-api-inventory', perm: 'perm:apitest:inventory:read', helpDomain: 'T', helpTipKey: 'layout.leafHelp.apiInventory', helpAnchorKey: 'layout.leafHelpAnchor.apiInventory' },
      { path: '/api-spec-change-analysis', icon: 'DocumentChecked', titleKey: 'layout.specChange', dataTest: 'menu-spec-change-analysis', helpDomain: 'T', helpTipKey: 'layout.leafHelp.specChange', helpAnchorKey: 'layout.leafHelpAnchor.specChange' },
      { path: '/monitor', icon: 'Monitor', titleKey: 'layout.monitor', dataTest: 'menu-monitor', helpDomain: 'I', helpTipKey: 'layout.leafHelp.monitor', helpAnchorKey: 'layout.leafHelpAnchor.monitor' },
      { path: '/perf', icon: 'Odometer', titleKey: 'layout.perf', dataTest: 'menu-perf', helpDomain: 'W', helpTipKey: 'layout.leafHelp.perf' },
      // Project-wide load-run ledger : same read guard precedent — no menu perm
      { path: '/perf/runs', icon: 'Histogram', titleKey: 'layout.perfRuns', dataTest: 'menu-perf-runs', helpDomain: 'W', helpTipKey: 'layout.leafHelp.perfRuns' },
    ],
  },
  {
    // Two-level menu restructure (site-wide depth ruling): the APP automation leaves
    // used to be folded as a third level inside "execute"; they now form their own
    // top-level group, ordered daily-four then assets-five (11-16: the credential
    // vault and login-state ledger moved out to data & assets - they are reusable
    // session/secret assets, not app-automation tooling).
    index: 'appauto', icon: 'Cellphone', titleKey: 'layout.journeyAppauto',
    items: [
 // Device wall: occupancy projection + profile CRUD (access guarded server-side, no menu perm)
      { path: '/appauto/devices', icon: 'Cellphone', titleKey: 'layout.deviceWall', dataTest: 'menu-device-wall', helpDomain: 'X', helpTipKey: 'layout.leafHelp.deviceWall', helpAnchorKey: 'layout.leafHelpAnchor.deviceWall' },
 // N1 device reservation calendar (same precedent: no menu perm)
      { path: '/appauto/reservations', icon: 'Clock', titleKey: 'layout.reservationCalendar', dataTest: 'menu-reservation-calendar', helpDomain: 'X', helpTipKey: 'layout.leafHelp.reservationCalendar', helpAnchorKey: 'layout.leafHelpAnchor.reservationCalendar' },
 // Engine center: menu perm mirrors the route meta perm code
      { path: '/appauto/engines', icon: 'Cpu', titleKey: 'layout.engineCenter', dataTest: 'menu-engine-center', perm: 'perm:appauto:engine:view', helpDomain: 'X', helpTipKey: 'layout.leafHelp.engineCenter', helpAnchorKey: 'layout.leafHelpAnchor.engineCenter' },
 // Step-healing suggestion board (same precedent: no menu perm)
      { path: '/appauto/healing', icon: 'MagicStick', titleKey: 'layout.stepHealing', dataTest: 'menu-step-healing', helpDomain: 'X', helpTipKey: 'layout.leafHelp.stepHealing', helpAnchorKey: 'layout.leafHelpAnchor.stepHealing' },
 // App-under-test profiles (pkg dual key + H1 data gate server-side, no menu perm)
      { path: '/appauto/packages', icon: 'Files', titleKey: 'layout.appPackage', dataTest: 'menu-app-package', helpDomain: 'X', helpTipKey: 'layout.leafHelp.appPackage', helpAnchorKey: 'layout.leafHelpAnchor.appPackage' },
      { path: '/appauto/elements', icon: 'Collection', titleKey: 'layout.elementLibrary', dataTest: 'menu-element-library', helpDomain: 'X', helpTipKey: 'layout.leafHelp.elementLibrary', helpAnchorKey: 'layout.leafHelpAnchor.elementLibrary' },
 // UI Flow canvas: read-only projection of an APP case step tree (help chapter seeded in the same batch)
      { path: '/appauto/flow-canvas', icon: 'Share', titleKey: 'layout.flowCanvas', dataTest: 'menu-flow-canvas', helpDomain: 'X', helpTipKey: 'layout.leafHelp.flowCanvas', helpAnchorKey: 'layout.leafHelpAnchor.flowCanvas' },
 // Popup-rule self-learning approval desk (same precedent: no menu perm)
      { path: '/appauto/popup-rules', icon: 'MagicStick', titleKey: 'layout.popupRules', dataTest: 'menu-popup-rules', helpDomain: 'X', helpTipKey: 'layout.leafHelp.popupRules', helpAnchorKey: 'layout.leafHelpAnchor.popupRules' },
      { path: '/appauto/samples', icon: 'DataAnalysis', titleKey: 'layout.aiSampleBoard', dataTest: 'menu-ai-samples', helpDomain: 'X', helpTipKey: 'layout.leafHelp.aiSampleBoard', helpAnchorKey: 'layout.leafHelpAnchor.aiSampleBoard' },
    ],
  },
  {
    // AG batch: standalone "API Mocking" journey group — the inspector entry that shows
    // how declared rules actually behave on the serving plane (hit/miss traffic truth).
    index: 'mock', icon: 'Guide', titleKey: 'layout.journeyMock',
    items: [
      // AN-4: docs / scenarios / recording / inspector retired as menu
      // leaves — they merged into the per-space workbench (/mock/spaces/:id
      // ?tab=…, hidden detail route). The workbench gates the inspector tab
      // in-page by perm:mock:traffic, so the leaf-level traffic perm retires
      // with the leaf; the permission key itself stays live in the backend
      // gate (permission ledger zero-change on keys, only menu surface moves).
      { path: '/mock/spaces', icon: 'Guide', titleKey: 'layout.mockSpaces', dataTest: 'menu-mock-spaces', perm: 'perm:mock:read', helpDomain: 'T', helpTipKey: 'layout.leafHelp.mockSpaces', helpAnchorKey: 'layout.leafHelpAnchor.mockSpaces' },
      { path: '/mock/endpoints', icon: 'Compass', titleKey: 'layout.mockEndpoints', dataTest: 'menu-mock-endpoints', perm: 'perm:mock:read', helpDomain: 'T', helpTipKey: 'layout.leafHelp.mockEndpoints', helpAnchorKey: 'layout.leafHelpAnchor.mockEndpoints' },
    ],
  },
  {
    index: 'results', icon: 'DataAnalysis', titleKey: 'layout.journeyResults',
    items: [
      { path: '/test-report', icon: 'Document', titleKey: 'layout.testReport', dataTest: 'menu-test-report', helpDomain: 'Q', helpTipKey: 'layout.leafHelp.testReport', helpAnchorKey: 'layout.leafHelpAnchor.testReport' },
      { path: '/report', icon: 'DataAnalysis', titleKey: 'layout.reports', dataTest: 'menu-reports', helpDomain: 'Q', helpTipKey: 'layout.leafHelp.reports', helpAnchorKey: 'layout.leafHelpAnchor.reports' },
      { path: '/program-dashboard', icon: 'Odometer', titleKey: 'layout.programDashboard', dataTest: 'menu-program-dashboard', helpDomain: 'Q', helpTipKey: 'layout.leafHelp.programDashboard', helpAnchorKey: 'layout.leafHelpAnchor.programDashboard' },
      { path: '/defects', icon: 'Warning', titleKey: 'layout.defects', dataTest: 'menu-defects', helpDomain: 'J', helpTipKey: 'layout.leafHelp.defects' },
      // Evaluation-set leaf (AW door): menu mirrors backend key perm:judge:evalset:view.
      // Help domain Y now carries two chapters, so both leaves pin their own anchor
      { path: '/judge/evalset', icon: 'Collection', titleKey: 'layout.judgeEvalset', dataTest: 'menu-judge-evalset', perm: 'perm:judge:evalset:view', helpDomain: 'Y', helpTipKey: 'layout.leafHelp.judgeEvalset', helpAnchorKey: 'layout.leafHelpAnchor.judgeEvalset' },
      // Run-history leaf (AW4 door): menu mirrors backend key perm:judge:run:view.
      { path: '/judge/runs', icon: 'Timer', titleKey: 'layout.judgeRuns', dataTest: 'menu-judge-runs', perm: 'perm:judge:run:view', helpDomain: 'Y', helpTipKey: 'layout.leafHelp.judgeRuns', helpAnchorKey: 'layout.leafHelpAnchor.judgeRuns' },
      // Judge report leaf: backend single source is perm:judge:run:view (M1 migration), menu mirrors the code
      { path: '/judge/report', icon: 'DataAnalysis', titleKey: 'layout.judgeReport', dataTest: 'menu-judge-report', perm: 'perm:judge:run:view', helpDomain: 'Y', helpTipKey: 'layout.leafHelp.judgeReport', helpAnchorKey: 'layout.leafHelpAnchor.judgeReport' },
    ],
  },
  {
    index: 'dataAssets', icon: 'Box', titleKey: 'layout.journeyDataAssets',
    items: [
      { path: '/data-factories', icon: 'MagicStick', titleKey: 'layout.dataFactory', dataTest: 'menu-data-factories', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.dataFactory' , helpAnchorKey: 'layout.leafHelpAnchor.dataFactory' },
      // AN-5: /suites retired as a leaf — merged into the /cases tab
      // (regression suites ride the case workbench now; old path redirects).
      { path: '/tags', icon: 'PriceTag', titleKey: 'layout.tags', dataTest: 'menu-tags', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.tags' , helpAnchorKey: 'layout.leafHelpAnchor.tags' },
      { path: '/asset-pool', icon: 'Box', titleKey: 'layout.assetPool', dataTest: 'menu-asset-pool', perm: 'perm:asset:pool:read', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.assetPool' , helpAnchorKey: 'layout.leafHelpAnchor.assetPool' },
      { path: '/explore', icon: 'Compass', titleKey: 'layout.explore', dataTest: 'menu-explore', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.explore' , helpAnchorKey: 'layout.leafHelpAnchor.explore' },
      { path: '/role-bindings', icon: 'UserFilled', titleKey: 'layout.roleCard', dataTest: 'menu-role-bindings', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.roleCard' , helpAnchorKey: 'layout.leafHelpAnchor.roleCard' },
      // 11-16 user ruling: the credential vault and the login-state ledger are
      // reusable session/secret assets, not app-automation tooling; they moved to
      // the data & assets group. Paths, perms and data-test anchors unchanged; the
      // help chapter follows the group (V535 migrates the vault chapter to domain Z;
      // the ledger has no dedicated chapter yet - registered debt, see the ruling).
 // Credential vault: secrets are never echoed back (same precedent: no menu perm)
      { path: '/appauto/credentials', icon: 'Lock', titleKey: 'layout.credentialVault', dataTest: 'menu-credential-vault', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.credentialVault', helpAnchorKey: 'layout.leafHelpAnchor.credentialVault' },
      // Login-state ledger (AV batch): browser/token sessions for API + UI +
      // monitor consumers; leaf perm mirrors the backend read key.
      { path: '/login-states', icon: 'Key', titleKey: 'layout.loginStates', dataTest: 'menu-login-states', perm: 'perm:session:read', helpDomain: 'Z', helpTipKey: 'layout.leafHelp.loginStates', helpAnchorKey: 'layout.leafHelpAnchor.loginStates' },
    ],
  },
  {
    index: 'system', icon: 'Setting', titleKey: 'layout.system',
    items: [
      { path: '/projects', icon: 'FolderOpened', titleKey: 'layout.projects', dataTest: 'menu-projects', helpDomain: 'H', helpTipKey: 'layout.leafHelp.projects', helpAnchorKey: 'layout.leafHelpAnchor.projects' },
      // Y-wave : program-set console leaf (global governance surface —
      // programs group projects, they are not scoped by one header project).
      { path: '/programs', icon: 'Grid', titleKey: 'layout.programs', dataTest: 'menu-programs', helpDomain: 'H', helpTipKey: 'layout.leafHelp.programs', helpAnchorKey: 'layout.leafHelpAnchor.programs' },
      { path: '/api-tokens', icon: 'Key', titleKey: 'layout.apiTokens', dataTest: 'menu-api-tokens', helpDomain: 'H', helpTipKey: 'layout.leafHelp.apiTokens', helpAnchorKey: 'layout.leafHelpAnchor.apiTokens' },
      { path: '/delete-requests', icon: 'DocumentDelete', titleKey: 'layout.deleteRequests', dataTest: 'menu-delete-requests', helpDomain: 'P', helpTipKey: 'layout.leafHelp.deleteRequests' },
      { path: '/roles', icon: 'UserFilled', titleKey: 'layout.roles', dataTest: 'menu-roles', perm: 'perm:system:role-manage', helpDomain: 'H', helpTipKey: 'layout.leafHelp.roles', helpAnchorKey: 'layout.leafHelpAnchor.roles' },
      { path: '/users', icon: 'User', titleKey: 'layout.users', dataTest: 'menu-users', perm: 'perm:system:user-manage', helpDomain: 'H', helpTipKey: 'layout.leafHelp.users', helpAnchorKey: 'layout.leafHelpAnchor.users' },
      { path: '/configs', icon: 'Setting', titleKey: 'layout.configs', dataTest: 'menu-configs', perm: 'perm:system:system-config', helpDomain: 'K', helpTipKey: 'layout.leafHelp.configs', helpAnchorKey: 'layout.leafHelpAnchor.configs' },
      { path: '/llm-configs', icon: 'Cpu', titleKey: 'layout.llmConfigs', dataTest: 'menu-llm-configs', perm: 'perm:system:llm-config', helpDomain: 'E', helpTipKey: 'layout.leafHelp.llmConfigs' },
      { path: '/audit-logs', icon: 'Document', titleKey: 'layout.auditLogs', dataTest: 'menu-audit-logs', perm: 'perm:detail:audit-view', helpDomain: 'M', helpTipKey: 'layout.leafHelp.auditLogs' },
      { path: '/mq-dead-letter', icon: 'Warning', titleKey: 'layout.deadLetterQueue', dataTest: 'menu-mq-dead-letter', perm: 'perm:system:dead-letter-read', helpDomain: 'K', helpTipKey: 'layout.leafHelp.deadLetterQueue', helpAnchorKey: 'layout.leafHelpAnchor.deadLetterQueue' },
      // AN-6: /usage-calibration retired as a leaf — merged into /cost as
      // mirror of the route guard; old path redirects). One story: spend money
      // (cost board) and reconcile how the figures were estimated (calibration).
      { path: '/cost', icon: 'Coin', titleKey: 'layout.cost', dataTest: 'menu-cost', helpDomain: 'E', helpTipKey: 'layout.leafHelp.cost' },
      { path: '/scheduler-health', icon: 'Timer', titleKey: 'layout.schedulerHealth', dataTest: 'menu-scheduler-health', perm: 'perm:system:system-config', helpDomain: 'K', helpTipKey: 'layout.leafHelp.schedulerHealth', helpAnchorKey: 'layout.leafHelpAnchor.schedulerHealth' },
      { path: '/execution/pools', icon: 'Grid', titleKey: 'layout.executorPools', dataTest: 'menu-executor-pools', perm: 'perm:project:executor-pool', helpDomain: 'I', helpTipKey: 'layout.leafHelp.executorPools', helpAnchorKey: 'layout.leafHelpAnchor.executorPools' },
      { path: '/cluster/upgrade', icon: 'Upload', titleKey: 'layout.upgradeCenter', dataTest: 'menu-upgrade-center', perm: 'perm:cluster:manifest:view', helpDomain: 'K', helpTipKey: 'layout.leafHelp.upgradeCenter', helpAnchorKey: 'layout.leafHelpAnchor.upgradeCenter' },
    ],
  },
]


export function groupLeaves(grp: JourneyGroup): MenuLeaf[] {
  return grp.items ?? []
}


export function filterVisibleLeaves(leaves: MenuLeaf[], hasPerm: (code: string) => boolean): MenuLeaf[] {
  return leaves.filter((it) => !it.perm || hasPerm(it.perm))
}


export const allMenuLeaves: MenuLeaf[] = journeyGroups.flatMap((g) => groupLeaves(g))

/**
 * Resolve a page's display-name i18n key through ONE lookup shared by the
 * topbar title, the ask-from hint in the chat composer and the breadcrumb
 * . Order is
 * load-bearing: route meta first (detail pages name themselves), then the
 * menu-leaf mapping, then an honest app-name fallback for unknown paths.
 */
export function resolveMenuTitleKey(path: string, metaTitleKey?: unknown): string {
  if (typeof metaTitleKey === 'string' && metaTitleKey) return metaTitleKey
  const found = allMenuLeaves.find((m) => m.path === path)
  return found ? found.titleKey : 'common.appName'
}
