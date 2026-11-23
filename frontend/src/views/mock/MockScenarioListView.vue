<template>
  <div class="page-root mock-scenarios-page" data-test="mock-scenarios-page">
    <div :class="embedded ? 'mock-scenarios-embedded' : 'page-card'">
      <!-- R23 stage-2: the title shell row is gone — the page name rides the
 global topbar and the retired title-side "?" now lives on the topbar
 help dot (data-test=header-help-btn). IA posture kept:
 scenarios are their own addressable surface; screening (space +
 keyword) lives in the filter-bar row and the page-level write
 action moved to the leading slot of the same row (R23 law 5),
 carrying NO selector there (project rides the global header
 context). -->
      <!-- segment visibility (ownership iron rule 3): the list is always one
 project's segment and the page says so out loud -->
      <p v-if="!embedded" class="page-subtitle">
        {{ t('mockScenarios.pageSubtitle', { project: ctx.primaryName || '—' }) }}
      </p>

      <div
        v-if="projectId && (spaces.length || !loadingSpaces)"
        class="filter-bar"
        data-test="mock-scenarios-filter-bar"
      >
        <!-- iron rule 2: no header project means the entry is disabled and the
             tooltip points at the header instead of a post-submit generic
             error (page-level no-project empty block stays as the belt piece) -->
        <el-tooltip
          v-if="canWrite"
          :content="t('components.ownerEcho.pickInHeader')"
          placement="bottom"
          :disabled="!!projectId"
        >
          <span>
            <el-button
              type="primary"
              :icon="Plus"
              :disabled="!projectId"
              data-test="mock-scenarios-new"
              @click="openCreateDrawer"
            >
              {{ t('mockScenarios.newScenario') }}
            </el-button>
          </span>
        </el-tooltip>
        <el-select
          v-if="!embedded"
                    v-model="pickedSpace"
          :placeholder="t('mockScenarios.selectSpace')"
          style="width: 220px"
          data-test="mock-scenarios-space-select" filterable
          :disabled="!spaces.length"
          @change="syncSpaceToUrl()"
        >
          <el-option v-for="s in spaces" :key="s.id" :label="spaceLabel(s)" :value="s.id" />
        </el-select>
        <el-input
          v-model="keyword"
          :placeholder="t('mockScenarios.searchPh')"
          :prefix-icon="Search"
          clearable
          style="width: 220px"
          data-test="mock-scenarios-search"
        />
        <div class="filter-bar-actions" data-test="mock-scenarios-filter-actions">
          <el-button :icon="Refresh" :loading="loading" data-test="mock-scenarios-refresh" @click="loadScenarios">
            {{ t('mockScenarios.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-scenarios-no-project">
        <EmptyState :title="t('mockScenarios.noProject')" />
      </div>
      <div v-else-if="!loadingSpaces && !spaces.length" class="empty-block" data-test="mock-scenarios-no-space">
        <EmptyState :title="t('mockScenarios.noSpace')" :hint="t('mockScenarios.noSpaceHint')" />
      </div>

      <template v-else-if="spaceId">
        <TableSkeleton v-if="loading && !rows.length" :cols="6" data-test="mock-scenarios-skeleton" />
        <el-table border
          v-else
          :data="visibleRows"
          row-class-name="scenario-row-link"
          data-test="mock-scenarios-table"
          @sort-change="onSortChange"
          @row-click="openDetail"
        >
          <el-table-column :label="t('mockScenarios.colName')" min-width="200" prop="name" sortable="custom">
            <template #default="{ row }">
              <span class="name-text" data-test="mock-scenarios-row">{{ row.row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockScenarios.colLane')" width="120" prop="scope" sortable="custom">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ laneText(row.graph?.scope ?? '') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockScenarios.colStates')" width="110" prop="statesCount" sortable="custom">
            <template #default="{ row }">
              <span v-if="row.graph">{{ row.graph.states.length }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockScenarios.colTransitions')" width="110" prop="transitionsCount" sortable="custom">
            <template #default="{ row }">
              <span v-if="row.graph">{{ row.graph.transitions.length }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockScenarios.colHealth')" min-width="180">
            <template #default="{ row }">
              <el-tag
                v-if="row.graph && row.graph.dangling.length"
                size="small"
                type="warning"
                data-test="mock-scenarios-dangling"
              >
                {{ t('mockScenarios.danglingTag', { n: row.graph.dangling.length }) }}
              </el-tag>
              <span v-else-if="!row.graph" class="curl-error" data-test="mock-scenarios-unreadable">
                {{ t('mockScenarios.unreadable', { reason: row.reason ?? 'unknown' }) }}
              </span>
              <el-tag v-else size="small" type="success" data-test="mock-scenarios-healthy">
                {{ t('mockScenarios.healthyTag') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockScenarios.colUpdated')" width="170" prop="updatedAt" sortable="custom">
            <template #default="{ row }">{{ timeText(row.row.updatedAt) }}</template>
          </el-table-column>
          <template #empty>
            <span data-test="mock-scenarios-empty">{{ rows.length ? t('mockScenarios.noMatch') : t('mockScenarios.empty') }}</span>
          </template>
        </el-table>

        <!-- The window is bounded server-side (display-scale iron law); the pager
             stays hidden on an empty ledger so it never invites dead clicks. -->
        <div v-if="total > 0" class="pagination-bar" data-test="mock-scenarios-pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next, total"
            @current-change="loadScenarios"
          />
        </div>
        <p v-if="keyword.trim() && rows.length" class="search-note" data-test="mock-scenarios-search-note">
          {{ t('mockScenarios.searchNote', { shown: visibleRows.length, window: rows.length, total }) }}
        </p>

        <!-- Create drawer (moved off the docs page with the scenario surface).
             The form guards emptiness and pre-warns dangling edges — legality is
             decided once at the core save gate, whose plain-word refusal shows
             untouched; the page never claims validity itself. -->
        <el-drawer
          v-model="drawerVisible"
          :title="t('mockScenarios.drawerTitle')"
          size="520px"
          data-test="mock-scenarios-create-drawer"
        >
          <!-- ownership echo (iron rule 1): read-only, single-sourced from the
               header project context -->
          <ProjectOwnerEcho />
          <el-form label-position="top" class="scenario-form">
            <el-form-item :label="t('mockScenarios.fieldName')" required>
              <el-input
                v-model="form.name"
                maxlength="120"
                :placeholder="t('mockScenarios.namePh')"
                data-test="mock-scenarios-f-name"
              />
            </el-form-item>
            <el-form-item :label="t('mockScenarios.fieldStates')" required>
              <div class="state-rows" data-test="mock-scenarios-f-states">
                <div v-for="(st, i) in form.states" :key="i" class="state-row">
                  <el-input
                    :model-value="st"
                    :data-test="`mock-scenarios-f-state-${i}`"
                    :placeholder="t('mockScenarios.statePh')"
                    @update:model-value="(v: string) => (form.states[i] = v)"
                  />
                  <el-radio
                    :model-value="form.initialIndex"
                    :value="i"
                    data-test="mock-scenarios-f-initial"
                    @change="form.initialIndex = i"
                  ></el-radio>
                  <el-button link type="danger" data-test="mock-scenarios-f-remove-state" @click="removeState(i)">
                    {{ t('mockScenarios.remove') }}
                  </el-button>
                </div>
                <el-button link type="primary" data-test="mock-scenarios-f-add-state" @click="addState">
                  + {{ t('mockScenarios.fieldStates') }}
                </el-button>
              </div>
            </el-form-item>
            <el-form-item :label="t('mockScenarios.fieldLane')">
              <el-select v-model="form.scope" data-test="mock-scenarios-f-lane" style="width: 220px">
                <el-option :label="t('mockScenarios.laneSession')" value="session" />
                <el-option :label="t('mockScenarios.laneRole')" value="role" />
                <el-option :label="t('mockScenarios.laneGlobal')" value="global" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('mockScenarios.fieldTransitions')">
              <div class="edge-rows">
                <div v-for="(e, i) in form.transitions" :key="i" class="edge-row">
                  <el-select
                    :model-value="e.from"
                    :placeholder="t('mockScenarios.edgeFrom')"
                    data-test="mock-scenarios-f-edge-from" filterable
                    @update:model-value="(v: string) => (form.transitions[i].from = v)"
                  >
                    <el-option v-for="st in declaredStates" :key="`f${st}`" :label="st" :value="st" />
                  </el-select>
                  <span class="muted">→</span>
                  <el-input
                    :model-value="e.to"
                    :placeholder="t('mockScenarios.edgeTo')"
                    :data-test="`mock-scenarios-f-edge-to-${i}`"
                    @update:model-value="(v: string) => (form.transitions[i].to = v)"
                  />
                  <el-button link type="danger" data-test="mock-scenarios-f-remove-edge" @click="form.transitions.splice(i, 1)">
                    {{ t('mockScenarios.remove') }}
                  </el-button>
                </div>
                <el-button link type="primary" data-test="mock-scenarios-f-add-edge" @click="form.transitions.push({ from: '', to: '' })">
                  + {{ t('mockScenarios.addEdge') }}
                </el-button>
              </div>
            </el-form-item>
            <div v-if="drawerDangling.length" class="curl-error" data-test="mock-scenarios-dangling-warn">
              {{ t('mockScenarios.danglingWarn') }}：{{ drawerDangling.join(', ') }}
            </div>
            <div v-if="drawerError" class="curl-error" data-test="mock-scenarios-form-error">{{ drawerError }}</div>
          </el-form>
          <template #footer>
            <el-button type="primary" :loading="saving" data-test="mock-scenarios-save" @click="submitScenario">
              {{ t('mockScenarios.save') }}
            </el-button>
          </template>
        </el-drawer>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AG batch : the standalone scenario surface. One row per state machine in
 * a bounded server-side window (name order), with an instant name search over
 * the loaded window and a jump into the addressable detail page where the
 * graph — the page's only heavy asset — renders one per screen.
 *
 * Honest boundaries:
 * - the search filters the window the server just served (the paged door has
 * no keyword parameter); the note line states shown/window/total so the
 * filtered count never reads as the whole ledger;
 * - reading a declaration here only summarises it — legality stays the core
 * save gate's single point, whose plain-word refusal is shown untouched.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useMockSpaceUrlPick } from '@/composables/useMockSpaceUrlPick'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useProjectContextStore } from '@/stores/projectContext'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import {
  laneSpectrumLabel,
  readScenarioGraph,
  writeScenarioDefinition,
  type ScenarioEdge,
  type ScenarioGraph,
  type ScenarioReadFailure,
} from '@/utils/mockScenarioGraph'
import { createScenario, listSpaces, pageScenarios, type MockSpaceItem } from '@/api/mock'

const { t } = useI18n()
const router = useRouter()
// AN-4 embedded contract: the space workbench hosts this page as a tab
// and injects the picked space id (props default off — standalone mounting
// stays possible while retired paths still redirect).
const props = withDefaults(defineProps<{ embedded?: boolean; spaceId?: number | null }>(), { embedded: false, spaceId: null })
const embedded = computed(() => props.embedded)
const auth = useAuthStore()
const ctx = useProjectContextStore()
const { projectId } = useProjectPageContext()

const canWrite = computed(() => auth.hasPerm('perm:mock:write'))

const spaces = ref<MockSpaceItem[]>([])
const loadingSpaces = ref(false)
const pickedSpace = ref<number | null>(null)
const { pickFromQuery, syncSpaceToUrl } = useMockSpaceUrlPick(pickedSpace, projectId)
// : host-injected space id wins over the local pick (redirect-era fallback kept).
const spaceId = computed(() => props.spaceId ?? pickedSpace.value)

const rows = ref<Array<{ row: { id: number; name: string; updatedAt: string | null }; graph: ScenarioGraph | null; reason: ScenarioReadFailure | null }>>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// Boot-guarded skeleton: the table v-else would paint its empty state on the
// first frame when the flag starts false (measured flash 2040ms, 2026-11-04).
const loading = ref(true)
const keyword = ref('')
// Column prop -> core scenario SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload — columns outside the whitelist
// never emit sort params, no client-only fake sort). The counted facets order
// by what core counts from the declaration (unreadable rows honestly count 0).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  scope: 'scope',
  statesCount: 'states_count',
  transitionsCount: 'transitions_count',
  updatedAt: 'updated_at',
}
// Server-side sort state (same shape as the endpoints/inspector/tag pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in
 * core SQL); clearing a sort falls back to the historical name order; page
 * resets to 1 so paging windows stay consistent. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadScenarios()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadScenarios()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadScenarios()
}

/** Instant search over the loaded window (name substring, case-insensitive). */
const visibleRows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter((r) => r.row.name.toLowerCase().includes(k))
})

function spaceLabel(s: MockSpaceItem): string {
  const alias = s.alias ? `（${s.alias}）` : ''
  return `${s.name}${alias}`
}

/** Lane label in words; unknown spectrum values pass through raw (never blank). */
function laneText(scope: string): string {
  const key = laneSpectrumLabel(scope)
  return key ? t(key) : scope || '—'
}

function timeText(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

async function loadSpaces(): Promise<void> {
  if (!projectId.value) {
    spaces.value = []
    pickedSpace.value = null
    rows.value = []
    total.value = 0
    return
  }
  loadingSpaces.value = true
  try {
    spaces.value = await listSpaces(projectId.value)
    const fromQuery = pickFromQuery(spaces.value.map((s) => s.id))
    if (fromQuery != null) pickedSpace.value = fromQuery
    else if (!spaces.value.some((s) => s.id === pickedSpace.value)) {
      pickedSpace.value = spaces.value[0]?.id ?? null
    }
    void syncSpaceToUrl()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockScenarios.loadSpacesFailed')))
    spaces.value = []
  } finally {
    loadingSpaces.value = false
  }
}

/** One bounded window per request — the list never renders an unbounded set. */
async function loadScenarios(): Promise<void> {
  if (!spaceId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await pageScenarios(spaceId.value, page.value, pageSize.value, {
      sortBy: query.sortBy, sortDir: query.sortDir,
    })
    total.value = res.total
    rows.value = res.list.map((row) => {
      const read = readScenarioGraph(row.definitionJson)
      return {
        row: { id: row.id, name: row.name, updatedAt: row.updatedAt },
        graph: read.ok ? read.graph : null,
        reason: read.ok ? null : read.reason,
      }
    })
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockScenarios.loadFailed')))
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

watch(projectId, () => {
  pickedSpace.value = null
  void loadSpaces()
})
watch(spaceId, () => {
  page.value = 1
  void loadScenarios()
}, {
  // Workbench-tab hosting passes a constant spaceId prop — the scenario rows
  // must load on mount, not wait for a change that never comes.
  immediate: true,
})

/** A row opens the addressable detail page with the space context riding. */
function openDetail(raw: unknown): void {
  const r = raw as { row: { id: number } }
  void router.push({
    path: `/mock/scenarios/${r.row.id}`,
    query: { spaceId: String(spaceId.value ?? ''), projectId: String(projectId.value ?? '') },
  })
}

// ---- create drawer -----------------------------------------------------------
const drawerVisible = ref(false)
const saving = ref(false)
const drawerError = ref('')
const form = ref<{ name: string; states: string[]; initialIndex: number; scope: string; transitions: ScenarioEdge[] }>({
  name: '',
  states: [''],
  initialIndex: 0,
  scope: 'session',
  transitions: [],
})

const declaredStates = computed(() => form.value.states.map((s) => s.trim()).filter((s) => s.length > 0))
const drawerDangling = computed(() =>
  form.value.transitions
    .map((e) => ({ from: e.from.trim(), to: e.to.trim() }))
    .filter((e) => e.to && !declaredStates.value.includes(e.to))
    .map((e) => `${e.from || '?'}→${e.to}`),
)

function openCreateDrawer(): void {
  form.value = { name: '', states: [''], initialIndex: 0, scope: 'session', transitions: [] }
  drawerError.value = ''
  drawerVisible.value = true
}

function addState(): void {
  form.value.states.push('')
}

function removeState(i: number): void {
  form.value.states.splice(i, 1)
  if (form.value.initialIndex >= form.value.states.length) form.value.initialIndex = 0
}

async function submitScenario(): Promise<void> {
  if (!spaceId.value) return
  drawerError.value = ''
  const name = form.value.name.trim()
  const states = declaredStates.value
  if (!name || !states.length) {
    drawerError.value = t('mockScenarios.formIncomplete')
    return
  }
  saving.value = true
  try {
    const idx = Math.min(Math.max(0, form.value.initialIndex), states.length - 1)
    await createScenario(spaceId.value, {
      name,
      definitionJson: writeScenarioDefinition({
        states,
        initial: states[idx],
        scope: form.value.scope,
        transitions: form.value.transitions
          .map((e) => ({ from: e.from.trim(), to: e.to.trim() }))
          .filter((e) => e.from && e.to),
      }),
    })
    ElMessage.success(t('mockScenarios.saved'))
    drawerVisible.value = false
    await loadScenarios()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockScenarios.createFailed')))
  } finally {
    saving.value = false
  }
}

// mock-domain ledger: the scenario rows themselves belong to no
// dialog write kind (no scenario draft exists), so only the space options
// this page shows — the space picker loaded at mount — have to converge when
// a Mock-space draft lands. Silent re-read of the option list, no prompts.
import { useObjectSync } from '@/composables/useObjectSync'

useObjectSync({
  watch: ['MOCK_SPACE_WRITE'],
  onSync: () => void loadSpaces(),
})

void loadSpaces()
</script>

<style scoped>
.mock-scenarios-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.empty-block { padding: 24px 0; }
.name-text { font-size: 13px; font-weight: 600; }
.curl-error { color: var(--el-color-danger); font-size: 12px; }
.search-note { margin: 8px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.scenario-form .state-row,
.scenario-form .edge-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; width: 100%; }
</style>
