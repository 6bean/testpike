<!-- tp-sort-exempt: mock spaces are hand-created scopes (tens per project) served in full by the list door, then windowed client-side by the page pager (display-scale gate, 6b vision segment). -->
<template>
  <div class="page-root mock-spaces-page" data-test="mock-spaces-page">
    <div class="page-card">
      <!-- R23 stage-2: the title shell row is gone — the page name rides the
 global topbar and the retired title-side "?" now lives on the topbar
 help dot (data-test=header-help-btn); the descriptive subtitle is
 dropped with the shell (its content lives in the help chapter).
 IA law inherited: the page's main write action moved to the
 leading slot of the filter-bar row (R23 law 5), refresh rides the
 trailing slot; the action slot hosts no selectors. -->

      <!-- Serving-plane root: every space route hangs under this prefix. The two
           values come verbatim from the config supply gate (02 v2.63) — the page
           never assembles a host it was not told. -->
      <div v-if="servingUrl" class="serving-bar" data-test="mock-spaces-serving">
        <span class="serving-label">{{ t('mockSpaces.servingBar') }}</span>
        <span class="mono">{{ servingUrl.publicBaseUrl }}{{ servingUrl.trafficBasePath }}</span>
      </div>

      <!-- R23 law 5: the write entry rides the filter-bar leading slot and
           stays rendered while the context is empty (iron rule 2: disabled +
           plain-word pointer, the no-project empty block below is the belt
           piece). Screening + refresh appear once a project is picked. -->
      <div class="filter-bar" data-test="mock-spaces-filter-bar">
        <!-- iron rule 2: while no header project is picked, the entry is
             disabled and the tooltip points at the header (no post-submit
             generic error) -->
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
              data-test="mock-spaces-new"
              @click="openCreate"
            >
              {{ t('mockSpaces.newSpace') }}
            </el-button>
          </span>
        </el-tooltip>
        <template v-if="projectId">
          <!-- display-scale iron law (vision gate, dim4=2 before): worst-scale
               fixture showed 32 rows fully inlined with no pager and no search —
               this bar carries an instant name/key filter and the table pages at
               20 rows per window. Spaces are project-scoped hand-made objects,
               so the ledger is filtered and sliced client-side over the full list
               the server already served (no second server spectrum). -->
          <el-input
            v-model="keyword"
            :placeholder="t('mockSpaces.searchPh')"
            :prefix-icon="Search"
            clearable
            style="width: 220px"
            data-test="mock-spaces-search"
          />
          <div class="filter-bar-actions" data-test="mock-spaces-filter-actions">
            <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
                 tabs and exactly one table, so the page-level column-settings popover equals the
                 table scope; show/hide, order, pinning and density persist per user via the piece. -->
            <ColumnSettings
              :columns="settingsRows"
              :density="colCfg.state.density"
              @toggle="colCfg.toggle"
              @move="colCfg.move"
              @density="colCfg.setDensity"
              @reset="colCfg.reset"
              @pin="colCfg.toggleFixed"
            />
            <el-button :icon="Refresh" :loading="loading" data-test="mock-spaces-refresh" @click="loadSpaces">
              {{ t('mockSpaces.refresh') }}
            </el-button>
          </div>
        </template>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-spaces-no-project">
        <EmptyState :title="t('mockSpaces.noProject')" />
      </div>

      <template v-else>
        <TableSkeleton v-if="loading && !spaces.length" :cols="colCfg.visibleColumns.value.length + 1" data-test="mock-spaces-skeleton" />
        <el-table border v-else :data="pagedSpaces" :size="colCfg.state.density" data-test="mock-spaces-table">
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : MOCK_SPACES_FLEX_MIN[col.key]"
            :data-test="`mock-spaces-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'name'">
                <span>{{ row.name }}</span>
                <span v-if="row.alias" class="muted alias-sub">（{{ t('mockSpaces.aliasLabel') }} {{ row.alias }}）</span>
              </template>
              <template v-else-if="col.key === 'spaceKey'"><span class="mono">{{ row.spaceKey }}</span></template>
              <template v-else-if="col.key === 'status'">
                <el-tag :type="statusTone(row.status)" size="small" data-test="mock-spaces-status">
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'version'">{{ row.version ?? '—' }}</template>
              <template v-else-if="col.key === 'priority'">{{ row.priority ?? '—' }}</template>
              <template v-else-if="col.key === 'authMode'">{{ authModeLabel(row.authMode) }}</template>
              <template v-else-if="col.key === 'missPolicy'">
                <!-- miss ladder: the rung decides what a no-stub-match request does.
 Editable in place for write-permitted operators; legality stays the
 core spectrum, 40901 conflict reloads. Hit-area guard: the wider
 column keeps the 14px caret clear of the actions column (WCAG 2.5.8). -->
                <el-select
                  v-if="canWrite"
                  :model-value="row.missPolicy ?? 'llm-fallback'"
                  class="policy-select"
                  data-test="mock-spaces-policy-select"
                  @change="(value: string) => savePolicy(row, value)"
                >
                  <el-option
                    v-for="rung in missPolicyRungs"
                    :key="rung"
                    :label="t(`mockSpaces.missPolicy.${rung}`)"
                    :value="rung"
                  />
                </el-select>
                <span v-else data-test="mock-spaces-policy-static">{{ missPolicyLabel(row.missPolicy) }}</span>
              </template>
              <template v-else-if="col.key === 'limits'">
                <span class="muted">{{ limitsText(row) }}</span>
              </template>
              <template v-else>{{ row[col.key] }}</template>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockSpaces.colActions')" width="320" fixed="right" class-name="actions-cell-nowrap">
            <template #default="{ row }">
              <!-- AN-4: the spaces list is the only menu door into a
 space — open the per-space workbench here, forwarding any
 tab/source intent carried on our own URL (a jump that
 lost its space id resolves honestly through this list). -->
              <el-button link type="primary" data-test="mock-spaces-open-workbench" @click="openWorkbench(row)">
                {{ t('mockSpaces.actOpenWorkbench') }}
              </el-button>
              <el-button link type="primary" data-test="mock-spaces-copy-url" @click="copySpaceUrl(row)">
                {{ t('mockSpaces.actCopyUrl') }}
              </el-button>
              <el-button
                v-if="row.status === 0 && canPublish"
                link
                type="success"
                data-test="mock-spaces-publish"
                @click="transition(row, 1)"
              >
                {{ t('mockSpaces.actPublish') }}
              </el-button>
              <el-button
                v-if="row.status === 1 && canPublish"
                link
                type="danger"
                data-test="mock-spaces-retire"
                @click="transition(row, 2)"
              >
                {{ t('mockSpaces.actRetire') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span data-test="mock-spaces-empty">{{ t('mockSpaces.empty') }}</span>
          </template>
        </el-table>
        <!-- Client-side window over the project's space list (worst-scale iron
             law): the pager hides when the whole ledger fits one page. -->
        <div v-if="filtered.length > pageSize" class="pagination-bar" data-test="mock-spaces-pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="filtered.length"
            layout="prev, pager, next, total"
          />
        </div>
      </template>

      <!-- Create dialog: server-side validation is the single point, so the form
 only guards emptiness; every legality refusal (40001) shows the core's
 plain-word message untouched. Ratio iron law (vision gate :
 the old 520px single-column stack measured 0.6:1 (a narrow stripe) —
 the dialog now sits at 900px with short fields paired two per row,
 bringing the measured box into the 1.2–1.8:1 comfort band. -->
      <el-dialog
        v-model="createVisible"
        :title="t('mockSpaces.createTitle')"
        width="900px"
        data-test="mock-spaces-create-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context — the space lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form label-position="top">
          <div class="dialog-form-pair">
            <el-form-item :label="t('mockSpaces.fieldName')" required>
              <el-input v-model="form.name" maxlength="120" data-test="mock-spaces-f-name" />
            </el-form-item>
            <el-form-item :label="t('mockSpaces.fieldAlias')">
              <el-input v-model="form.alias" maxlength="60" data-test="mock-spaces-f-alias" />
            </el-form-item>
          </div>
          <el-form-item :label="t('mockSpaces.fieldKey')" required>
            <el-input v-model="form.spaceKey" maxlength="60" data-test="mock-spaces-f-key" />
            <div class="field-hint">{{ t('mockSpaces.fieldKeyHint') }}</div>
          </el-form-item>
          <div class="dialog-form-pair">
            <el-form-item :label="t('mockSpaces.fieldBasePathMode')">
              <el-select v-model="form.basePathMode" style="width: 100%" data-test="mock-spaces-f-basepath">
                <el-option :label="t('mockSpaces.basePathPrefix')" value="prefix" />
                <el-option :label="t('mockSpaces.basePathStrict')" value="strict" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('mockSpaces.fieldAuthMode')">
              <el-select v-model="form.authMode" style="width: 100%" data-test="mock-spaces-f-auth">
                <el-option :label="t('mockSpaces.authNone')" value="none" />
                <el-option :label="t('mockSpaces.authToken')" value="token" />
                <el-option :label="t('mockSpaces.authCred')" value="cred" />
              </el-select>
            </el-form-item>
          </div>
          <div class="dialog-form-pair">
            <el-form-item :label="t('mockSpaces.fieldRateLimit')">
              <el-input-number v-model="form.rateLimitRps" :min="1" :controls="false" style="width: 100%" data-test="mock-spaces-f-rate" />
            </el-form-item>
            <el-form-item :label="t('mockSpaces.fieldQuotaDaily')">
              <el-input-number v-model="form.quotaDaily" :min="1" :controls="false" style="width: 100%" data-test="mock-spaces-f-quota" />
            </el-form-item>
          </div>
          <!-- space-level chaos dial: the whole space answers ~N% of served
 requests with a plain 503 (labelled chaos in the hit ledger).
 Range legality itself stays the core save gate (0-100 refusal in
 plain words) — the dial only bounds the input. -->
          <div class="dialog-form-pair">
            <el-form-item :label="t('mockSpaces.fieldPriority')">
              <el-input-number v-model="form.priority" :min="1" :controls="false" style="width: 100%" data-test="mock-spaces-f-priority" />
            </el-form-item>
            <el-form-item :label="t('mockSpaces.fieldChaos')">
              <el-input-number
                v-model="form.chaosPercent"
                :min="0"
                :max="100"
                :controls="false"
                style="width: 100%"
                data-test="mock-spaces-f-chaos"
              />
              <div class="field-hint">{{ t('mockSpaces.fieldChaosHint') }}</div>
            </el-form-item>
          </div>
          <!-- the miss ladder picked at birth. Rung legality and
 the AI double-gate (global switch + fallback switch) are enforced
 in core — the hint only tells the truth about what each rung does. -->
          <el-form-item :label="t('mockSpaces.fieldMissPolicy')">
            <el-select v-model="form.missPolicy" style="width: 100%" data-test="mock-spaces-f-misspolicy">
              <el-option
                v-for="rung in missPolicyRungs"
                :key="rung"
                :label="t(`mockSpaces.missPolicy.${rung}`)"
                :value="rung"
              />
            </el-select>
            <div class="field-hint">{{ t(`mockSpaces.missPolicyHint.${form.missPolicy}`) }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="mock-spaces-create-cancel" @click="createVisible = false">
            {{ t('mockSpaces.createCancel') }}
          </el-button>
          <el-button type="primary" :loading="creating" data-test="mock-spaces-create-submit" @click="submitCreate">
            {{ t('mockSpaces.create') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AG batch : the mock space workbench.
 * Journey: pick project -> create a draft space -> publish it (the only legal
 * serving state) -> copy its access URL / curl example. Retire stops serving
 * immediately. Lifecycle edges and the production-env ban are decided in core —
 * this page mirrors the honest outcomes: '40901' (someone else moved the row)
 * prompts a reload, and legality refusals (40001) show core's plain-word
 * message untouched. Publish/retire buttons hide without perm:mock:publish.
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
// AK single-source posture : page-level project selector removed — the
// project rides on the global header context via useProjectPageContext.
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { BizError } from '@/api/request'
import { bizErrorMsg } from '@/utils/bizError'
import { useAuthStore } from '@/stores/auth'
import {
  createSpace,
  getServingUrl,
  listSpaces,
  transitionSpace,
  updateSpacePolicy,
  type MockSpaceItem,
  type MockServingUrl,
} from '@/api/mock'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// AK single-source posture: page project state comes from the shared composable
// (URL priority at setup, two-way sync with the header context). No local copy.
const { projectId } = useProjectPageContext()

const canWrite = computed(() => auth.hasPerm('perm:mock:write'))

/**
 * Spaces ledger column definitions for the shared column-config piece (N-3 repayment).
 * Widths mirror the previous hard-coded el-table-column attributes so the default
 * layout is unchanged; show/hide, order, pinning and density then persist per user
 * through the piece. The trailing action column stays pinned outside the loop
 * (locked column, not configurable).
 */
const MOCK_SPACES_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'mockSpaces.colName', width: null },
  { key: 'spaceKey', labelKey: 'mockSpaces.colKey', width: null },
  { key: 'status', labelKey: 'mockSpaces.colStatus', width: 110 },
  { key: 'version', labelKey: 'mockSpaces.colVersion', width: 90 },
  { key: 'priority', labelKey: 'mockSpaces.colPriority', width: 90 },
  { key: 'authMode', labelKey: 'mockSpaces.colAuthMode', width: 110 },
  { key: 'missPolicy', labelKey: 'mockSpaces.colMissPolicy', width: 150 },
  { key: 'limits', labelKey: 'mockSpaces.colLimits', width: null },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const MOCK_SPACES_FLEX_MIN: Record<string, number> = { name: 170, spaceKey: 150, limits: 150 }
const colCfg = useColumnConfig('mock-spaces', MOCK_SPACES_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: MOCK_SPACES_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const canPublish = computed(() => auth.hasPerm('perm:mock:publish'))

const spaces = ref<MockSpaceItem[]>([])
// Boot-guarded skeleton: the table v-else would paint its empty state on the
// first frame when the flag starts false (measured flash 2221ms, 2026-11-04).
const loading = ref(true)
const servingUrl = ref<MockServingUrl | null>(null)

// Worst-scale window : instant filter over name/spaceKey
// plus a 20-row client page. Re-loading or changing the project resets the
// window so a stale page index can never show an empty table on real data.
const keyword = ref('')
const page = ref(1)
const pageSize = 20
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return spaces.value
  return spaces.value.filter(
    (s) => s.name.toLowerCase().includes(kw) || s.spaceKey.toLowerCase().includes(kw),
  )
})
const pagedSpaces = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize),
)
watch([keyword, projectId], () => {
  page.value = 1
})

const createVisible = ref(false)
const creating = ref(false)
// Miss ladder rungs — the shared spectrum names mirrored from core's
// MockMissPolicies (passthrough / reject / llm-fallback). Rendering order only;
// legality of any value stays decided in the core save chain.
const missPolicyRungs = ['passthrough', 'reject', 'llm-fallback'] as const
const form = ref({
  name: '',
  spaceKey: '',
  alias: '',
  basePathMode: 'prefix',
  authMode: 'none',
  rateLimitRps: null as number | null,
  quotaDaily: null as number | null,
  priority: null as number | null,
  chaosPercent: null as number | null,
  missPolicy: 'llm-fallback' as string,
})

async function loadSpaces(): Promise<void> {
  servingUrl.value = await getServingUrl().catch(() => null)
  if (!projectId.value) {
    spaces.value = []
    return
  }
  loading.value = true
  try {
    spaces.value = await listSpaces(projectId.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockSpaces.loadSpacesFailed')))
    spaces.value = []
  } finally {
    loading.value = false
  }
}

watch(projectId, () => void loadSpaces())

function statusLabel(status: number | null): string {
  if (status === 1) return t('mockSpaces.statusPublished')
  if (status === 2) return t('mockSpaces.statusRetired')
  return t('mockSpaces.statusDraft')
}

function statusTone(status: number | null): 'success' | 'warning' | 'info' {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  return 'info'
}

function authModeLabel(mode: string | null): string {
  if (mode === 'token') return t('mockSpaces.authToken')
  if (mode === 'cred') return t('mockSpaces.authCred')
  return t('mockSpaces.authNone')
}

/** Rung label with the server-default truth: a null column value means the
 * row was born before the ladder existed — default behavior is llm-fallback. */
function missPolicyLabel(policy: string | null): string {
  return t(`mockSpaces.missPolicy.${policy ?? 'llm-fallback'}`)
}

/**
 * Inline miss-ladder save . The select is one-way bound to the
 * row, so a refused write never leaves the UI showing a lie: '40901' reloads
 * the list (someone edited the row), any core legality refusal shows the
 * plain-word message and the row keeps its previous rung.
 */
async function savePolicy(row: MockSpaceItem, nextPolicy: string): Promise<void> {
  if (nextPolicy === (row.missPolicy ?? 'llm-fallback')) return
  try {
    const saved = await updateSpacePolicy(row.id, nextPolicy, row.version ?? 0)
    row.missPolicy = saved.missPolicy
    row.version = saved.version
    ElMessage.success(t('mockSpaces.policySaved', { name: row.name }))
  } catch (e) {
    if (e instanceof BizError && e.code === '40901') {
      ElMessage.warning(t('mockSpaces.conflictRefresh'))
      await loadSpaces()
      return
    }
    ElMessage.error(bizErrorMsg(e, t('mockSpaces.policySaveFailed')))
  }
}

function limitsText(row: MockSpaceItem): string {
  const parts: string[] = []
  parts.push(row.rateLimitRps ? `${t('mockSpaces.rateUnit')} ${row.rateLimitRps}` : t('mockSpaces.limitDefault'))
  if (row.quotaDaily) parts.push(`${t('mockSpaces.quotaUnit')} ${row.quotaDaily}`)
  // Chaos dial display honesty: a set dial shows the percent, an unset/zero
  // dial says "no injection" — never a silent blank that could read unknown.
  parts.push(row.chaosPercent ? t('mockSpaces.chaosTag', { n: row.chaosPercent }) : t('mockSpaces.chaosNone'))
  return parts.join(' · ')
}

/** Space route = serving root + '/' + routing handle (alias joins as a twin). */
function spaceUrl(row: MockSpaceItem, handle: string): string {
  const base = servingUrl.value
  if (!base) return ''
  return `${base.publicBaseUrl}${base.trafficBasePath}/${handle}`
}

async function copyText(text: string, okKey: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t(okKey))
  } catch {
    ElMessage.warning(t('mockSpaces.copyDenied'))
  }
}

/** Open this space's workbench, forwarding any tab/source intent carried on the list URL. */
function openWorkbench(row: MockSpaceItem): void {
  const query: LocationQueryRaw = { projectId: String(projectId.value ?? '') }
  const tab = route.query.tab
  const source = route.query.source
  if (typeof tab === 'string' && tab) query.tab = tab
  if (typeof source === 'string' && source) query.source = source
  void router.push({ path: `/mock/spaces/${row.id}`, query })
}

function copySpaceUrl(row: MockSpaceItem): void {
  if (!servingUrl.value) {
    ElMessage.warning(t('mockSpaces.servingUnavailable'))
    void loadSpaces()
    return
  }
  const primary = spaceUrl(row, row.spaceKey)
  const lines = [`curl -s ${primary}`]
  if (row.alias) lines.push(`curl -s ${spaceUrl(row, row.alias)}`)
  void copyText(lines.join('\n'), 'mockSpaces.urlCopied')
}

/**
 * Lifecycle move. expectedVersion carries the row version untouched — the core
 * UPDATE runs under the same optimistic lock, so a concurrent edit surfaces as
 * BizError '40901' and the honest action is reload-then-retry, never resend.
 */
async function transition(row: MockSpaceItem, targetStatus: number): Promise<void> {
  const confirmText = targetStatus === 1
    ? t('mockSpaces.publishConfirm', { name: row.name })
    : t('mockSpaces.retireConfirm', { name: row.name })
  try {
    await ElMessageBox.confirm(confirmText, t('mockSpaces.transitionTitle'), {
      confirmButtonText: t('mockSpaces.transitionOk'),
      cancelButtonText: t('mockSpaces.createCancel'),
      type: targetStatus === 2 ? 'warning' : 'info',
    })
  } catch {
    return
  }
  try {
    await transitionSpace(row.id, targetStatus, row.version ?? 0)
    ElMessage.success(targetStatus === 1 ? t('mockSpaces.published') : t('mockSpaces.retired'))
    await loadSpaces()
  } catch (e) {
    if (e instanceof BizError && e.code === '40901') {
      ElMessage.warning(t('mockSpaces.conflictRefresh'))
      await loadSpaces()
      return
    }
    // Illegal edge / env turned production / any core refusal: plain words as-is.
    ElMessage.error(bizErrorMsg(e, t('mockSpaces.transitionFailed')))
  }
}

function openCreate(): void {
  form.value = {
    name: '',
    spaceKey: '',
    alias: '',
    basePathMode: 'prefix',
    authMode: 'none',
    rateLimitRps: null,
    quotaDaily: null,
    priority: null,
    chaosPercent: null,
    missPolicy: 'llm-fallback',
  }
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!projectId.value || !form.value.name.trim() || !form.value.spaceKey.trim()) {
    ElMessage.warning(t('mockSpaces.fieldsRequired'))
    return
  }
  creating.value = true
  try {
    await createSpace({
      projectId: projectId.value,
      name: form.value.name.trim(),
      spaceKey: form.value.spaceKey.trim(),
      alias: form.value.alias.trim() || null,
      basePathMode: form.value.basePathMode,
      authMode: form.value.authMode,
      rateLimitRps: form.value.rateLimitRps,
      quotaDaily: form.value.quotaDaily,
      priority: form.value.priority,
      // null/undefined = server default (0, no injection); the core save
      // gate owns the 0-100 legality with a plain-word refusal.
      chaosPercent: form.value.chaosPercent,
      // Miss ladder chosen at birth; the core save chain validates the rung.
      missPolicy: form.value.missPolicy,
    })
    ElMessage.success(t('mockSpaces.created'))
    createVisible.value = false
    await loadSpaces()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockSpaces.createFailed')))
  } finally {
    creating.value = false
  }
}

// mock-domain ledger: a landed Mock-space draft appends a NEW space
// row to this ledger (MockSpaceCreateDraftStrategy.land creates only — there
// is no space-rename write kind), so the page converges silently on the
// landing announcement. loadSpaces keeps the current filter/page as-is and
// carries no toast/confirm/jump on the happy path (BG iron rules 2-3); the
// subscription self-unhooks on unmount (single-source hook, R40/R57).
import { useObjectSync } from '@/composables/useObjectSync'

useObjectSync({
  watch: ['MOCK_SPACE_WRITE'],
  onSync: () => void loadSpaces(),
})

void loadSpaces()
</script>

<style scoped>
/* Pointer-spacing guard for the in-cell miss-policy select (see column note):
   leave a 16px right gap so the caret never crowds the actions column. */
.policy-select {
  width: calc(100% - 16px);
  margin-right: 16px;
}
.mock-spaces-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.serving-bar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; background: var(--el-color-info-light-9); color: var(--el-text-color-primary); }
.serving-label { flex: none; color: var(--el-text-color-secondary); font-size: 12px; }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.alias-sub { margin-left: 4px; font-size: 12px; }
.field-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.empty-block { padding: 24px 0; }
</style>
