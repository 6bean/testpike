<template>
  <div class="page-root login-state-page" data-test="login-state-page">
    <div class="page-card">
      <!-- FB same-band merge: no row-level batch bar and two screening
           conditions (keyword + status, under the <=4 threshold), so the
           page-level variant action (create) keeps riding the
           .filter-bar-actions trailing slot ahead of the view-state action
           (column settings); the emptied page-header row stays removed. The
           query button is inline at the end of the condition flow (R21
           second revision), never pushed into the trailing slot. The create
           entry keeps the ownership gate: disabled without a header project
           context, tooltip points at the header (R30 single switch). -->
      <div class="filter-bar" data-test="login-state-filter-bar">
        <el-input v-model="keyword" class="filter-keyword" data-test="login-state-keyword-input"
          :placeholder="t('sessionState.keywordPlaceholder')" clearable
          @keyup.enter="applyFilters" @clear="applyFilters" />
        <el-select v-model="status" class="filter-status" data-test="login-state-status-select"
          :placeholder="t('sessionState.statusAll')" clearable @change="applyFilters">
          <el-option :label="t('sessionState.statusEnabled')" :value="1" />
          <el-option :label="t('sessionState.statusDisabled')" :value="0" />
        </el-select>
        <el-button type="primary" data-test="login-state-query-btn" @click="applyFilters">
          {{ t('sessionState.query') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="login-state-create-btn" @click="openCreate">
                {{ t('sessionState.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <ColumnSettings
            :columns="pane.settingsRows.value"
            :density="pane.state.density"
            :disabled="pane.settingsDisabled.value"
            :density-disabled="pane.densityDisabled.value"
            :disabled-tooltip="pane.disabledReason.value ? t(pane.disabledReason.value) : ''"
            @toggle="pane.toggle"
            @move="pane.move"
            @density="pane.setDensity"
            @reset="pane.reset"
            @pin="pane.toggleFixed"
          />
        </div>
      </div>

      <!-- two honest empty states: no visible project vs. none selected -->
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="login-state-empty-no-project">
        <EmptyState :title="t('sessionState.noProject')" :hint="t('sessionState.noProjectHint')" :action-text="t('sessionState.goProjects')" @action="router.push('/projects')" />
      </div>

      <div v-else-if="!loading && !projectId" class="empty-box" data-test="login-state-empty-select-project">
        <EmptyState :title="t('sessionState.selectProject')" :hint="t('sessionState.selectProjectHint')" />
      </div>

      <template v-else>
        <TableSkeleton v-if="loading" :cols="6" data-test="login-state-skeleton" />
        <DataTablePage
          v-else
          :data="rows"
          data-test="login-state-table"
          class="data-table"
          row-key="id"
          :size="pane.state.density"
          :column-config="pane.cfg"
          :default-sort="activeSort"
          @sort-change="onSortChange"
        >
          <el-table-column
            v-for="col in pane.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :sortable="SERVER_SORTABLE.has(col.key) ? 'custom' : false"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :min-width="LIGHT_FLEX_MIN[col.key] ?? 120"
            :show-overflow-tooltip="col.key === 'stateName' || col.key === 'scopeOrigins'"
            :data-test="`login-state-col-${col.key}`"
          >
            <template #default="{ row }">
              <span v-if="col.key === 'light'" :data-test="`login-state-light-${row.id}`">
                <el-tag :type="lightTag(row)" size="small" data-test="login-state-light">{{ lightLabel(row) }}</el-tag>
              </span>
              <span v-else-if="col.key === 'scopeOrigins'">{{ scopeText(row) }}</span>
              <span v-else-if="col.key === 'extraAuthRefs'" :data-test="`login-state-extra-count-${row.id}`">{{ extraRefsText(row) }}</span>
              <span v-else>{{ cellText(row, col.key) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('sessionState.actions')" width="460" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                :type="row.status === 0 ? 'success' : 'warning'"
                plain
                data-test="login-state-toggle-btn"
                @click="doToggle(row)"
              >
                {{ row.status === 0 ? t('sessionState.enable') : t('sessionState.disable') }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :disabled="row.status === 0"
                data-test="login-state-probe-btn"
                @click="openProbe(row)"
              >
                {{ t('sessionState.probe') }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                data-test="login-state-edit-btn"
                @click="openEdit(row)"
              >
                {{ t('sessionState.edit') }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                data-test="login-state-history-btn"
                @click="openHistory(row)"
              >
                {{ t('sessionState.history') }}
              </el-button>
              <el-button size="small" type="danger" plain data-test="login-state-delete-btn" @click="doDelete(row)">
                {{ t('sessionState.delete') }}
              </el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState
              data-test="login-state-empty-create"
              :title="t('common.emptyNoData')"
              :hint="t('sessionState.emptyCreateHint')"
              :action-text="t('sessionState.create')"
              @action="openCreate"
            />
          </template>
        </DataTablePage>
        <div class="pagination-bar">
          <el-pagination v-if="total > 0" v-model:current-page="page" :page-size="PAGE_SIZE"
            :total="total" layout="total, prev, pager, next" data-test="login-state-pager"
            @current-change="load" />
        </div>
      </template>

      <!-- "test now": session replay against one origin, tail-visible trail -->
      <el-dialog
        draggable
        v-model="probeVisible"
        :title="t('sessionState.probeTitle', { name: probeRow?.stateName || '' })"
        class="tp-dialog--lg"
        append-to-body
        data-test="login-state-probe-dialog"
      >
        <p class="probe-hint" data-test="login-state-probe-hint">{{ t('sessionState.probeHint') }}</p>
        <div class="probe-row">
          <el-input
            v-model="probeUrl"
            class="probe-url-input"
            :placeholder="t('sessionState.probeUrlPlaceholder')"
            data-test="login-state-probe-url"
            clearable
          />
          <el-button type="primary" :loading="probeRunning" data-test="login-state-probe-run" @click="runProbe">
            {{ t('sessionState.probeRun') }}
          </el-button>
        </div>
        <!-- request shape (11-19 user command): same method/body grammar as
             the vault test door. The body is transient — never stored, and
             the trail echoes the template source with {{CRED:key}}
             placeholders intact. -->
        <div class="probe-row probe-method-row">
          <label class="probe-field-label" for="login-state-probe-method">{{ t('sessionState.probeMethod') }}</label>
          <el-select id="login-state-probe-method" v-model="probeMethod" data-test="login-state-probe-method"
            data-se1-exempt="static closed enumeration of three HTTP verbs" class="probe-method-select">
            <el-option value="GET" :label="t('sessionState.probeMethodOptionGet')" />
            <el-option value="POST" :label="t('sessionState.probeMethodOptionPost')" />
            <el-option value="PUT" :label="t('sessionState.probeMethodOptionPut')" />
          </el-select>
        </div>
        <div v-if="probeMethod !== 'GET'" class="probe-body-wrap" data-test="login-state-probe-body-wrap">
          <label class="probe-field-label" for="login-state-probe-body">{{ t('sessionState.probeBody') }}</label>
          <p class="probe-body-hint" data-test="login-state-probe-body-hint">{{ t('sessionState.probeBodyHint') }}</p>
          <el-input id="login-state-probe-body" v-model="probeBody" type="textarea" :rows="5"
            :placeholder="t('sessionState.probeBodyPlaceholder')" data-test="login-state-probe-body" />
        </div>
        <TaskTailDrawer v-if="probeTailOpen" :events="probeEvents" :live="probeRunning" :doors="probeDoors" />
        <template #footer>
          <el-button data-test="login-state-probe-close" @click="probeVisible = false">{{ t('common.close') }}</el-button>
        </template>
      </el-dialog>

      <!-- R67 revisit door : list this state's historical probe runs and
           replay any run's tail through the SHARED base (no second chain). -->
      <RunHistoryDialog
        v-model="histVisible"
        :load-runs="loadHistRuns"
        :fetch-frames="fetchHistFrames"
        :doors="histDoors"
        retention-param="ai.stream.event-retention-days"
      />

      <!-- register/edit dialog: the ledger keeps ONE form (R40) — create
           starts it blank, edit prefills every field from the row. The
           secret slot is write-only in both modes: edit never echoes the
           stored value back, a blank save keeps the vault ciphertext. -->
      <el-dialog draggable v-model="createVisible"
        :title="editingId === null ? t('sessionState.createTitle') : t('sessionState.editTitle', { name: editingName })"
        class="tp-dialog--lg" append-to-body
        :data-test="editingId === null ? 'login-state-create-dialog' : 'login-state-edit-dialog'">
        <ProjectOwnerEcho />
        <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="110px" scroll-to-error>
          <el-form-item :label="t('sessionState.stateName')" prop="stateName">
            <el-input v-model="createForm.stateName" :placeholder="t('sessionState.stateNamePlaceholder')" data-test="login-state-create-name" maxlength="64" show-word-limit />
            <p v-if="editingId !== null" class="edit-hint" data-test="login-state-rename-hint">{{ t('sessionState.renameHint') }}</p>
          </el-form-item>
          <el-form-item :label="t('sessionState.kind')" prop="kind">
            <el-select v-model="createForm.kind" data-test="login-state-create-kind" data-se1-exempt="static closed enumeration of three values" style="width: 100%" :placeholder="t('sessionState.kindPlaceholder')">
              <el-option v-for="k in kindOptions" :key="k.value" :label="t(k.labelKey)" :value="k.value" />
            </el-select>
          </el-form-item>
          <!-- combined-session headers: the state may ride extra
 auth headers alongside the kind's primary header. Each row
 references a credential already filed in this project's vault
 (el-select per SE5, never a raw value); the template slot only
 takes the shared {{CRED:key}} prefix grammar. A row left fully
 blank is dropped at submit; when no row is filled the field is
 omitted, so a re-registration keeps stored headers untouched. -->
          <el-form-item :label="t('sessionState.extraRefsSection')">
            <div class="extra-refs" data-test="login-state-extra-refs">
              <p class="extra-refs-hint" data-test="login-state-extra-refs-hint">{{ t('sessionState.extraRefsHint') }}</p>
              <div v-for="(ref, idx) in createForm.extraAuthRefs" :key="idx" class="extra-ref-row"
                :data-test="`login-state-extra-ref-row-${idx}`">
                <el-select v-model="ref.credKey" filterable class="extra-ref-cred"
                  :data-test="`login-state-extra-ref-cred-${idx}`"
                  :placeholder="t('sessionState.extraRefsCredPlaceholder')">
                  <el-option v-for="c in credentialOptions" :key="c.credKey"
                    :label="`${c.name} · ${c.credKey}`" :value="c.credKey" />
                </el-select>
                <el-input v-model="ref.headerName" class="extra-ref-name" maxlength="64" show-word-limit
                  :data-test="`login-state-extra-ref-name-${idx}`"
                  :placeholder="t('sessionState.extraRefsNamePlaceholder')" />
                <el-input v-model="ref.valueTemplate" class="extra-ref-template" maxlength="128"
                  :data-test="`login-state-extra-ref-template-${idx}`"
                  :placeholder="t('sessionState.extraRefsTemplatePlaceholder')" />
                <el-button text :icon="Delete" class="extra-ref-remove"
                  :aria-label="t('sessionState.extraRefsRemove')"
                  :data-test="`login-state-extra-ref-remove-${idx}`" @click="removeExtraRef(idx)" />
              </div>
              <el-button size="small" :icon="Plus" data-test="login-state-extra-ref-add" @click="addExtraRef">
                {{ t('sessionState.extraRefsAdd') }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item :label="t('sessionState.roleScope')">
            <el-input v-model="createForm.roleScope" :placeholder="t('sessionState.roleScopePlaceholder')" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item :label="t('sessionState.accountLabel')">
            <el-input v-model="createForm.accountLabel" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item :label="t('sessionState.credKey')">
            <el-input v-model="createForm.credKey" :placeholder="t('sessionState.credKeyPlaceholder')" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item :label="t('sessionState.secret')">
            <el-input v-model="createForm.secret" type="password" show-password
              :placeholder="editingId === null ? t('sessionState.secretPlaceholder') : t('sessionState.secretKeepHint')" />
          </el-form-item>
          <el-form-item :label="t('sessionState.scopeOrigins')">
            <el-select v-model="createForm.scopeOrigins" multiple filterable allow-create default-first-option :reserve-keyword="false" style="width: 100%" :placeholder="t('sessionState.scopeOriginsPlaceholder')">
              <el-option v-for="o in createForm.scopeOrigins" :key="o" :label="o" :value="o" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('sessionState.remark')">
            <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="login-state-create-cancel" @click="createVisible = false">{{ t('sessionState.cancel') }}</el-button>
          <!-- R28 idempotency on the edit door: an unchanged form keeps the
               save button disabled with a pointer instead of popping a fake
               "saved" toast. The create door has no baseline, it always saves. -->
          <el-tooltip :content="t('sessionState.noChangeHint')" placement="top"
            :disabled="!(editingId !== null && !formDirty)">
            <span>
              <el-button type="primary" data-test="login-state-create-submit"
                :disabled="editingId !== null && !formDirty" @click="submitForm">
                {{ editingId === null ? t('sessionState.confirm') : t('sessionState.confirmEdit') }}
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import type { ColumnDef } from '@/composables/useColumnConfig'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'

// : a landed login-state write (BD screenshot intake draft card)
// silently re-reads this list; the four-state light column then reflects the
// fresh replay verdict without any user action (iron rules 2 and 3).
useObjectSync({
  watch: ['LOGIN_STATE_WRITE'],
  onSync: () => void load(),
})
import { bizErrorMsg } from '@/utils/bizError'
import {
  deleteSessionState,
  getSessionState,
  listSessionStatesPage,
  registerSessionState,
  setSessionStateEnabled,
  updateSessionState,
  type LoginStateRow,
  probeLoginState,
  listProbeEvents,
} from '@/api/sessionState'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import RunHistoryDialog from '@/components/RunHistoryDialog.vue'
import { listCredentials, type AppautoCredential } from '@/api/appauto'
import { probeTrailDoors, listProbeRuns } from '@/api/taskEvents'
import { useTestRunTail } from '@/composables/useTestRunTail'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { projectId } = useProjectPageContext()

const projects = computed(() => auth.projects ?? [])
const loading = ref(false)
// Server-side paging door (S-group worst-size face): the table renders one
// pageful straight from the ledger query, so keyword/status filters and
// column sorts always travel to the server instead of re-ordering a loaded
// segment. Page state is restored from the URL query (F1 deep links).
const PAGE_SIZE = 20
function queryText(key: string): string {
  const v = route.query[key]
  return typeof v === 'string' ? v : ''
}
const keyword = ref(queryText('keyword'))
const status = ref<'' | number>(queryText('status') === '' ? '' : Number(queryText('status')))
const page = ref(Math.max(1, Number(queryText('page')) || 1))
const sortBy = ref(queryText('sortBy'))
const sortDir = ref(queryText('sortDir'))
const total = ref(0)
const rows = ref<LoginStateRow[]>([])

/** Header-sort affordance opens only on server-whitelisted ledger columns
 * (mirror of the core SORT_COLUMNS). The four-state light is a derived
 * display value and the vault reference key lives outside the ledger order,
 * so both refuse the affordance instead of faking a global sort (R25). */
const SERVER_SORTABLE = new Set([
  'stateName',
  'kind',
  'roleScope',
  'accountLabel',
  'expiresAt',
  'lastVerifiedAt',
  'createdAt',
  'updatedAt',
  'status',
])

/** Column set for the shared settings plumbing (single source in this file).
 * width:null = elastic min-width columns; the template renders every ledger
 * column via LIGHT_FLEX_MIN, so no fixed pixel width is set here. */
const LEDGER_COLUMNS: ColumnDef[] = [
  { key: 'stateName', labelKey: 'sessionState.stateName', width: null },
  { key: 'light', labelKey: 'sessionState.light', width: null },
  { key: 'kind', labelKey: 'sessionState.kind', width: null },
  { key: 'roleScope', labelKey: 'sessionState.roleScope', width: null },
  { key: 'accountLabel', labelKey: 'sessionState.accountLabel', width: null },
  { key: 'credKey', labelKey: 'sessionState.credKey', width: null },
  { key: 'extraAuthRefs', labelKey: 'sessionState.extraRefsColumn', width: null },
  { key: 'scopeOrigins', labelKey: 'sessionState.scopeOrigins', width: null },
  { key: 'expiresAt', labelKey: 'sessionState.expiresAt', width: null },
  { key: 'lastVerifiedAt', labelKey: 'sessionState.lastVerifiedAt', width: null },
]
const LIGHT_FLEX_MIN: Record<string, number> = {
  stateName: 160,
  scopeOrigins: 200,
  expiresAt: 170,
  lastVerifiedAt: 170,
}

// single-pane host: this page has no tabs, so the settings host binds one pane
const activePane = ref('ledger')
const settingsHost = useTabScopedSettings(activePane)
const pane = settingsHost.settingsFor('ledger', 'loginStateLedger', LEDGER_COLUMNS)

type LightKey = 'DISABLED' | 'EXPIRED' | 'EXPIRING' | 'VALID' | 'UNKNOWN'

/**
 * Display-side light. The authoritative four-state classification is the
 * server-side SessionPreflightSupport/ExpiryState single source; the light
 * here mirrors it for the ledger rows (status gate first, then expiry).
 * EXPIRING is a display estimate (one hour ahead) — the batch start gate
 * refuses on the server value, never on this pixel.
 */
function lightOf(row: LoginStateRow): LightKey {
  if (row.status === 0) return 'DISABLED'
  if (!row.expiresAt) return 'UNKNOWN'
  const expiry = Date.parse(row.expiresAt)
  if (Number.isNaN(expiry)) return 'UNKNOWN'
  const now = Date.now()
  if (expiry <= now) return 'EXPIRED'
  if (expiry <= now + 60 * 60 * 1000) return 'EXPIRING'
  return 'VALID'
}

const LIGHT_LABEL: Record<LightKey, string> = {
  get DISABLED() { return t('sessionState.lightDisabled') },
  get EXPIRED() { return t('sessionState.lightExpired') },
  get EXPIRING() { return t('sessionState.lightExpiring') },
  get VALID() { return t('sessionState.lightValid') },
  get UNKNOWN() { return t('sessionState.lightUnknown') },
}

function lightLabel(row: LoginStateRow): string {
  return LIGHT_LABEL[lightOf(row)]
}

function lightTag(row: LoginStateRow): 'success' | 'warning' | 'danger' | 'info' {
  switch (lightOf(row)) {
    case 'VALID':
      return 'success'
    case 'EXPIRING':
      return 'warning'
    case 'EXPIRED':
    case 'DISABLED':
      return 'danger'
    default:
      return 'info'
  }
}

function scopeText(row: LoginStateRow): string {
  return (row.scopeOrigins ?? []).join(', ')
}

/** Columns whose values are ISO timestamps and get the site datetime trim. */
const DATETIME_COLUMNS = new Set(['expiresAt', 'lastVerifiedAt'])

function cellText(row: LoginStateRow, key: string): string {
  const value = (row as unknown as Record<string, unknown>)[key]
  if (value == null || value === '') return '—'
  const text = String(value)
  // Only datetime cells get the shared ISO trim (2026-11-13T22:25:38 ->
  // 2026-11-13 22:25:38). Every other cell keeps its full text in the DOM:
  // a state name or cred key must never be silently cut (information stays
  // complete; visual overflow is a CSS ellipsis concern, not a data one —
  // slicing here broke the ledger E2E row match on 2026-11-13).
  if (DATETIME_COLUMNS.has(key)) return text.replace('T', ' ').slice(0, 19)
  return text
}

/** Page-state URL sync (F1): filters/sort/page ride the query so refresh,
 * back and shared links restore the same ledger segment; defaults drop out of
 * the URL and foreign query keys (deep-link projectId) are preserved. */
function syncUrl(): void {
  const query: LocationQueryRaw = { ...route.query }
  const put = (key: string, value: string) => {
    if (value) query[key] = value
    else delete query[key]
  }
  put('page', page.value > 1 ? String(page.value) : '')
  put('keyword', keyword.value.trim())
  put('status', status.value === '' ? '' : String(status.value))
  put('sortBy', sortBy.value)
  put('sortDir', sortBy.value ? sortDir.value : '')
  void router.replace({ query })
}

/** The skeleton and the table alternate on v-if/v-else, so the table
 * remounts on every load; without replaying the current order through
 * default-sort the header toggle cycle would restart at ascending each
 * time (found by the 2026-11-14 worst-size paging spectrum). */
const activeSort = computed(() =>
  sortBy.value && (sortDir.value === 'ascending' || sortDir.value === 'descending')
    ? { prop: sortBy.value, order: sortDir.value as 'ascending' | 'descending' }
    : {},
)

function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  sortBy.value = order ? prop : ''
  sortDir.value = order ?? ''
  page.value = 1
  void load()
}

function applyFilters(): void {
  page.value = 1
  void load()
}

async function load() {
  if (!projectId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const r = await listSessionStatesPage({
      projectId: projectId.value,
      keyword: keyword.value.trim() || null,
      status: status.value === '' ? null : status.value,
      sortBy: sortBy.value || null,
      sortDir: sortDir.value || null,
      page: page.value,
      size: PAGE_SIZE,
    })
    rows.value = r.list
    total.value = r.total
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('sessionState.loadFailed')))
  } finally {
    loading.value = false
  }
  syncUrl()
}

/** Lifecycle actions keep calling reload(); it re-runs the paged door. */
async function reload(): Promise<void> {
  await load()
}

watch(projectId, () => {
  page.value = 1
  void load()
})

/** URL-as-single-source resync (F1 convention, mirrors ElementLibraryView):
 * a hash-only navigation — browser back or a pasted link — changes the query
 * without remounting this component, so the filter refs must re-read the URL
 * or a stale keyword would silently ride along into the next ledger query.
 * The value-equality guard breaks any echo with this page's own syncUrl. */
function urlState() {
  return {
    keyword: queryText('keyword'),
    status: queryText('status'),
    page: Math.max(1, Number(queryText('page')) || 1),
    sortBy: queryText('sortBy'),
    sortDir: queryText('sortDir'),
  }
}
watch(() => route.query, () => {
  const u = urlState()
  if (u.keyword === keyword.value && u.status === String(status.value)
    && u.page === page.value && u.sortBy === sortBy.value
    && u.sortDir === sortDir.value) return
  keyword.value = u.keyword
  status.value = u.status === '' ? '' : Number(u.status)
  page.value = u.page
  sortBy.value = u.sortBy
  sortDir.value = u.sortDir
  void load()
})
onMounted(load)

const createVisible = ref(false)
/** Edit-mode anchor: null = create dialog, number = ledger row being edited. */
const editingId = ref<number | null>(null)
const editingName = ref('')
/** Serialized snapshot taken when the edit dialog opens (R28 dirty baseline). */
const formBaseline = ref('')
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  stateName: '',
  kind: '',
  roleScope: '',
  accountLabel: '',
  credKey: '',
  secret: '',
  scopeOrigins: [] as string[],
  remark: '',
  extraAuthRefs: [] as { credKey: string; headerName: string; valueTemplate: string }[],
})

/** Vault credential candidates for the combined-header selector (SE5: a
 *  closed list fetched from the project's filed credentials, never free text). */
const credentialOptions = ref<AppautoCredential[]>([])

async function loadCredentialOptions() {
  if (!projectId.value) return
  try {
    credentialOptions.value = await listCredentials(projectId.value, true)
  } catch (e) {
    credentialOptions.value = []
    ElMessage.error(bizErrorMsg(e, t('sessionState.extraRefsCredLoadFailed')))
  }
}

function addExtraRef() {
  createForm.extraAuthRefs.push({ credKey: '', headerName: '', valueTemplate: '' })
}

function removeExtraRef(idx: number) {
  createForm.extraAuthRefs.splice(idx, 1)
}

/** Ledger column cell: how many combined headers the row rides. */
function extraRefsText(row: LoginStateRow): string {
  const n = (row.extraAuthRefs ?? []).length
  return n > 0 ? t('sessionState.extraRefsCount', { n }) : '—'
}

// Closed enumeration mirrored from the server constant
// core SessionStateSupport.KINDS; the server-side value check stays as the
// second-line defence for direct API calls. Each option label carries an
// effect sentence so the choice is self-explanatory (SE5 iron rule).
const kindOptions = [
  { value: 'token', labelKey: 'sessionState.kindOptionToken' },
  { value: 'storage_state', labelKey: 'sessionState.kindOptionStorageState' },
  { value: 'cookie_set', labelKey: 'sessionState.kindOptionCookieSet' },
]

/** Canonical snapshot of the editable form — the edit dialog's dirty baseline
 * and the create path share it so a save that changed nothing is caught here
 * (R28) instead of relying on the server to fake a "saved" toast. Blank
 * combined-header rows are dropped exactly like at submit; header order is
 * kept because the ledger dialog lists rows in the order the user set. */
function serializeForm(): string {
  const filled = createForm.extraAuthRefs
    .filter((r) => r.credKey.trim() || r.headerName.trim() || r.valueTemplate.trim())
    .map((r) => [r.credKey.trim(), r.headerName.trim(), r.valueTemplate.trim()])
  return JSON.stringify({
    stateName: createForm.stateName.trim(),
    kind: createForm.kind.trim(),
    roleScope: createForm.roleScope.trim(),
    accountLabel: createForm.accountLabel.trim(),
    credKey: createForm.credKey.trim(),
    scopeOrigins: createForm.scopeOrigins.map((o) => o.trim()),
    remark: createForm.remark.trim(),
    refs: filled,
  })
}

const formDirty = computed(() => serializeForm() !== formBaseline.value)

function resetForm(): void {
  createForm.stateName = ''
  createForm.kind = ''
  createForm.roleScope = ''
  createForm.accountLabel = ''
  createForm.credKey = ''
  createForm.secret = ''
  createForm.scopeOrigins = []
  createForm.remark = ''
  createForm.extraAuthRefs = []
}

function openCreate() {
  editingId.value = null
  editingName.value = ''
  formBaseline.value = ''
  resetForm()
  createVisible.value = true
  void loadCredentialOptions()
}

/**
 * Edit door (the ledger edit fix): prefill every field from a fresh server
 * read. The secret slot is write-only and never echoed — blank means keep the
 * vault ciphertext, typing replaces it. Combined headers arrive as editable
 * rows, so add/remove/template all work on a registered state, not only on a
 * brand-new registration.
 */
async function openEdit(row: LoginStateRow) {
  try {
    const fresh = await getSessionState(row.id)
    editingId.value = fresh.id
    editingName.value = fresh.stateName
    createForm.stateName = fresh.stateName
    createForm.kind = fresh.kind
    createForm.roleScope = fresh.roleScope ?? ''
    createForm.accountLabel = fresh.accountLabel ?? ''
    createForm.credKey = fresh.credKey ?? ''
    createForm.secret = ''
    createForm.scopeOrigins = [...(fresh.scopeOrigins ?? [])]
    createForm.remark = fresh.remark ?? ''
    createForm.extraAuthRefs = (fresh.extraAuthRefs ?? []).map((r) => ({
      credKey: r.credKey,
      headerName: r.headerName ?? '',
      valueTemplate: r.valueTemplate ?? '',
    }))
    formBaseline.value = serializeForm()
    createVisible.value = true
    void loadCredentialOptions()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('sessionState.loadFailed')))
  }
}

const createRules = computed<FormRules>(() => ({
  stateName: [{ required: true, message: t('sessionState.stateNameRequired'), trigger: 'blur' }],
  kind: [{ required: true, message: t('sessionState.kindRequired'), trigger: 'change' }],
}))

async function submitForm() {
  const form = createFormRef.value
  if (!form || !projectId.value) return
  const ok = await form.validate().catch(() => false)
  if (!ok) return
  // Fully blank rows are dropped; a half-filled row still travels to core,
  // whose fail-closed validation names the offending row (R63) — the ledger
  // never silently swallows an intent the user typed. On the create door no
  // filled row at all omits the field, so a re-registration keeps stored
  // headers untouched; the edit door always sends the explicit list, because
  // deleting every visible row is a real user intent to clear them.
  const filled = createForm.extraAuthRefs.filter((r) => r.credKey.trim() || r.headerName.trim() || r.valueTemplate.trim())
  const refs = filled.map((r) => ({
    credKey: r.credKey.trim(),
    into: 'header',
    headerName: r.headerName.trim(),
    valueTemplate: r.valueTemplate.trim() || null,
  }))
  if (editingId.value !== null) {
    try {
      await updateSessionState(editingId.value, {
        projectId: projectId.value,
        stateName: createForm.stateName.trim(),
        roleScope: createForm.roleScope.trim() || null,
        accountLabel: createForm.accountLabel.trim() || null,
        kind: createForm.kind.trim(),
        credKey: createForm.credKey.trim() || null,
        secret: createForm.secret || null,
        scopeOrigins: createForm.scopeOrigins.length ? createForm.scopeOrigins : null,
        remark: createForm.remark.trim() || null,
        extraAuthRefs: refs,
      })
      createVisible.value = false
      ElMessage.success(t('sessionState.updated', { name: createForm.stateName }))
      await reload()
    } catch (e) {
      ElMessage.error(bizErrorMsg(e, t('sessionState.updateFailed')))
    }
    return
  }
  try {
    await registerSessionState({
      projectId: projectId.value,
      stateName: createForm.stateName.trim(),
      roleScope: createForm.roleScope.trim() || null,
      accountLabel: createForm.accountLabel.trim() || null,
      kind: createForm.kind.trim(),
      credKey: createForm.credKey.trim() || null,
      secret: createForm.secret || null,
      scopeOrigins: createForm.scopeOrigins.length ? createForm.scopeOrigins : null,
      remark: createForm.remark.trim() || null,
      extraAuthRefs: refs.length ? refs : undefined,
    })
    createVisible.value = false
    createForm.secret = ''
    ElMessage.success(t('sessionState.created', { name: createForm.stateName }))
    await reload()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('sessionState.createFailed')))
  }
}

const probeVisible = ref(false)
const probeRow = ref<LoginStateRow | null>(null)
const probeUrl = ref('')
/** Request shape for the probe door (same grammar as the vault test door). */
const probeMethod = ref('GET')
const probeBody = ref('')
const {
  events: probeEvents, running: probeRunning, drawerOpen: probeTailOpen, openRun: probeOpenRun,
} = useTestRunTail(async (runToken, cursor) =>
  (await listProbeEvents(probeRow.value!.id, runToken, cursor)).map((row) => ({
    cursor: row.cursor, level: row.level, summary: row.summary, detail: row.detail, landedAt: row.landedAt,
  })))
/** gated read doors over the probe trail : follow the open row */
const probeDoors = computed(() => (probeRow.value ? probeTrailDoors(probeRow.value.id) : undefined))

// ---------- R67 revisit door : run history + replay over the shared base ----------
const histVisible = ref(false)
const histRow = ref<LoginStateRow | null>(null)
const histDoors = computed(() => (histRow.value ? probeTrailDoors(histRow.value.id) : undefined))
function openHistory(row: LoginStateRow) {
  histRow.value = row
  histVisible.value = true
}
async function loadHistRuns() {
  if (!histRow.value) return []
  return listProbeRuns(histRow.value.id)
}
async function fetchHistFrames(runToken: string, cursor: number) {
  if (!histRow.value) return []
  const rows = await listProbeEvents(histRow.value.id, runToken, cursor)
  return rows.map((row) => ({
    cursor: row.cursor, level: row.level, summary: row.summary, detail: row.detail, landedAt: row.landedAt,
  }))
}

function openProbe(row: LoginStateRow) {
  probeRow.value = row
  probeUrl.value = ''
  probeMethod.value = 'GET'
  probeBody.value = ''
  probeEvents.value = []
  probeTailOpen.value = false
  probeVisible.value = true
}

async function runProbe() {
  if (!probeRow.value) {
    return
  }
  try {
    // GET stays byte-identical to the historical door (no run payload);
    // POST/PUT carry the transient template — the server fills {{CRED:}}
    // vault references at send time and the trail echoes only the source.
    const run = probeMethod.value === 'GET'
      ? undefined
      : { method: probeMethod.value, bodyTemplate: probeBody.value.trim() || null }
    const outcome = await probeLoginState(probeRow.value.id, probeUrl.value || undefined, run)
    await probeOpenRun(outcome)
    await load()
  } catch (error) {
    ElMessage.error(bizErrorMsg(error, t('sessionState.probeFailed')))
  }
}

async function doToggle(row: LoginStateRow) {
  const enable = row.status === 0
  try {
    await ElMessageBox.confirm(
      enable ? t('sessionState.enableConfirm', { name: row.stateName }) : t('sessionState.disableConfirm', { name: row.stateName }),
      t('sessionState.toggleTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await setSessionStateEnabled(row.id, enable)
    ElMessage.success(enable ? t('sessionState.enabled', { name: row.stateName }) : t('sessionState.disabled', { name: row.stateName }))
    await reload()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('sessionState.toggleFailed')))
  }
}

async function doDelete(row: LoginStateRow) {
  try {
    await ElMessageBox.confirm(t('sessionState.deleteConfirm', { name: row.stateName }), t('sessionState.deleteTitle'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteSessionState(row.id)
    ElMessage.success(t('sessionState.deleted', { name: row.stateName }))
    await reload()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('sessionState.deleteFailed')))
  }
}
</script>

<style scoped>
.data-table {
  margin-top: 12px;
}
/* condition controls keep the site filter-field widths (the band gap itself
   is the theme .filter-bar single source, not tuned here). */
.filter-keyword {
  width: 240px;
}
.filter-status {
  width: 140px;
}
/* combined-header rows: fixed identity fields left, template grows;
   row gap follows the site form-line single source (no negative offsets). */
.extra-refs {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.extra-refs-hint {
  margin: 0;
  color: var(--tp-text-muted, inherit);
}
.extra-ref-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(140px, 1fr) minmax(180px, 1.4fr) auto;
  gap: 8px;
  align-items: center;
}
/* Every filled row is fail-closed server-side on the credential pick and the
   header name (LoginStateServiceImpl.validateExtraRefs), but the row has no
   form labels to carry the standard marker — paint the red asterisk inside
   the two required controls so the dialog names the requirement before
   submit instead of only rejecting afterwards. */
.extra-ref-cred :deep(.el-select__wrapper)::before,
.extra-ref-name :deep(.el-input__wrapper)::before {
  content: "*";
  color: var(--el-color-danger, #f56c6c);
  margin-inline-end: 2px;
}
/* edit-mode pointer under the name field (rename reaches consumers). */
.edit-hint {
  margin: 4px 0 0;
  color: var(--tp-text-muted, inherit);
}
/* probe-door request-shape controls (11-19): the classes were referenced by
   the dialog markup ever since but never styled (inputs fell back to block
   defaults); landed together with the method/body surface. */
.probe-hint {
  margin: 0 0 12px;
  color: var(--tp-text-muted, inherit);
}
.probe-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.probe-url-input {
  flex: 1;
}
.probe-method-row {
  gap: 12px;
}
.probe-field-label {
  min-width: 64px;
  color: var(--tp-text-secondary, inherit);
}
.probe-method-select {
  width: 260px;
}
.probe-body-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}
.probe-body-hint {
  margin: 0;
  color: var(--tp-text-muted, inherit);
}
</style>
