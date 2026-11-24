<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import AssertionBuilder from '@/components/AssertionBuilder.vue'
import OrchestrationBuilder from '@/views/apitest/OrchestrationBuilder.vue'
import AuthFormEditor from '@/views/apitest/AuthFormEditor.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import SpecImportWizard from '@/views/apitest/SpecImportWizard.vue'
import SpecSubscriptionPanel from '@/views/apitest/SpecSubscriptionPanel.vue'
import MockStubDirectWizard from '@/components/mock/MockStubDirectWizard.vue'
import RunSetLaunchDialog from '@/views/apitest/RunSetLaunchDialog.vue'
import RunSetDetailDrawer from '@/views/apitest/RunSetDetailDrawer.vue'
import RequestReorderPanel from '@/views/apitest/RequestReorderPanel.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { loopSummary, preconditionSummary } from '@/utils/orchestrationSummary'
import { HTTP_METHODS, TRANSPORT_PROTOCOLS } from '@/utils/assertionForm'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import { hasQueryScope, lensEmptyCopy, listProjectScope, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import { FLOW_DEMO_PROJECT_CODE, FLOW_DEMO_COLLECTION_NAME } from '@/config/journeys'
import { useWindowedList } from '@/composables/useWindowedList'
import {
  RAIL_DEFAULT_WIDTH,
  clampRailWidth,
  readRailWidth,
  writeRailWidth,
} from '@/composables/useRailWidthPrefs'
import {
  createCollection,
  createRequest,
  createStepAlias,
  deleteCollection,
  deleteRequest,
  getEffectivePreview,
  getRequest,
  listCollections,
  listRequests,
  listStepAliases,
  promoteFlow,
  promoteRequest,
  renameCollection,
  reorderRequests,
  updateRequest,
  previewRunSet,
  type ApiCollectionItem,
  type ApiRequestItem,
  type EffectivePreviewItem,
  type RunSetPreviewStep,
  type StepAliasItem,
} from '@/api/apiTest'

/**
 * API test workbench (02 §/api/v1/apitest collection+request contract mirror).
 *
 * information-architecture rebuild: the old three-nested-dropdown maze
 * (project -> collection -> request) plus a flat dump of 10+ toolbar buttons
 * is replaced by the Postman/Apifox mental model —
 * left rail = two visible lists (collection cards / request rows of the
 * selected collection), each with its own instant search;
 * right area = the selected request workspace (editor + save, request-level
 * actions live in its header);
 * project-level doors (spec import / subscription / mock bridge) fold into
 * one "data source" dropdown above the rail.
 * Project comes from the global header context (useProjectPageContext —
 * single source, no page-level selector). Value-domain authority stays
 * core-side (SSRF absolute-URL refusal / WS2 assertion-group validation);
 * the page only pre-gates for UX. Errors surface via bizErrorMsg (the
 * interceptor already toasts; catch blocks keep the fallback honest).
 */

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const { projectId, syncToContext, followTick } = useProjectPageContext()

/**
 * case 1: a deep-linked ?projectId that left the visible domain
 * (row deleted / not a member) must be named in plain words instead of
 * silently rendering a create-inducement empty state on top of it.
 * Visibility authority = the /users/me snapshot in the auth store — the
 * same visible-domain source the header prunes against (no second ruling
 * here; server-side enforcement stays ProjectAccessGuard). An unhydrated
 * snapshot (projects empty) never accuses a link: useProjectPageContext
 * already fires one loadMe refresh, and a still-missing id lands here.
 */
const projectInvalid = computed(
  () =>
    projectId.value !== null &&
    auth.projects.length > 0 &&
    !auth.projects.some((p) => p.projectId === projectId.value),
)

/** Doors that need a real project (create entry / data-source menu):
 *  closed both when nothing is picked and when the picked project is gone. */
const projectMissing = computed(() => projectId.value === null || projectInvalid.value)

const collections = ref<ApiCollectionItem[]>([])
const collectionId = ref<number | null>(null)
const requests = ref<ApiRequestItem[]>([])
const requestId = ref<number | null>(null)

/** Collection-pick filter: the rail list is a fully-loaded in-memory set,
 *  so substring filtering client-side is honest (unlike a paged server table). */
const collectionKeyword = ref('')

// Empty-state copy : rail collections follow the header lens;
// the resolver names the segment and the way out, legacy stays verbatim.
const railEmptyCopy = computed(() => lensEmptyCopy({
  translate: t,
  titleKey: 'apitest.lensEmptyTitle',
  emptyKey: collectionKeyword.value.trim() ? 'apitest.collectionNoMatch' : 'apitest.collectionEmpty',
  filtered: collectionKeyword.value.trim() !== '',
}))
const filteredCollections = computed(() => {
  const kw = collectionKeyword.value.trim().toLowerCase()
  if (!kw) return collections.value
  return collections.value.filter((c) => c.name.toLowerCase().includes(kw))
})

/** Request-pick filter (inventory badge deep link lands ?keyword=<path>):
 *  same in-memory honesty; the box stays editable so users can widen the cut
 *  again — SE rule 2: an active filter is always visible in its own control. */
const requestKeyword = ref(
  typeof route.query.keyword === 'string' ? route.query.keyword : '',
)
// Request-list empty title follows the header lens (MP4-d, same resolver).
const requestEmptyTitle = computed(() =>
  lensEmptyCopy({
    translate: t,
    titleKey: 'apitest.lensEmptyTitle',
    emptyKey: requestKeyword.value.trim() ? 'apitest.requestNoMatch' : 'apitest.requestEmpty',
    filtered: requestKeyword.value.trim() !== '',
  }).title,
)
const filteredRequests = computed(() => {
  const kw = requestKeyword.value.trim().toLowerCase()
  if (!kw) return requests.value
  return requests.value.filter(
    (r) =>
      r.name.toLowerCase().includes(kw) ||
      r.urlPath.toLowerCase().includes(kw),
  )
})

/**
 * Worst-scale containment (display-scale iron rule): a 200-request
 * collection paints only the scroll window plus top/bottom pads — the
 * scrollbar stays honest (total height ≈ full source) and the DOM never
 * holds all rows. Scroll state has ONE owner: the box element's
 * scrollTop (useWindowedList derives everything from it; no second
 * copy). The el-table virtualiser (useVirtualRows) does not apply here —
 * these rows are li, not table rows.
 */
const requestListEl = ref<HTMLElement | null>(null)
const reqWindow = useWindowedList(filteredRequests, {
  scroller: () => requestListEl.value,
  rowPitch: 36,
  buffer: 4,
})
const reqVisible = reqWindow.visible
const reqPadTop = reqWindow.padTop
const reqPadBottom = reqWindow.padBottom

function onRequestListScroll(): void {
  // pitch self-correction before the window math (theme/density drift guard)
  reqWindow.measurePitch()
  reqWindow.sync()
}

// Filter or source change: land the window back on the first match.
watch(filteredRequests, () => {
  reqWindow.resetWindow()
  reqWindow.sync()
})

/**
 * a: drag-reorder mode for the rail rows. The core door
 * (PUT /apitest/requests/{collectionId}/order) validates the FULL order
 * honestly — stale/foreign/duplicate ids or a partial list get a plain 400
 * — so the entry stays closed while a filter cut is active (submitting the
 * visible subset would trip that gate). The gate is the prevention; the
 * tooltip names the blocking reason instead of failing after the fact.
 */
const reorderOpen = ref(false)
const reorderSaving = ref(false)

const reorderBlockedReason = computed<string>(() => {
  if (collectionId.value === null) return t('apitest.reorder.noCollectionTip')
  if (requests.value.length === 0) return t('apitest.reorder.emptyListTip')
  if (requestKeyword.value.trim() !== '') return t('apitest.reorder.filterTip')
  return ''
})
const reorderDisabled = computed(() => reorderBlockedReason.value !== '')

// A gate that closes under the user's feet (filter typed / collection gone)
// drops the panel immediately — the panel always renders the full list.
watch(reorderDisabled, (blocked) => {
  if (blocked) reorderOpen.value = false
})
watch(collectionId, () => {
  reorderOpen.value = false
})

// Leaving reorder mode remounts the windowed ul — re-land it on the first match.
watch(reorderOpen, (open) => {
  if (!open) {
    reqWindow.resetWindow()
    reqWindow.sync()
  }
})

/** Persist the dragged full order, then re-fetch so rail/preview/execution
 *  all read the same server truth. Failure = interceptor toast with the
 *  core 400 pass-through; the panel stays open for retry (order kept local). */
async function saveRequestOrder(orderedRequestIds: number[]): Promise<void> {
  if (collectionId.value === null) return
  reorderSaving.value = true
  try {
    await reorderRequests(collectionId.value, orderedRequestIds)
    ElMessage.success(t('apitest.reorder.saved'))
    await loadRequests()
    reorderOpen.value = false
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  } finally {
    reorderSaving.value = false
  }
}

/**
 * b: the rail "flow" view — the same-source execution order
 * made visible without opening the launch dialog. Row truth comes from
 * previewRunSet (the exact contract the launcher and the runner read:
 * what you see here IS the dispatch order), and the orchestration badges
 * (precondition / loop) join the ALREADY loaded request rows by
 * requestId — zero extra queries per row. Inline chord markers
 * (producer-seq badges, forward-reference warnings) reuse the runSet dep
 * key family so one vocabulary serves both faces.
 * Worst-scale rule: 50–200 hops go through the shared windowed
 * virtualiser plus an instant search box, never a page-level full render.
 * The SVG overlay canvas stays phase 2 (overlay chords would be an
 * unbounded heavy asset — the inline marker shape is what this segment
 * ships honestly).
 */
/** deep-link params (?view=flow&projectCode=..&collectionName=..):
 * the quick-journey card / onboarding overlay land the pane on the factory
 * demo flow. Values resolve against the visible domain (auth.projects = /me
 * snapshot) — an unknown code keeps the honest current segment, never a fake
 * selection . No numeric ids in URLs = survives
 * reinstall id drift. */
const routeProjectCode = typeof route.query.projectCode === 'string' ? route.query.projectCode : ''
const routeCollectionName = typeof route.query.collectionName === 'string' ? route.query.collectionName : ''
const railView = ref<'browse' | 'flow'>(route.query.view === 'flow' ? 'flow' : 'browse')

/** Visible-domain resolution of the demo project code (single source for both
 *  the mount deep link and the empty-state door; null = not visible = honest
 *  refusal, never a force-selection). */
function demoProjectIdFor(code: string): number | null {
  return auth.projects.find((p) => p.projectCode === code)?.projectId ?? null
}

/** Open the seeded demo collection (by name — id drift survives) and switch
 *  the rail to the flow pane. Caller must have loaded collections for the
 *  current project. Returns false when the seed is absent (honest absence).
 *  Row loading stays watcher-driven (railView/collectionId watches pull the
 *  preview) — no manual second call (red line 6 posture: one path). */
async function landOnDemoCollection(name: string): Promise<boolean> {
  const flat = collections.value.flatMap((c) => [c, ...c.children])
  const match = flat.find((c) => c.name === name)
  if (!match) return false
  railView.value = 'flow'
  if (collectionId.value !== match.id) await selectCollection(match)
  return true
}

/** Flow-pane empty-state door: jump the workspace to the factory demo journey
 *  (login -> order -> poll -> paging), through the same page-context single
 *  source a header switch uses. Without a visible demo project the click
 *  answers in plain words instead of silently failing. */
async function openFlowDemoEntry(): Promise<void> {
  const pid = demoProjectIdFor(FLOW_DEMO_PROJECT_CODE)
  if (pid !== null && projectId.value !== pid) {
    projectId.value = pid
    syncToContext()
    await loadStepAliases()
    await loadCollections()
  }
  const landed = await landOnDemoCollection(FLOW_DEMO_COLLECTION_NAME)
  if (!landed) {
    ElMessage.info(t('apitest.flow.demoMissing'))
    return
  }
  void router.replace({
    query: { ...route.query, view: 'flow', projectCode: FLOW_DEMO_PROJECT_CODE, collectionName: FLOW_DEMO_COLLECTION_NAME },
  })
}
const flowSteps = ref<RunSetPreviewStep[]>([])
const flowLoading = ref(false)
const flowError = ref('')
const flowKeyword = ref('')
const flowListEl = ref<HTMLElement | null>(null)

/** requestId -> seq map so dependency badges name the producer's row number. */
const flowSeqById = computed(() => {
  const map = new Map<number, number>()
  flowSteps.value.forEach((s) => map.set(s.requestId, s.seqNo))
  return map
})

const filteredFlowSteps = computed(() => {
  const kw = flowKeyword.value.trim().toLowerCase()
  if (!kw) return flowSteps.value
  return flowSteps.value.filter((s) =>
    `${s.seqNo} ${s.name} ${s.method} ${s.urlPath}`.toLowerCase().includes(kw))
})

const flowWindow = useWindowedList(filteredFlowSteps, {
  scroller: () => flowListEl.value,
  rowPitch: 36,
  buffer: 4,
})
const flowVisible = flowWindow.visible
const flowPadTop = flowWindow.padTop
const flowPadBottom = flowWindow.padBottom

function onFlowListScroll(): void {
  flowWindow.measurePitch()
  flowWindow.sync()
}

watch(filteredFlowSteps, () => {
  flowWindow.resetWindow()
  flowWindow.sync()
})

async function loadFlowView(): Promise<void> {
  if (collectionId.value === null) {
    flowSteps.value = []
    flowError.value = ''
    return
  }
  flowLoading.value = true
  flowError.value = ''
  try {
    flowSteps.value = await previewRunSet(collectionId.value)
  } catch (e) {
    // Honest absence: the failure sentence lands in the pane, never a blank.
    flowSteps.value = []
    flowError.value = bizErrorMsg(e, t('apitest.flow.loadFail'))
  } finally {
    flowLoading.value = false
  }
}

// Switching into flow mode (or into a new collection while in it) pulls the
// preview once; leaving browse clears the stale cut so re-entry re-filters
// from the full set.
watch(railView, (view) => {
  reorderOpen.value = false
  if (view === 'flow') void loadFlowView()
  else flowKeyword.value = ''
})
watch(collectionId, () => {
  if (railView.value === 'flow') void loadFlowView()
})
// A reorder save changed the server order — the flow pane must not keep
// showing the pre-drag rows.
watch(requests, () => {
  if (railView.value === 'flow') void loadFlowView()
})

/**
 * whole-flow promotion entry. The gate reads the pane's own
 * row source (flowSteps — the same list the door will freeze), so what the
 * user sees disabled is exactly what would be refused; prevention lives
 * here, the core 400/409 stays the honest backstop with verbatim copy.
 */
const flowPromoting = ref(false)

const flowPromoteBlockedReason = computed<string>(() => {
  if (collectionId.value === null) return t('apitest.flowPromote.blockedNoCollection')
  if (flowSteps.value.length === 0) return t('apitest.flowPromote.blockedEmpty')
  return ''
})
const flowPromoteDisabled = computed(
  () => flowPromoteBlockedReason.value !== '' || flowPromoting.value,
)

/**
 * Promote the whole rail flow into ONE regression case through the
 * collection-level door (zero request body — order, mapping, fail-closed
 * refusals and badges are all single-sourced in core). Confirm first, then
 * one door call for the entire flow; success names the case number and
 * jumps to the case detail, and the rail reloads so every member badge
 * points at the same case.
 */
async function promoteWholeFlow(): Promise<void> {
  if (collectionId.value === null || flowPromoteDisabled.value) return
  try {
    await ElMessageBox.confirm(
      t('apitest.flowPromote.confirm', {
        name: currentCollectionName.value,
        count: flowSteps.value.length,
      }),
      t('apitest.flowPromote.entry'),
      { type: 'info', confirmButtonText: t('apitest.save'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  flowPromoting.value = true
  try {
    const result = await promoteFlow(collectionId.value)
    await loadRequests()
    ElMessage.success(t('apitest.flowPromote.ok', {
      caseNo: result.caseNo,
      count: result.promotedRequestCount,
    }))
    void router.push(`/cases/${result.caseId}`)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.flowPromote.fail')))
  } finally {
    flowPromoting.value = false
  }
}

/**
 * requestId -> orchestration badges, derived from the rows already in
 * memory (the summary kernel parses the jsonb texts once per collection
 * load — a per-render JSON.parse in the template would be a second
 * needless pass). Malformed rows simply carry no badge here; the editor
 * is the single place that pinpoints a bad jsonb.
 */
const flowSummaries = computed(() => {
  const map = new Map<number, {
    pre: ReturnType<typeof preconditionSummary>
    loop: ReturnType<typeof loopSummary>
  }>()
  requests.value.forEach((r) => {
    map.set(r.id, { pre: preconditionSummary(r.preconditionJson), loop: loopSummary(r.loopJson) })
  })
  return map
})

function summaryOf(id: number): { pre: ReturnType<typeof preconditionSummary>; loop: ReturnType<typeof loopSummary> } {
  return flowSummaries.value.get(id) ?? { pre: null, loop: null }
}

/** Flow rows are clickable straight into the workspace editor (browse parity). */
function openFlowRequest(id: number): void {
  const row = requests.value.find((r) => r.id === id)
  if (row) openRequest(row)
}

/** Edit form model (save = full PUT; create = POST then back-fill id). */
const formName = ref('')
const formMethod = ref('GET')
/** transport discriminator (http | ws) — vocabulary single-source
 * from utils/assertionForm (mirrors core ApiWsContract); legacy rows load as http. */
const formProtocol = ref('http')
const formUrlPath = ref('')
const formBody = ref('')
const formAssertions = ref('[]')
const builderRef = ref<InstanceType<typeof AssertionBuilder> | null>(null)
/** orchestration rows (precondition/loop jsonb texts, {} = off). */
const formPrecondition = ref('{}')
const formLoop = ref('{}')
const orchestrationRef = ref<InstanceType<typeof OrchestrationBuilder> | null>(null)
/** C-2b: auth row jsonb text ({} = no auth; editor mirrors core AuthFormContract). */
const formAuth = ref('{}')
const authRef = ref<InstanceType<typeof AuthFormEditor> | null>(null)
const saving = ref(false)

/**
 * final-effective preview. Collapsible section in the request
 * workspace that shows what the dispatch envelope will actually carry after
 * collection defaults merge in. The verdict is fetched from the core resolver
 * through the admin mirror (GET .../effective) — the page never re-implements
 * the merge (red line 6: one interpretation, zero second copy). An unsaved
 * request has no server-side verdict yet, so the toggle stays disabled with a
 * plain-language hint instead of a local guess. Failure posture: the last
 * verdict stays visible while the interceptor toasts the error (a stale
 * preview is honest about which request it was fetched for; the section
 * re-fetches on reopen and resets across request switches).
 */
const effectiveOpen = ref(false)
const effectiveLoading = ref(false)
const effectiveData = ref<EffectivePreviewItem | null>(null)

async function toggleEffective(): Promise<void> {
  if (requestId.value === null) return
  effectiveOpen.value = !effectiveOpen.value
  if (effectiveOpen.value && effectiveData.value === null) {
    await refreshEffective()
  }
}

async function refreshEffective(): Promise<void> {
  if (requestId.value === null) return
  effectiveLoading.value = true
  try {
    effectiveData.value = await getEffectivePreview(requestId.value)
  } catch {
    // Interceptor already toasts; keep the section open with an empty hint.
    effectiveData.value = null
  } finally {
    effectiveLoading.value = false
  }
}

/** Auth verdict → chip label (four-valued source, single vocabulary). */
function effectiveAuthLabel(source: string): string {
  switch (source) {
    case 'request': return t('apitest.effectiveAuthRequest')
    case 'collection': return t('apitest.effectiveAuthCollection')
    case 'none-explicit': return t('apitest.effectiveAuthNoneExplicit')
    default: return t('apitest.effectiveAuthNone')
  }
}

/** Alias entry-1 consumer: project alias table (saveAs pool key -> plain name; H1 isolation). */
const stepAliases = ref<StepAliasItem[]>([])

/** Interface-doc import wizard (P3; in-page dialog, refresh tree after write). */
const importWizardOpen = ref(false)

/** Contract subscription panel (in-page drawer; add/edit/enable/disable of rows). */
const subscriptionOpen = ref(false)

/** Contract-to-mock stub wizard: in-page three-step dialog over the admin
 *  bridge (preview drafts -> select -> import). The entry needs the same
 *  permission key the two bridge doors enforce. */
const stubDirectOpen = ref(false)

async function loadStepAliases(): Promise<void> {
  if (projectId.value === null) {
    stepAliases.value = []
    return
  }
  try {
    stepAliases.value = await listStepAliases(projectId.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.loadFail')))
  }
}

/** Alias entry-1: plain name + this row's saveAs pool key = path alias;
 *  the three-conflict 400 / duplicate 409 plain-word answers pass through core. */
async function onRegisterAlias(payload: { aliasName: string; target: string }): Promise<void> {
  if (projectId.value === null) return
  try {
    await createStepAlias({ projectId: projectId.value, aliasName: payload.aliasName, kind: 'path', target: payload.target })
    ElMessage.success(t('apitest.aliasSaved'))
    await loadStepAliases()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.aliasSaveFail')))
  }
}

async function loadCollections(): Promise<void> {
  const scope = listProjectScope(projectId.value)
  if (scope === null) {
    collections.value = []
    collectionId.value = null
    requests.value = []
    requestId.value = null
    return
  }
  try {
    collections.value = await listCollections(scope, programIdParam())
    // Keep the picked collection when it still exists in the new segment
    // (header switch to another project invalidates it — reset instead).
    if (collectionId.value !== null && !collections.value.some((c) => c.id === collectionId.value)) {
      collectionId.value = null
      requests.value = []
      requestId.value = null
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.loadFail')))
  }
}

// Header program lens flip = re-scope the collection rail .
// Under a lens the bridge answers a multi-root forest of the program's
// visible projects; detach drops back to the plain project segment. The rail
// is a non-paged door, so "reload" is the whole page-1 posture here.
watchProgramLens(() => void loadCollections(), () => hasQueryScope(projectId.value))

async function loadRequests(): Promise<void> {
  if (collectionId.value === null) {
    requests.value = []
    requestId.value = null
    return
  }
  try {
    requests.value = await listRequests(collectionId.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.loadFail')))
  }
}

/** Whether the open request carries the "contract changed, review pending" badge. */
const currentContractStale = computed(
  () => requests.value.find((r) => r.id === requestId.value)?.contractStale ?? false,
)

/** Mock twin badge : the diff hit also has contract-derived stubs. */
const currentMockStale = computed(
  () => requests.value.find((r) => r.id === requestId.value)?.mockStale ?? false,
)

/** Promotion badge : the open request already became a regression case. */
const currentPromotedCaseId = computed(
  () => requests.value.find((r) => r.id === requestId.value)?.promotedCaseId ?? null,
)

const promoting = ref(false)

/**
 * Promote the open sandbox request into a regression case ( real chain;
 * all mapping/duplication judgement is core-side — the 409 repeat answer
 * naming the existing case number passes through as plain language).
 * Confirmation first (asset-creating write), badge refresh after.
 */
async function promoteCurrentRequest(): Promise<void> {
  if (requestId.value === null) return
  try {
    await ElMessageBox.confirm(
      t('apitest.promoteConfirm', { name: formName.value }),
      t('apitest.promoteToCase'),
      { type: 'info', confirmButtonText: t('apitest.save'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  promoting.value = true
  try {
    const result = await promoteRequest(requestId.value)
    ElMessage.success(t('apitest.promoteOk', { caseNo: result.caseNo }))
    await loadRequests()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.promoteFail')))
  } finally {
    promoting.value = false
  }
}

/** Badge click destination: the promoted case detail (double-direction lineage). */
function openPromotedCase(): void {
  if (currentPromotedCaseId.value === null) return
  void router.push(`/cases/${currentPromotedCaseId.value}`)
}

function openRequest(req: ApiRequestItem): void {
  requestId.value = req.id
  formName.value = req.name
  formMethod.value = req.method
  formProtocol.value = req.protocol || 'http'
  formUrlPath.value = req.urlPath
  formBody.value = req.bodyText ?? ''
  formAssertions.value = req.assertionsJson ?? '[]'
  formPrecondition.value = req.preconditionJson || '{}'
  formLoop.value = req.loopJson || '{}'
  formAuth.value = req.authJson || '{}'
  // Switching the open request invalidates the verdict: the next expand refetches.
  effectiveData.value = null
}

function newRequest(): void {
  requestId.value = null
  formName.value = ''
  formMethod.value = 'GET'
  formProtocol.value = 'http'
  formUrlPath.value = '/'
  formBody.value = ''
  formAssertions.value = '[]'
  formPrecondition.value = '{}'
  formLoop.value = '{}'
  formAuth.value = '{}'
  effectiveData.value = null
  effectiveOpen.value = false
}

/** Rail card pick: switch the collection segment and open its first request. */
async function selectCollection(c: ApiCollectionItem): Promise<void> {
  if (collectionId.value === c.id) return
  collectionId.value = c.id
  await loadRequests()
  if (requests.value.length > 0) openRequest(requests.value[0])
  else newRequest()
}

/** stage 2: draggable left rail (280~480, ruling allows it).
 * Personal preference lives in localStorage per user (same bucket discipline
 * as the global sidebar) — never a server-side K8 key. */
const railWidth = ref(readRailWidth(auth.user?.userId))

function railResizeStart(e: MouseEvent): void {
  if (e.button !== 0) return
  const startX = e.clientX
  const startW = railWidth.value
  document.body.style.userSelect = 'none'
  document.body.classList.add('is-resizing-ap-rail')
  const onMove = (ev: MouseEvent) => {
    railWidth.value = clampRailWidth(startW + (ev.clientX - startX))
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.classList.remove('is-resizing-ap-rail')
    writeRailWidth(auth.user?.userId, railWidth.value)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

/** Double-click the grabber: back to the default width and persist it. */
function railResizeReset(): void {
  railWidth.value = RAIL_DEFAULT_WIDTH
  writeRailWidth(auth.user?.userId, RAIL_DEFAULT_WIDTH)
}

// Re-read the preference when the signed-in user changes mid-session so two
// accounts on one browser never share a rail width.
watch(() => auth.user?.userId, (v) => {
  railWidth.value = readRailWidth(v)
})

/** Wizard write done: refresh the rail tree and the current collection's list
 *  (import products land visibly at once — tell-then-show loop closed). */
async function onImported(): Promise<void> {
  await loadCollections()
  if (collectionId.value === null && collections.value.length > 0) {
    collectionId.value = collections.value[0].id
  }
  if (collectionId.value !== null) {
    await loadRequests()
  }
}

/** New collection: always-on prompt dialog; without a header project the
 *  entry itself is disabled + pointed at the header (ownership-echo rule 2),
 *  so no post-hoc generic "pick a project first" error can happen. */
async function addCollection(): Promise<void> {
  let value = ''
  try {
    const r = await ElMessageBox.prompt(t('apitest.newCollectionPrompt'), t('apitest.newCollection'), {
      inputValue: t('apitest.defaultCollectionName'),
      confirmButtonText: t('apitest.save'),
      cancelButtonText: t('common.cancel'),
    })
    value = r.value.trim()
  } catch {
    // cancel/close = user abandoned, return silently
    return
  }
  if (projectId.value === null) {
    ElMessage.warning(t('apitest.pickProjectFirst'))
    return
  }
  try {
    const created = await createCollection({
      projectId: projectId.value, parentId: 0, name: value, tags: '',
    })
    ElMessage.success(t('apitest.createOk'))
    await loadCollections()
    await selectCollectionAfterCreate(created.id)
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  }
}

/** Land the freshly created collection as the picked segment with an empty editor. */
async function selectCollectionAfterCreate(id: number): Promise<void> {
  collectionId.value = id
  await loadRequests()
  newRequest()
}

/** Collection rename (row-level action, current picked collection). */
async function renameCurrentCollection(): Promise<void> {
  const cur = collections.value.find((c) => c.id === collectionId.value)
  if (!cur) return
  try {
    const { value } = await ElMessageBox.prompt(t('apitest.renameCollectionPrompt'), t('apitest.renameCollection'), {
      inputValue: cur.name,
      confirmButtonText: t('apitest.save'),
      cancelButtonText: t('common.cancel'),
    })
    await renameCollection(cur.id, value.trim(), cur.tags ?? '')
    ElMessage.success(t('apitest.saveOk'))
    await loadCollections()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  }
}

/**
 * collection-defaults editor: the dialog only edits the stored inheritance
 * rows (default headers + default auth block). The merge itself is single-sourced
 * in core (ApiEffectiveDefaultsResolver) — zero second computation on the page.
 */
const defaultsDialogOpen = ref(false)
const defaultsHeaderRows = ref<{ name: string; value: string }[]>([])
const defaultsAuth = ref('{}')
// iron rule 3 baseline: the server values at the moment the defaults
// dialog opened — a landed draft swaps untouched fields to the new value and
// keeps whatever the user already typed (silently overwriting input is the
// data-loss half of the same rule).
const defaultsBaseline = ref<{ headersJson: string; authJson: string } | null>(null)
const defaultsAuthRef = ref<InstanceType<typeof AuthFormEditor> | null>(null)
const defaultsSaving = ref(false)

/** jsonb text -> editable rows; malformed or absent payloads land on the honest
 *  empty list so the editor shows exactly what the server would inherit. */
function parseCollectionHeaderRows(raw?: string | null): { name: string; value: string }[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map((r) => ({
      name: String((r as { name?: unknown }).name ?? ''),
      value: String((r as { value?: unknown }).value ?? ''),
    }))
  } catch {
    return []
  }
}

function openCollectionDefaults(): void {
  const cur = collections.value.find((c) => c.id === collectionId.value)
  if (!cur) return
  defaultsHeaderRows.value = parseCollectionHeaderRows(cur.headersJson)
  defaultsAuth.value = cur.authJson || '{}'
  defaultsBaseline.value = { headersJson: cur.headersJson ?? '', authJson: cur.authJson || '{}' }
  defaultsDialogOpen.value = true
}

async function saveCollectionDefaults(): Promise<void> {
  const cur = collections.value.find((c) => c.id === collectionId.value)
  if (!cur) return
  const authError = defaultsAuthRef.value?.validateAll() ?? null
  if (authError) {
    ElMessage.error(authError)
    return
  }
  const rows = defaultsHeaderRows.value
    .map((r) => ({ name: r.name.trim(), value: r.value }))
    .filter((r) => r.name !== '')
  defaultsSaving.value = true
  try {
    // Name/tags round-tripped verbatim — the four-field PUT wipes omitted tags
    // otherwise (core save gate contract).
    await renameCollection(cur.id, cur.name, cur.tags ?? '', {
      headersJson: JSON.stringify(rows),
      authJson: defaultsAuth.value,
    })
    ElMessage.success(t('apitest.saveOk'))
    defaultsDialogOpen.value = false
    await loadCollections()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  } finally {
    defaultsSaving.value = false
  }
}

async function removeCurrentCollection(): Promise<void> {
  const cur = collections.value.find((c) => c.id === collectionId.value)
  if (!cur) return
  try {
    await ElMessageBox.confirm(t('apitest.deleteCollectionConfirm'), t('apitest.delete'), { type: 'warning' })
    await deleteCollection(cur.id)
    ElMessage.success(t('apitest.deleteOk'))
    collectionId.value = null
    requests.value = []
    newRequest()
    await loadCollections()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  }
}

/** Save request: assertion form pre-gate (validateAll) pinpoints the bad row,
 *  then the core save gate passes plain-language refusals through. */
async function saveRequest(): Promise<void> {
  if (collectionId.value === null) {
    ElMessage.warning(t('apitest.pickCollectionFirst'))
    return
  }
  const firstError = builderRef.value?.validateAll() ?? null
  if (firstError) {
    ElMessage.error(firstError)
    return
  }
  const orchestrationError = orchestrationRef.value?.validateAll() ?? null
  if (orchestrationError) {
    ElMessage.error(orchestrationError)
    return
  }
  const authError = authRef.value?.validateAll() ?? null
  if (authError) {
    ElMessage.error(authError)
    return
  }
  const payload = {
    collectionId: collectionId.value,
    name: formName.value.trim(),
    method: formMethod.value,
    protocol: formProtocol.value,
    urlPath: formUrlPath.value,
    headersJson: '[]',
    bodyText: formBody.value,
    authJson: formAuth.value,
    assertionsJson: formAssertions.value,
    preconditionJson: formPrecondition.value,
    loopJson: formLoop.value,
  }
  saving.value = true
  try {
    const saved = requestId.value === null
      ? await createRequest(payload)
      : await updateRequest(requestId.value, payload)
    ElMessage.success(t('apitest.saveOk'))
    requestId.value = saved.id
    // Saving changed the merge inputs — refresh now when the section is open,
    // otherwise the next expand refetches lazily.
    effectiveData.value = null
    if (effectiveOpen.value) await refreshEffective()
    await loadRequests()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.saveFail')))
  } finally {
    saving.value = false
  }
}

async function removeRequest(): Promise<void> {
  if (requestId.value === null) return
  try {
    await ElMessageBox.confirm(t('apitest.deleteRequestConfirm'), t('apitest.delete'), { type: 'warning' })
    await deleteRequest(requestId.value)
    ElMessage.success(t('apitest.deleteOk'))
    newRequest()
    await loadRequests()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(bizErrorMsg(e, t('apitest.deleteFail')))
  }
}

/** Reindent the JSON request body with 2 spaces; an invalid payload is left
 *  untouched with a plain-word toast (no silent rewrite of user text). */
function formatRequestBody(): void {
  const raw = formBody.value.trim()
  if (raw === '') return
  try {
    formBody.value = JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    ElMessage.warning(t('apitest.formatBodyFail'))
  }
}

/** Perf entry: jump to the workbench wizard carrying an asset-reference query
 *  (URL assembly/validation all belong to the wizard + core single point). */
function saveAsPerfScenario(): void {
  if (requestId.value === null || collectionId.value === null || projectId.value === null) return
  void router.push({
    path: '/perf',
    query: {
      upgradeProject: String(projectId.value),
      upgradeCollection: String(collectionId.value),
      upgradeRequest: String(requestId.value),
    },
  })
}

/** §12a item 1 send side: jump to the mock docs page carrying the project pre-filter. */
function jumpToMockDocs(): void {
  if (projectId.value === null) return
  void router.push({ path: '/mock/docs', query: { projectId: projectId.value, source: 'openapi' } })
}

/** Collection-run : launch dialog + result drawer. */
const runSetDialogOpen = ref(false)
const runSetDrawerOpen = ref(false)

const currentCollectionName = computed(() =>
  collections.value.find((c) => c.id === collectionId.value)?.name ?? '')

/** Interface-count badge source: the list payload stays honest — when the
 * server-side count column is absent, the rail shows the loaded size of the
 * picked segment only and renders nothing for the others (never a fake 0). */
function collectionCount(c: ApiCollectionItem): number | null {
  if (typeof c.requestCount === 'number') return c.requestCount
  return c.id === collectionId.value ? requests.value.length : null
}

/** Header context switched the project (or pruned it): reload the whole segment. */
watch(projectId, async (v, old) => {
  if (v === old) return
  collectionId.value = v === null ? null : collectionId.value
  await loadStepAliases()
  await loadCollections()
  if (collectionId.value !== null) await loadRequests()
})

// followTick bump = the header drove a project switch (deep-link/prune):
// reload so the rail follows the context instead of the stale segment.
watch(followTick, async () => {
  await loadStepAliases()
  await loadCollections()
  if (collectionId.value !== null) await loadRequests()
})

onMounted(async () => {
  // the factory demo deep link resolves BEFORE the segment loads.
  // projectCode -> visible-domain project (auth.projects = /me snapshot: a
  // code the user cannot see is ignored, never force-selected — zero privilege
  // widening), landed through the page-context single source (syncToContext).
  const demoPid = routeProjectCode ? demoProjectIdFor(routeProjectCode) : null
  if (demoPid !== null && projectId.value !== demoPid) {
    projectId.value = demoPid
    syncToContext()
  }
  await loadStepAliases()
  await loadCollections()
  // collectionName lands the rail on the seeded demo collection (flat = the
  // two-level tree the rail already renders). Missing seed = honest absence:
  // auto-first stays, the empty-state pointer offers the same door again.
  if (routeCollectionName) {
    const landed = await landOnDemoCollection(routeCollectionName)
    if (landed) return
  }
  if (collections.value.length > 0 && collectionId.value === null) {
    await selectCollection(collections.value[0])
  }
  // origin jump: a promoted case links back to its exact source request —
  // the auto-first rule would open the wrong one, so a query requestId wins
  // and relocates the collection selection to the request's owning collection.
  const qr = Number(Array.isArray(route.query.requestId) ? route.query.requestId[0] : route.query.requestId)
  if (Number.isInteger(qr) && qr > 0) {
    try {
      const req = await getRequest(qr)
      const flat = collections.value.flatMap((c) => [c, ...c.children])
      if (flat.some((c) => c.id === req.collectionId)) {
        collectionId.value = req.collectionId
        await loadRequests()
      }
      openRequest(req)
    } catch {
      // unreadable/foreign request: the interceptor already toasts — keep the
      // auto-first state instead of a broken deep link.
    }
  }
})

// (apitest segment): a landed collection-defaults draft converges this
// page silently — the rail row re-reads, and an open defaults dialog swaps
// only the fields the user has not touched (iron rule 3, zero prompts, no
// page jump). A landed contract version marks imported requests stale
// server-side (markImportedRequestsStale), so the stale/mock-stale/promotion
// badges converge on the same signal through a silent request re-read.
useObjectSync({
  watch: ['APITEST_WRITE'],
  onSync: () => void syncLandedCollectionDefaults(),
})
useObjectSync({
  watch: ['APISPEC_WRITE'],
  onSync: () => void loadRequests(),
})

async function syncLandedCollectionDefaults(): Promise<void> {
  await loadCollections()
  if (!defaultsDialogOpen.value || !defaultsBaseline.value) return
  const cur = collections.value.find((c) => c.id === collectionId.value)
  if (!cur) return
  const baseline = defaultsBaseline.value
  if (JSON.stringify(defaultsHeaderRows.value) === JSON.stringify(parseCollectionHeaderRows(baseline.headersJson))) {
    defaultsHeaderRows.value = parseCollectionHeaderRows(cur.headersJson)
    baseline.headersJson = cur.headersJson ?? ''
  }
  if (defaultsAuth.value === baseline.authJson) {
    defaultsAuth.value = cur.authJson || '{}'
    baseline.authJson = defaultsAuth.value
  }
}
</script>

<template>
  <!-- tp-page-root-exempt: root carries its own 16px padding (live-measured
       1440x900), content edge gap present without the theme .page-root. -->
  <div class="api-test-page" data-test="api-test-page">
    <div class="tp-page-head">
      <!-- stage-3: the page-name h2 retired (topbar is the single
 source); only the operating-rule description line stays. -->
      <p class="tp-page-desc">{{ t('apitest.pageDesc') }}</p>
    </div>

    <div class="ap-workbench">
      <!-- ================= left rail: two visible lists ================= -->
      <aside class="ap-rail" data-test="apitest-rail" :style="{ flex: `0 0 ${railWidth}px`, width: `${railWidth}px` }">
        <!-- stage 2: right-edge grabber (6px hit area, hover highlight —
 same shape as the global sidebar resizer; dblclick resets). -->
        <div class="ap-rail-resizer" data-test="apitest-rail-resizer"
          @mousedown="railResizeStart" @dblclick="railResizeReset"></div>
        <div class="ap-rail-top">
          <ProjectOwnerEcho />
          <!-- Project-level doors live here, not on a mixed toolbar .
 case 1: an invalid deep-linked project closes the doors the
 same way an empty header pick does (honest absence, no writes
 into a project that does not exist). -->
          <el-dropdown trigger="click" data-test="apitest-datasource-menu">
            <el-button size="small" :disabled="projectMissing">
              {{ t('apitest.dataSource.menu') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item data-test="apitest-import-from-spec-btn" :disabled="projectMissing" @click="importWizardOpen = true">
                  {{ t('apitest.specImport.entry') }}
                </el-dropdown-item>
                <el-dropdown-item data-test="apitest-subscription-btn" :disabled="projectMissing" @click="subscriptionOpen = true">
                  {{ t('apitest.subscription.entry') }}
                </el-dropdown-item>
                <!-- §12a item 1 send side: contract page -> mock docs with the
                     project pre-filter; door hidden without the perm:mock:read mirror. -->
                <el-dropdown-item
                  v-if="!projectMissing && auth.hasPerm('perm:mock:read')"
                  data-test="apitest-view-in-mock"
                  @click="jumpToMockDocs"
                >
                  {{ t('apitest.viewInMock') }}
                </el-dropdown-item>
                <!-- Contract-to-mock entry: same permission key as both bridge doors. -->
                <el-dropdown-item
                  v-if="!projectMissing && auth.hasPerm('perm:mock:write')"
                  data-test="apitest-stub-direct-btn"
                  @click="stubDirectOpen = true"
                >
                  {{ t('apitest.stubImport.entry') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <div class="ap-rail-section" data-test="apitest-collection-list">
          <div class="ap-rail-head">
            <el-input
              v-model="collectionKeyword"
              data-test="apitest-collection-filter"
              :placeholder="t('apitest.collectionFilterPh')"
              :prefix-icon="Search"
              clearable
              size="small"
              class="ap-rail-search"
            />
            <!-- Create entry gated by the header context (rule 2: no post-hoc
 generic error). case 1: an invalid deep-linked project
 closes the door with the same plain-word guidance instead of
 inviting a collection inside a project that does not exist. -->
            <el-tooltip
              :disabled="!projectMissing"
              :content="t(projectInvalid ? 'apitest.projectInvalidHint' : 'apitest.pickProjectInHeader')"
              placement="top"
            >
              <span>
                <el-button
                  data-test="apitest-new-collection-btn"
                  size="small"
                  type="primary"
                  plain
                  :disabled="projectMissing"
                  @click="addCollection"
                >
                  {{ t('apitest.newCollection') }}
                </el-button>
              </span>
            </el-tooltip>
          </div>

          <p class="ap-concept-hint" data-test="apitest-collection-concept">{{ t('apitest.collectionConcept') }}</p>

          <div v-if="projectInvalid" class="ap-rail-empty" data-test="apitest-project-invalid">
            <EmptyState
              :title="t('apitest.projectInvalid')"
              :hint="t('apitest.projectInvalidHint')"
            />
          </div>
          <div v-else-if="projectId === null" class="ap-rail-empty">
            <EmptyState :title="t('apitest.pickProjectInHeader')" />
          </div>
          <div v-else-if="filteredCollections.length === 0" class="ap-rail-empty">
            <EmptyState
              :title="railEmptyCopy.title"
              :hint="railEmptyCopy.hint"
            />
          </div>
          <ul v-else class="ap-card-list">
            <li
              v-for="c in filteredCollections"
              :key="c.id"
              class="ap-collection-card"
              :class="{ 'is-active': c.id === collectionId }"
              :data-test="`apitest-collection-card-${c.id}`"
              @click="selectCollection(c)"
            >
              <span class="ap-card-name">{{ c.name }}</span>
              <el-tag
                v-if="collectionCount(c) !== null"
                size="small"
                type="info"
                data-test="apitest-collection-count"
              >
                {{ t('apitest.requestCountBadge', { count: collectionCount(c)! }) }}
              </el-tag>
              <!-- Row actions folded into one more-dropdown (≤3-button discipline). -->
              <el-dropdown
                trigger="click"
                data-test="apitest-collection-more"
                @command="(cmd: string) => { if (cmd === 'rename') renameCurrentCollection(); else if (cmd === 'defaults') openCollectionDefaults(); else if (cmd === 'delete') removeCurrentCollection() }"
              >
                <button class="ap-more-btn" :aria-label="t('apitest.collectionMore')" @click.stop>
                  <el-icon><ArrowDown /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">{{ t('apitest.renameCollection') }}</el-dropdown-item>
                    <el-dropdown-item command="defaults" data-test="apitest-collection-defaults-item">{{ t('apitest.collectionDefaults') }}</el-dropdown-item>
                    <el-dropdown-item command="delete">{{ t('apitest.delete') }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </li>
          </ul>
        </div>

        <!-- Collection-run entry stays on the card area (ruling: dialog only
 changes host position — dialog/drawer components untouched). -->
        <div v-if="collectionId !== null" class="ap-run-bar">
          <el-button
            type="primary"
            size="small"
            data-test="apitest-run-collection-btn"
            @click="runSetDialogOpen = true"
          >
            {{ t('apitest.runSet.entry') }}
          </el-button>
          <el-button size="small" data-test="apitest-runset-history-btn" @click="runSetDrawerOpen = true">
            {{ t('apitest.runSet.historyEntry') }}
          </el-button>
        </div>

        <div class="ap-rail-section" data-test="apitest-request-list">
          <!-- b: rail two-state switch (browse rows / flow view).
 The flow pane is a mode of THIS section — collection cards and
 the workspace stay untouched, no second page, no tab bar. -->
          <div class="ap-rail-view-switch" data-test="apitest-flow-toggle" role="group" :aria-label="t('apitest.flow.toggleAria')">
            <el-button
              size="small"
              :plain="railView === 'browse'"
              :type="railView === 'browse' ? 'primary' : 'default'"
              data-test="apitest-flow-browse-btn"
              @click="railView = 'browse'"
            >
              {{ t('apitest.flow.viewBrowse') }}
            </el-button>
            <el-button
              size="small"
              :plain="railView === 'flow'"
              :type="railView === 'flow' ? 'primary' : 'default'"
              data-test="apitest-flow-flow-btn"
              @click="railView = 'flow'"
            >
              {{ t('apitest.flow.viewFlow') }}
            </el-button>
          </div>
          <div class="ap-rail-head">
            <el-input
              v-model="requestKeyword"
              v-show="railView === 'browse'"
              data-test="apitest-request-filter"
              :placeholder="t('apitest.requestFilterPh')"
              :prefix-icon="Search"
              clearable
              size="small"
              class="ap-rail-search"
            />
            <el-button
              size="small"
              plain
              data-test="apitest-new-request-btn"
              :disabled="collectionId === null"
              @click="newRequest"
            >
              {{ t('apitest.newRequest') }}
            </el-button>
            <el-tooltip :content="reorderBlockedReason || t('apitest.reorder.tip')" placement="top">
              <span>
                <el-button
                  size="small"
                  plain
                  data-test="apitest-reorder-btn"
                  :disabled="reorderDisabled"
                  @click="reorderOpen = true"
                >
                  {{ t('apitest.reorder.entry') }}
                </el-button>
              </span>
            </el-tooltip>
          </div>
          <div v-if="collectionId === null" class="ap-rail-empty">
            <EmptyState :title="t('apitest.pickCollectionHint')" />
          </div>
          <RequestReorderPanel
            v-else-if="reorderOpen"
            :requests="requests"
            :saving="reorderSaving"
            @save="saveRequestOrder"
            @close="reorderOpen = false"
          />
          <!-- b flow pane: mutually exclusive with the browse
 rows (one v-if chain, one row source — no second state copy). -->
          <div v-else-if="railView === 'flow'" class="ap-flow-pane" data-test="apitest-flow-pane">
            <div class="ap-flow-head">
              <p class="ap-flow-caption">{{ t('apitest.flow.caption') }}</p>
              <!-- whole-flow promotion entry — one door call
 freezes the exact rail order into ONE regression case.
 Disabled with a plain-language reason while no collection
 is picked / the flow is empty (prevention posture shared
 with the reorder entry). -->
              <el-tooltip :content="flowPromoteBlockedReason || t('apitest.flowPromote.tip')" placement="top">
                <span>
                  <el-button
                    size="small"
                    plain
                    :loading="flowPromoting"
                    data-test="apitest-flow-promote-btn"
                    :disabled="flowPromoteDisabled"
                    @click="promoteWholeFlow"
                  >
                    {{ t('apitest.flowPromote.entry') }}
                  </el-button>
                </span>
              </el-tooltip>
            </div>
            <el-input
              v-model="flowKeyword"
              size="small"
              clearable
              class="ap-rail-search"
              data-test="apitest-flow-search"
              :placeholder="t('apitest.flow.searchPh')"
              :prefix-icon="Search"
            />
            <el-alert
              v-if="flowError"
              class="ap-flow-error"
              type="error"
              :closable="false"
              show-icon
              data-test="apitest-flow-error"
              :title="flowError"
            />
            <!-- empty-state pointer to the factory demo journey
 page-context single source; an invisible seed answers in
 plain words (demoMissing), never a silent failure. The
 wrapper carries the door anchor — EmptyState keeps its own
 data-test="empty-state" (global contracts) and its action
 button (data-test="empty-action") stays the click target. -->
            <div v-else-if="!flowLoading && flowSteps.length === 0" class="ap-rail-empty" data-test="apitest-flow-empty">
              <div data-test="apitest-flow-demo-btn">
                <EmptyState
                  :title="t('apitest.flow.empty')"
                  :hint="t('apitest.flow.emptyHint')"
                  :action-text="t('apitest.flow.demoAction')"
                  @action="openFlowDemoEntry"
                />
              </div>
            </div>
            <ul
              v-else
              ref="flowListEl"
              v-loading="flowLoading"
              class="ap-row-list"
              data-test="apitest-flow-rows"
              @scroll.passive="onFlowListScroll"
            >
              <li v-if="flowPadTop > 0" class="ap-window-pad" :style="{ height: `${flowPadTop}px` }" aria-hidden="true" />
              <li
                v-for="step in flowVisible"
                :key="step.requestId"
                class="ap-request-row ap-flow-row"
                data-windowed-row
                :class="{ 'is-active': step.requestId === requestId }"
                :data-test="`apitest-flow-row-${step.seqNo}`"
                @click="openFlowRequest(step.requestId)"
              >
                <span class="ap-flow-seq mono">#{{ step.seqNo }}</span>
                <el-tag size="small" :type="step.method === 'GET' ? 'success' : step.method === 'DELETE' ? 'danger' : 'warning'" class="ap-method-tag">
                  {{ step.method }}
                </el-tag>
                <span class="ap-row-name">{{ step.name }}</span>
                <span v-if="summaryOf(step.requestId).pre" class="ap-flow-chips">
                  <el-tag size="small" type="info" data-test="apitest-flow-pre-tag">
                    {{ t(summaryOf(step.requestId).pre!.key, summaryOf(step.requestId).pre!.params) }}
                  </el-tag>
                </span>
                <span v-if="summaryOf(step.requestId).loop" class="ap-flow-chips">
                  <el-tag size="small" type="info" data-test="apitest-flow-loop-tag">
                    {{ t(summaryOf(step.requestId).loop!.key, summaryOf(step.requestId).loop!.params) }}
                  </el-tag>
                  <!-- until mode has no room for the gap inside its sentence,
                       so the delay renders as its own chip; count mode folds
                       the gap into the wording (loopCountDelay) — never both. -->
                  <el-tag v-if="summaryOf(step.requestId).loop!.key === 'apitest.flow.loopUntil' && summaryOf(step.requestId).loop!.params.delay" size="small" type="info" effect="plain">
                    {{ t('apitest.flow.delayTip', { delay: summaryOf(step.requestId).loop!.params.delay }) }}
                  </el-tag>
                </span>
                <span v-if="step.deps.length > 0" class="ap-flow-chips">
                  <el-tag
                    v-for="dep in step.deps"
                    :key="`${step.requestId}-${dep.key}-${dep.producerRequestId}`"
                    size="small"
                    :type="dep.producerEarlier ? 'info' : 'warning'"
                    data-test="apitest-flow-dep-tag"
                  >
                    {{ dep.producerEarlier
                      ? t('apitest.runSet.depFrom', { key: dep.key, seq: flowSeqById.get(dep.producerRequestId) ?? '?' })
                      : t('apitest.runSet.depForward', { key: dep.key, seq: flowSeqById.get(dep.producerRequestId) ?? '?' }) }}
                  </el-tag>
                </span>
              </li>
              <li v-if="!flowLoading && flowKeyword.trim() && filteredFlowSteps.length === 0 && flowSteps.length > 0"
                  class="ap-rail-empty" data-test="apitest-flow-no-match">
                <EmptyState :title="t('apitest.flow.noMatch')" />
              </li>
              <li v-if="flowPadBottom > 0" class="ap-window-pad" :style="{ height: `${flowPadBottom}px` }" aria-hidden="true" />
            </ul>
          </div>
          <div v-else-if="railView === 'browse' && filteredRequests.length === 0" class="ap-rail-empty">
            <EmptyState
              :title="requestEmptyTitle"
            />
          </div>
          <ul
            v-else
            ref="requestListEl"
            class="ap-row-list"
            data-test="apitest-request-rows"
            @scroll.passive="onRequestListScroll"
          >
            <li v-if="reqPadTop > 0" class="ap-window-pad" :style="{ height: `${reqPadTop}px` }" aria-hidden="true" />
            <li
              v-for="r in reqVisible"
              :key="r.id"
              class="ap-request-row"
              data-windowed-row
              :class="{ 'is-active': r.id === requestId }"
              :data-test="`apitest-request-row-${r.id}`"
              @click="openRequest(r)"
            >
              <el-tag size="small" :type="r.method === 'GET' ? 'success' : r.method === 'DELETE' ? 'danger' : 'warning'" class="ap-method-tag">
                {{ r.method }}
              </el-tag>
              <span class="ap-row-name">{{ r.name }}</span>
              <span class="ap-row-path mono">{{ r.urlPath }}</span>
              <el-tag v-if="r.contractStale" size="small" type="warning" data-test="apitest-stale-tag">
                {{ t('apitest.contractStaleTag') }}
              </el-tag>
              <el-tag v-if="r.mockStale" size="small" type="warning" data-test="apitest-mock-stale-tag">
                {{ t('apitest.mockStaleTag') }}
              </el-tag>
            </li>
            <li v-if="reqPadBottom > 0" class="ap-window-pad" :style="{ height: `${reqPadBottom}px` }" aria-hidden="true" />
          </ul>
        </div>
      </aside>

      <!-- ================= right: request workspace ================= -->
      <section class="ap-workspace">
        <div class="ap-workspace-head" data-test="apitest-workspace-head">
          <h3 class="ap-workspace-title">{{ formName || t('apitest.newRequest') }}</h3>
          <!-- Request-level actions live in the workspace header .
 stage 4: the two cross-domain exits (promote-to-case, save-as-perf)
 fold into a "More" dropdown so the header keeps exactly one primary
 affordance row — action-area consolidation per the page-responsibility
 rubric; the data-test hooks move onto the dropdown items unchanged. -->
          <div class="ap-workspace-actions">
            <el-dropdown trigger="click" data-test="apitest-more-actions-menu">
              <el-button size="small" data-test="apitest-more-actions-btn">
                {{ t('apitest.moreActions') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <!-- promotion (real chain): request -> regression case via core
 single point; the badge below is the reverse door back to the case. -->
                  <el-dropdown-item
                    data-test="apitest-promote-btn"
                    :disabled="requestId === null || promoting"
                    @click="promoteCurrentRequest"
                  >
                    {{ t('apitest.promoteToCase') }}
                  </el-dropdown-item>
                  <!-- Save-as-perf-scenario (T275h chain): jumps to the workbench with an
                       asset-reference query; env picking stays in the wizard. -->
                  <el-dropdown-item
                    data-test="apitest-save-as-perf-btn"
                    :disabled="requestId === null || collectionId === null || projectId === null"
                    @click="saveAsPerfScenario"
                  >
                    {{ t('apitest.saveAsPerf') }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              data-test="apitest-delete-request-btn"
              size="small"
              type="danger"
              plain
              :disabled="requestId === null"
              @click="removeRequest"
            >
              {{ t('apitest.delete') }}
            </el-button>
            <el-tag
              v-if="currentPromotedCaseId !== null"
              class="ap-promoted-badge"
              type="success"
              size="small"
              data-test="apitest-promoted-badge"
              style="cursor: pointer"
              @click="openPromotedCase"
            >
              {{ t('apitest.promotedTag') }}
            </el-tag>
          </div>
        </div>

        <el-alert
          v-if="currentContractStale"
          class="ap-stale-alert"
          type="warning"
          :closable="false"
          show-icon
          data-test="apitest-stale-alert"
          :title="t('apitest.contractStaleHint')"
        />
        <el-alert
          v-if="currentMockStale"
          class="ap-stale-alert"
          type="warning"
          :closable="false"
          show-icon
          data-test="apitest-mock-stale-alert"
          :title="t('apitest.mockStaleHint')"
        />

        <div class="ap-form">
          <div class="ap-field-row">
            <label for="apitest-request-name-input">{{ t('apitest.requestName') }}</label>
            <el-input v-model="formName" id="apitest-request-name-input" data-test="apitest-request-name" :placeholder="t('apitest.requestNamePh')" />
            <label for="apitest-method-input">{{ t('apitest.method') }}</label>
            <el-select v-model="formMethod" id="apitest-method-input" data-test="apitest-method-select" style="width: 120px">
              <el-option v-for="m in HTTP_METHODS" :key="m" :value="m" :label="m" />
            </el-select>
            <label for="apitest-protocol-select">{{ t('apitest.protocol') }}</label>
            <el-select v-model="formProtocol" id="apitest-protocol-select" data-test="apitest-protocol-select" style="width: 120px">
              <el-option
                v-for="p in TRANSPORT_PROTOCOLS"
                :key="p"
                :value="p"
                :label="p === 'ws' ? t('apitest.protocolWs') : t('apitest.protocolHttp')"
              />
            </el-select>
          </div>
          <div class="ap-field-row">
            <label for="apitest-url-path-input">{{ t('apitest.urlPath') }}</label>
            <el-input v-model="formUrlPath" id="apitest-url-path-input" data-test="apitest-url-path" :placeholder="formProtocol === 'ws' ? t('apitest.urlPathPhWs') : t('apitest.urlPathPh')" />
          </div>
          <div class="ap-field-col">
            <div class="ap-body-head">
              <label for="apitest-body-input">{{ t('apitest.requestBody') }}</label>
              <!-- stage 4: one-click JSON reindent (2-space). Invalid payloads
 keep the text untouched and say so in plain words. -->
              <el-button
                size="small"
                text
                data-test="apitest-format-body-btn"
                :disabled="formBody.trim() === ''"
                @click="formatRequestBody"
              >
                {{ t('apitest.formatBody') }}
              </el-button>
            </div>
            <el-input v-model="formBody" id="apitest-body-input" data-test="apitest-request-body" type="textarea" :rows="4" />
          </div>
          <div class="ap-field-col">
            <!-- assertions = multi-control composite (rows of selectors), no single labelable
                 target: caption is a styled span, inner controls carry their own labels -->
            <span class="ap-caption">{{ t('apitest.assertions') }}</span>
            <AssertionBuilder
              ref="builderRef"
              v-model="formAssertions"
              :protocol="formProtocol"
              :project-id="projectId"
              :aliases="stepAliases"
              @register-alias="onRegisterAlias"
            />
          </div>
          <div class="ap-field-col">
            <!-- orchestration = two independent row groups (precondition/loop),
                 each inner control carries its own label -->
            <span class="ap-caption">{{ t('apitest.orchestration') }}</span>
            <OrchestrationBuilder
              ref="orchestrationRef"
              v-model:precondition="formPrecondition"
              v-model:loop="formLoop"
            />
          </div>
          <div class="ap-field-col">
            <!-- auth = composite section (type select + per-type slots); inner
                 controls carry their own labels, caption is a styled span -->
            <span class="ap-caption">{{ t('apitest.authSection') }}</span>
            <AuthFormEditor
              ref="authRef"
              v-model:auth="formAuth"
              :project-id="projectId"
            />
          </div>
          <!-- final-effective preview. One collapsible section;
 the merge verdict is core-resolver output consumed read-only —
 the page renders chips, never recomputes. Unsaved requests keep
 the toggle disabled with a plain hint (no local guess, no fake). -->
          <div class="ap-field-col" data-test="apitest-effective-section">
            <div class="ap-effective-head">
              <el-button
                size="small"
                text
                data-test="apitest-effective-toggle"
                :disabled="requestId === null"
                @click="toggleEffective"
              >
                {{ t('apitest.effectiveToggle') }}
              </el-button>
              <span v-if="requestId === null" class="ap-effective-hint" data-test="apitest-effective-needs-save">
                {{ t('apitest.effectiveNeedsSave') }}
              </span>
              <el-button
                v-if="effectiveOpen && requestId !== null"
                size="small"
                text
                data-test="apitest-effective-refresh"
                :loading="effectiveLoading"
                @click="refreshEffective"
              >
                {{ t('common.refresh') }}
              </el-button>
            </div>
            <p v-if="effectiveOpen" class="ap-effective-hint">{{ t('apitest.effectivePreviewHint') }}</p>
            <template v-if="effectiveOpen && effectiveData !== null">
              <div class="ap-effective-block">
                <span class="ap-caption">{{ t('apitest.effectiveHeaders') }}</span>
                <div class="ap-effective-rows">
                  <div
                    v-for="row in effectiveData.headers"
                    :key="row.name"
                    class="ap-effective-row"
                    data-test="apitest-effective-header-row"
                  >
                    <code class="ap-effective-name">{{ row.name }}</code>
                    <span class="ap-effective-value">{{ row.value }}</span>
                    <el-tag
                      size="small"
                      :type="row.source === 'collection' ? 'warning' : 'info'"
                      effect="plain"
                      data-test="apitest-effective-source-chip"
                    >
                      {{ row.source === 'collection' ? t('apitest.effectiveSourceCollection') : t('apitest.effectiveSourceRequest') }}
                    </el-tag>
                  </div>
                </div>
              </div>
              <div class="ap-effective-block" data-test="apitest-effective-auth">
                <span class="ap-caption">{{ t('apitest.effectiveAuth') }}</span>
                <el-tag
                  size="small"
                  :type="effectiveData.authSource === 'collection' ? 'warning' : 'info'"
                  effect="plain"
                  data-test="apitest-effective-auth-chip"
                >
                  {{ effectiveAuthLabel(effectiveData.authSource) }}
                </el-tag>
                <span v-if="effectiveData.authSource === 'collection'" class="ap-effective-hint">
                  {{ t('apitest.effectiveFromCollection', { name: effectiveData.collectionName }) }}
                </span>
              </div>
            </template>
          </div>
          <div class="ap-actions">
            <el-button type="primary" data-test="apitest-save-btn" :loading="saving" @click="saveRequest">
              {{ t('apitest.save') }}
            </el-button>
          </div>
        </div>
      </section>
    </div>

    <!-- collection defaults (inherited headers + auth block); merge maths
 stay single-sourced in core, this dialog only edits stored rows. -->
    <el-dialog
      v-model="defaultsDialogOpen"
      :title="t('apitest.collectionDefaults')"
      width="720px"
      data-test="apitest-defaults-dialog"
    >
      <div class="ap-defaults-body">
        <div class="ap-defaults-group" data-test="apitest-defaults-headers">
          <span class="ap-caption">{{ t('apitest.collectionDefaultHeaders') }}</span>
          <p class="ap-defaults-hint">{{ t('apitest.collectionDefaultHeadersHint') }}</p>
          <div class="ap-defaults-rows">
            <div
              v-for="(row, idx) in defaultsHeaderRows"
              :key="idx"
              class="ap-defaults-row"
              :data-test="`apitest-defaults-header-row-${idx}`"
            >
              <el-input
                v-model="row.name"
                size="small"
                :placeholder="t('apitest.headerNamePlaceholder')"
                :aria-label="t('apitest.headerNamePlaceholder')"
              />
              <el-input
                v-model="row.value"
                size="small"
                :placeholder="t('apitest.headerValuePlaceholder')"
                :aria-label="t('apitest.headerValuePlaceholder')"
              />
              <el-button
                size="small"
                text
                :aria-label="t('apitest.delete')"
                :data-test="`apitest-defaults-header-del-${idx}`"
                @click="defaultsHeaderRows.splice(idx, 1)"
              >
                {{ t('apitest.delete') }}
              </el-button>
            </div>
          </div>
          <el-button
            size="small"
            data-test="apitest-defaults-header-add"
            @click="defaultsHeaderRows.push({ name: '', value: '' })"
          >
            {{ t('apitest.addHeaderRow') }}
          </el-button>
        </div>
        <div class="ap-defaults-group" data-test="apitest-defaults-auth">
          <span class="ap-caption">{{ t('apitest.collectionDefaultAuth') }}</span>
          <p class="ap-defaults-hint">{{ t('apitest.collectionDefaultAuthHint') }}</p>
          <AuthFormEditor
            ref="defaultsAuthRef"
            v-model:auth="defaultsAuth"
            :project-id="projectId"
          />
        </div>
      </div>
      <template #footer>
        <el-button data-test="apitest-defaults-cancel" @click="defaultsDialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="defaultsSaving"
          data-test="apitest-defaults-save-btn"
          @click="saveCollectionDefaults"
        >
          {{ t('apitest.save') }}
        </el-button>
      </template>
    </el-dialog>

    <SpecImportWizard
      v-model="importWizardOpen"
      :project-id="projectId"
      @imported="onImported"
    />

    <MockStubDirectWizard v-model="stubDirectOpen" :project-id="projectId" />

    <RunSetLaunchDialog
      v-model="runSetDialogOpen"
      :project-id="projectId"
      :collection-id="collectionId"
      :collection-name="currentCollectionName"
      @launched="runSetDrawerOpen = true"
    />

    <RunSetDetailDrawer v-model="runSetDrawerOpen" :collection-id="collectionId" :project-id="projectId" />

    <el-drawer v-model="subscriptionOpen" :title="t('apitest.subscription.title')" size="640px" data-test="apitest-subscription-drawer">
      <SpecSubscriptionPanel v-if="subscriptionOpen" :project-id="projectId" @closed="subscriptionOpen = false" />
    </el-drawer>
  </div>
</template>

<style scoped>
.api-test-page {
  padding: 16px;
}

.tp-page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.tp-page-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* Workbench shell: rail width is a per-user preference (default 360,
   drag 280~480 via the right-edge grabber); workspace stays fluid. */
.ap-workbench {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.ap-rail {
  position: relative;
  flex: 0 0 360px;
  width: 360px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

/* Rail grabber: 6px hit zone, 2px highlight line on hover (mirrors the
   global sidebar resizer so the affordance reads the same everywhere). */
.ap-rail-resizer {
  position: absolute;
  top: 0;
  right: -4px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 10;
}

.ap-rail-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  left: 2px;
  width: 2px;
  height: 100%;
  border-radius: 2px;
  background: transparent;
  transition: background-color 0.15s ease;
}

.ap-rail-resizer:hover::after {
  background: var(--tp-primary);
}

body.is-resizing-ap-rail .ap-rail {
  transition: none !important;
}

.ap-rail-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.ap-rail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ap-rail-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ap-rail-search {
  flex: 1;
  min-width: 0;
}

.ap-concept-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.ap-rail-empty {
  padding: 4px 0;
}

.ap-card-list,
.ap-row-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow-y: auto;
}

/* Virtual-window spacer : keeps the
 * scrollbar height honest for the full source; never interactive. */
.ap-window-pad {
  flex: 0 0 auto;
  pointer-events: none;
}

/* b: rail browse/flow segmented switch — one row of small
 * buttons above the request section, active side reads as primary. */
.ap-rail-view-switch {
  display: flex;
  gap: 6px;
  padding: 0 2px 6px;
}

/* caption + flow-promotion entry share one wrapped head row
 inside the narrow rail — the entry never forces a horizontal scroll. */
.ap-flow-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px;
}

.ap-flow-head .ap-flow-caption {
  flex: 1 1 100%;
}

.ap-flow-head > :last-child {
  margin-left: auto;
}

.ap-flow-caption {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--tp-text-secondary);
}

.ap-flow-seq {
  flex: 0 0 auto;
  min-width: 26px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}

/* Flow rows carry badges (precondition/loop/deps) — let them wrap instead
 * of truncating; the row stays one click target for the editor. */
.ap-flow-row {
  flex-wrap: wrap;
}

.ap-flow-chips {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.ap-collection-card,
.ap-request-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  min-height: 32px;
}

.ap-collection-card:hover,
.ap-request-row:hover {
  background: var(--el-fill-color-light);
}

.ap-collection-card.is-active,
.ap-request-row.is-active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.ap-card-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.ap-more-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--el-text-color-secondary);
  display: inline-flex;
  align-items: center;
  padding: 2px 4px;
  border-radius: 4px;
}

.ap-more-btn:hover {
  background: var(--el-fill-color);
}

.ap-run-bar {
  display: flex;
  gap: 8px;
}

.ap-request-row {
  font-size: 13px;
}

.ap-method-tag {
  flex: 0 0 auto;
}

.ap-row-name {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ap-row-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ap-workspace {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.ap-workspace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.ap-workspace-title {
  margin: 0;
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ap-workspace-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ap-stale-alert {
  margin-bottom: 0;
}

.ap-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ap-field-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ap-field-row label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.ap-field-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* Body caption row: label left, format affordance right . */
.ap-body-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ap-body-head .el-button {
  padding: 0 2px;
  height: auto;
}

.ap-field-col label,
.ap-field-col .ap-caption {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.ap-actions {
  display: flex;
}

/* collection-defaults dialog (4px grid; caption reuses the field-col
 typography so the two editor faces stay one visual language). */
.ap-defaults-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ap-defaults-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* vision fix : the auth group gets a real separator — adjacent hint
 text alone read as one merged block in the 1920 dark/light audit. */
.ap-defaults-group + .ap-defaults-group {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 16px;
}

.ap-defaults-group .ap-caption {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.ap-defaults-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* vision fix : scale-safe containment — long header lists scroll
 inside the dialog instead of overflowing it (display-scale iron rule). */
.ap-defaults-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 240px;
  overflow-y: auto;
  padding-right: 4px;
}

.ap-defaults-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
  gap: 8px;
  align-items: center;
}

.ap-defaults-row .el-button.is-text {
  min-width: 32px;
  color: var(--el-text-color-secondary);
}

.ap-defaults-row .el-button.is-text:hover {
  color: var(--el-color-danger);
}

/* effective-preview section — spacing on the 4px grid, colors
 from theme vars only (dark/light both inherit; no per-page palette fork). */
.ap-effective-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ap-effective-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ap-effective-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

/* Vision follow-up : the block caption must read one level
 above the hint text — weight is the differentiator that survives both
 themes without fragmenting the shared .ap-caption typography. */
.ap-effective-block .ap-caption {
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.ap-effective-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 240px;
  overflow-y: auto;
  padding-right: 4px;
}

.ap-effective-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.ap-effective-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-primary);
  font-weight: 600;
  font-family: var(--el-font-family-mono, monospace);
}

.ap-effective-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}
</style>
