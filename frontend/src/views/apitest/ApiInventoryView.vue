<!-- tp-sort-exempt: rows travel in contract order (method then path of the anchored version snapshot); the core bridge exposes no sort whitelist for this inventory and a client-only fake sort over a paged window would screen a lie. -->
<!-- tp-subtitle-exempt: the subtitle carries the ownership-rule echo "this list is the current project segment" (rule G clause 3) — segment semantics the top bar (project name only) cannot express. Kept by the stage-3 exemption ledger, item 1. -->
<!-- tp-column-config-exempt: the table is a navigation surface, not data browsing —
     every column is an action badge or deep link (freshness / requests / cases /
     mock / generate); hide-reorder per user would break "one row = one endpoint". -->
<template>
  <div class="page-root api-inventory-page" data-test="api-inventory-page">
    <div class="page-card">
      <!-- R23 stage-2: empty title shell removed — the page name rides the
           global topbar, and the retired title-side "?" now lives on the
           topbar help dot (data-test=header-help-btn). The project-slice echo
           and the review chip (dynamic data states, exemption class) stay as
           the first line of the card; the variant actions moved into the
           filter-bar leading slot (R23 law 5, Ant-Pro toolbar shape). -->
      <p class="page-subtitle" data-test="api-inventory-subtitle">
        <template v-if="ctx.primaryName">
          {{ t('apiInventory.pageSubtitle', { project: ctx.primaryName }) }}
        </template>
        <template v-else>{{ t('apiInventory.pageSubtitleNoProject') }}</template>
      </p>
      <!-- Plan-exit hint slot : pending
 contract change-review count rides along as a non-blocking glow chip —
 it never changes the freshness badges and never blocks anything; the
 queue truth stays in core, this chip only reads the status=0 segment. -->
      <el-tag
        v-if="reviewPending > 0"
        type="warning"
        effect="light"
        class="review-chip"
        data-test="api-inventory-review-chip"
        @click="gotoReviewQueue"
      >
        {{ t('apiInventory.reviewQueueChip', { n: reviewPending }) }}
      </el-tag>

      <div v-if="!projectId" class="empty-block" data-test="api-inventory-no-project">
        <EmptyState :title="t('apiInventory.noProject')" :hint="t('apiInventory.noProjectHint')" />
      </div>

      <template v-else>
        <div class="filter-bar" data-test="api-inventory-filter-bar">
          <!-- R23 stage-2 (law 5): variant actions moved from the retired title
               shell into the filter-bar leading slot (Ant-Pro toolbar shape —
               actions left, screening center, view-state actions trailing).
               Contract entrances retired from the document page live here
               (one surface): upload + URL pull, both behind the requirement
               write gate (admin or project owner; the backend double-gate
               stays authoritative — this only hides what would be rejected). -->
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button
                v-if="canWrite"
                :icon="Upload"
                type="primary"
                data-test="api-inventory-upload-btn"
                :disabled="!projectId"
                @click="specUploadVisible = true"
              >
                {{ t('apiInventory.upload') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button
                v-if="canWrite"
                :icon="Link"
                data-test="api-inventory-url-btn"
                :disabled="!projectId"
                @click="urlPullVisible = true"
              >
                {{ t('apiInventory.urlPull') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- version compare needs a picked contract chain with >=2 versions;
               the count check happens after the history load (honest momentary
               guidance instead of a stale, disabled guess on the header) -->
          <el-button
            :icon="Switch"
            :disabled="!pickedSpecName"
            :loading="compareLoading"
            data-test="api-inventory-compare-btn"
            @click="openCompare"
          >
            {{ t('apiInventory.compareBtn') }}
          </el-button>
          <!-- SE rule 1: contract names can exceed a dozen — filterable, remote-friendly shape.
               Anchor version is core single-source (max of the name chain), shown read-only
               on the coverage card, never re-picked here (bridge contract takes specName only). -->
          <el-select
            v-model="pickedSpecName"
            :placeholder="t('apiInventory.filterSpec')"
            filterable
            clearable
            :loading="specsLoading"
            style="width: 260px"
            data-test="api-inventory-filter-spec"
            @change="applyFilter"
          >
            <el-option v-for="s in specNames" :key="s.name" :label="s.name" :value="s.name" />
          </el-select>
          <!-- SE rule 2: the working-list switch is a visible active chip — no
               invisible filter. Clicking the red uncovered number on the card
               turns it on; the chip closes it again. -->
          <el-tag
            v-if="uncoveredOnly"
            closable
            type="warning"
            size="large"
            data-test="api-inventory-uncovered-chip"
            @close="toggleUncovered(false)"
          >
            {{ t('apiInventory.uncoveredChip') }}
          </el-tag>
          <div class="filter-bar-actions">
            <el-button :icon="Refresh" :loading="loading" data-test="api-inventory-refresh" @click="reload">
              {{ t('apiInventory.refresh') }}
            </el-button>
          </div>
        </div>

        <!-- coverage card: denominators are core judgments, displayed only -->
        <div v-if="coverage && pickedSpecName" class="coverage-card" data-test="api-inventory-coverage">
          <div class="cov-item">
            <div class="cov-num" data-test="api-inventory-cov-total">{{ coverage.total }}</div>
            <div class="cov-label">{{ t('apiInventory.covTotal') }}</div>
          </div>
          <div class="cov-item">
            <div class="cov-num">{{ coverage.endpointsWithRequests }}</div>
            <div class="cov-label">{{ t('apiInventory.covWithRequests') }}</div>
          </div>
          <div class="cov-item">
            <!-- the working-list entry: clicking the red number cuts the table
 to exactly the rows this count covers (server-side segment) -->
            <el-button
              class="cov-num cov-num-warn cov-num-btn"
              text
              data-test="api-inventory-uncovered-toggle"
              :title="t('apiInventory.viewUncovered')"
              @click="toggleUncovered(true)"
            ><span data-test="api-inventory-cov-uncovered">{{ coverage.uncoveredCount }}</span></el-button>
            <div class="cov-label">{{ t('apiInventory.covUncovered') }}</div>
          </div>
          <div class="cov-item">
            <div class="cov-num cov-num-accent" data-test="api-inventory-cov-percent">{{ coverage.coveragePercent }}%</div>
            <div class="cov-label">{{ t('apiInventory.covPercent') }}</div>
          </div>
          <div class="cov-anchor" data-test="api-inventory-cov-anchor">
            {{ t('apiInventory.covAnchor', { name: coverage.specName, version: coverage.specVersion }) }}
          </div>
        </div>

        <TableSkeleton v-if="loading && !rows.length" :cols="7" data-test="api-inventory-skeleton" />
        <div v-else-if="!rows.length && !loading" class="empty-block" data-test="api-inventory-empty">
          <EmptyState :title="t('apiInventory.empty')" :hint="t('apiInventory.emptyHint')" />
        </div>

        <template v-else>
          <el-table border :data="rows" data-test="api-inventory-table" row-key="endpointKey">
            <el-table-column :label="t('apiInventory.colEndpoint')" min-width="300">
              <template #default="{ row }">
                <div class="endpoint-cell">
                  <span class="mono method-tag" :class="`m-${row.method.toLowerCase()}`">{{ row.method }}</span>
                  <span class="mono path-text" data-test="api-inventory-path">
                    <span v-for="(seg, i) in splitPath(row.path)" :key="i" :class="{ 'path-var': seg.var }">{{ seg.text }}</span>
                  </span>
                  <el-tag v-if="row.deprecated" size="small" type="info" data-test="api-inventory-deprecated-tag">
                    {{ t('apiInventory.tagDeprecated') }}
                  </el-tag>
                </div>
                <div v-if="row.title" class="mono muted title-line">{{ row.title }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiInventory.colFreshness')" width="130" align="center">
              <template #default="{ row }">
                <span class="fresh-tag" :class="`fresh-${row.freshness}`" data-test="api-inventory-freshness">
                  {{ freshnessLabel(row.freshness) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiInventory.colRequests')" width="120" align="center">
              <template #default="{ row }">
                <!-- deep link: the request sandbox door filtered to this endpoint path -->
                <el-button
                  class="asset-num asset-link"
                  text
                  data-test="api-inventory-req-jump"
                  :title="t('apiInventory.jumpToRequests')"
                  @click="jumpToAsset('requests', row.path)"
                >{{ row.requestsCreated }}</el-button>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiInventory.colCases')" width="120" align="center">
              <template #default="{ row }">
                <el-button
                  class="asset-num asset-link"
                  text
                  data-test="api-inventory-case-jump"
                  :title="t('apiInventory.jumpToCases')"
                  @click="jumpToAsset('cases', row.path)"
                >{{ row.casesPromoted }}</el-button>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiInventory.colMock')" width="170" align="center">
              <template #default="{ row }">
                <!-- R-8: mock provenance is core-flagged (mockCovered / mockAiFallbackOnly);
                     the page renders the twin badges and never re-derives a second ledger.
                     Every mock badge jumps to the serving-inventory door pre-filtered by
                     the endpoint path; the entry is disabled with plain-language guidance
                     when the caller lacks the mock read permission (G-group). -->
                <el-tooltip
                  v-if="!canMockRead"
                  :content="t('apiInventory.jumpNoMockPerm')"
                  placement="top"
                >
                  <el-button
                    class="mock-jump"
                    text
                    disabled
                    data-test="api-inventory-mock-jump-disabled"
                  >
                    <span v-if="!row.mockCovered" class="muted">{{ t('apiInventory.mockNone') }}</span>
                    <span v-else-if="row.mockAiFallbackOnly" class="llm-tag">{{ t('apiInventory.mockAiFallback') }}</span>
                    <el-tag v-else size="small" type="success">{{ t('apiInventory.mockCovered') }}</el-tag>
                  </el-button>
                </el-tooltip>
                <el-button
                  v-else
                  class="mock-jump"
                  text
                  data-test="api-inventory-mock-jump"
                  :title="t('apiInventory.jumpToMock')"
                  @click="jumpToAsset('mock', row.path)"
                >
                  <span v-if="!row.mockCovered" class="muted" data-test="api-inventory-mock-none">{{ t('apiInventory.mockNone') }}</span>
                  <span v-else-if="row.mockAiFallbackOnly" class="llm-tag" data-test="api-inventory-mock-llm">
                    {{ t('apiInventory.mockAiFallback') }}
                  </span>
                  <el-tag v-else size="small" type="success" data-test="api-inventory-mock-covered">{{ t('apiInventory.mockCovered') }}</el-tag>
                </el-button>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiInventory.colGenerate')" width="110" align="center">
              <template #default="{ row }">
                <!-- suggestion-to-generate entrance moved along with the retired
                     contract panel: serialize this endpoint from the anchored
                     version snapshot (existing history chain, zero new endpoint)
                     and inject it into the AI generation requirement. -->
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :loading="generatingKey === row.endpointKey"
                  data-test="api-inventory-gen-btn"
                  @click="goGenerate(row)"
                >{{ t('apiInventory.genBtn') }}</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-bar" data-test="api-inventory-pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :total="total"
              :page-sizes="[20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              background
              data-test="api-inventory-pagination"
              @current-change="loadRows"
              @size-change="applyFilter"
            />
          </div>
        </template>
      </template>

      <!-- Version-compare drawer ( stage 1): pick two versions of the
 picked contract chain, see added/changed/removed endpoints. Changed
 rows expand into a side-by-side field-level schema panel — parsed
 rows, never a raw JSON dump; unparsable schemas degrade to a folded
 raw text honestly labeled. -->
      <el-drawer
        v-model="compareVisible"
        :title="t('apiInventory.compareTitle')"
        size="72%"
        data-test="api-inventory-compare-drawer"
      >
        <div class="compare-pick" data-test="api-inventory-compare-pick">
          <span class="pick-label">{{ t('apiInventory.comparePrev') }}</span>
          <el-select v-model="prevPickId" style="width: 220px" data-test="api-inventory-compare-prev" filterable>
            <el-option
              v-for="s in compareChain"
              :key="'p' + s.id"
              :label="`v${s.specVersion} · ${s.createdAt}`"
              :value="s.id"
            />
          </el-select>
          <span class="pick-label">{{ t('apiInventory.compareNext') }}</span>
          <el-select v-model="nextPickId" style="width: 220px" data-test="api-inventory-compare-next" filterable>
            <el-option
              v-for="s in compareChain"
              :key="'n' + s.id"
              :label="`v${s.specVersion} · ${s.createdAt}`"
              :value="s.id"
            />
          </el-select>
        </div>

        <template v-if="compareModel">
          <p class="compare-source" data-test="api-inventory-compare-source">
            {{ compareModel.source === 'core-ledger' ? t('apiInventory.compareSourceLedger') : t('apiInventory.compareSourceDerived') }}
          </p>
          <div class="compare-counts" data-test="api-inventory-compare-counts">
            <span class="cc cc-added">{{ t('apiInventory.compareAdded') }} {{ compareModel.added.length }}</span>
            <span class="cc cc-changed">{{ t('apiInventory.compareChanged') }} {{ compareModel.changed.length }}</span>
            <span class="cc cc-removed">{{ t('apiInventory.compareRemoved') }} {{ compareModel.removed.length }}</span>
          </div>

          <el-collapse v-if="compareModel.changed.length" v-model="expandedChanged" data-test="api-inventory-compare-collapse">
            <el-collapse-item
              v-for="row in compareModel.changed"
              :key="row.endpointKey"
              :name="row.endpointKey"
            >
              <template #title>
                <span class="mono method-tag" :class="`m-${row.method.toLowerCase()}`">{{ row.method }}</span>
                <span class="mono path-text compare-key">{{ row.endpointKey }}</span>
              </template>
              <div class="diff-cols">
                <div class="diff-col" data-test="api-inventory-diff-prev">
                  <h4>{{ t('apiInventory.compareSidePrev', { version: comparePrev?.specVersion }) }}</h4>
                  <SpecSchemaFieldsPanel :endpoint="row.prev" />
                </div>
                <div class="diff-col" data-test="api-inventory-diff-next">
                  <h4>{{ t('apiInventory.compareSideNext', { version: compareNext?.specVersion }) }}</h4>
                  <SpecSchemaFieldsPanel :endpoint="row.next" />
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>

          <div v-if="compareModel.added.length" class="compare-list">
            <h4 class="cmp-h added">{{ t('apiInventory.compareAdded') }}</h4>
            <p v-for="row in compareModel.added" :key="'a' + row.endpointKey" class="cmp-row mono" data-test="api-inventory-compare-added-row">
              {{ row.endpointKey }}
            </p>
          </div>
          <div v-if="compareModel.removed.length" class="compare-list">
            <h4 class="cmp-h removed">{{ t('apiInventory.compareRemoved') }}</h4>
            <p v-for="row in compareModel.removed" :key="'r' + row.endpointKey" class="cmp-row mono muted">{{ row.endpointKey }}</p>
          </div>
          <div
            v-if="!compareModel.added.length && !compareModel.changed.length && !compareModel.removed.length"
            class="cmp-none"
            data-test="api-inventory-compare-none"
          >
            {{ t('apiInventory.compareNoDiff') }}
          </div>
        </template>
        <p v-else-if="!compareModel && compareChain.length < 2" class="cmp-none">{{ t('apiInventory.compareNeedTwo') }}</p>
      </el-drawer>

      <!-- Contract upload (unified upload dialog, same posture as the retired
           document-page entrance): name = file name without extension, same-name
           re-upload = version chain, identical content = backend 409 surfaced
           inline by the dialog. -->
      <FileUploadDialog
        v-model="specUploadVisible"
        :title="t('apiInventory.upload')"
        :accept="SPEC_ACCEPT"
        :max-size-mb="SPEC_MAX_SIZE_MB"
        :tip="t('uploadDialog.specTip')"
        :submit-text="t('apiInventory.upload')"
        :upload-fn="doSpecUpload"
        @uploaded="onSpecUploaded"
      >
        <template #extra>
          <!-- ownership echo (iron rule 1): the uploaded contract versions land on
 the header-context project segment; read-only, single-sourced -->
          <ProjectOwnerEcho />
        </template>
      </FileUploadDialog>

      <!-- URL pull: SSRF whitelist/timeout/size gates are core-side single
           source; failures arrive as 400 plain language and render inline. -->
      <el-dialog
        v-model="urlPullVisible"
        :title="t('apiInventory.urlPull')"
        width="560px"
        data-test="api-inventory-url-dialog"
      >
        <!-- ownership echo (iron rule 1): pulled specs land on the header-context
 project segment; read-only, single-sourced -->
        <ProjectOwnerEcho />
        <el-form @submit.prevent>
          <el-form-item :label="t('apiInventory.urlPullName')">
            <el-input
              v-model="urlPullName"
              :placeholder="t('apiInventory.urlPullNameHint')"
              maxlength="120"
              data-test="api-inventory-url-name"
            />
          </el-form-item>
          <el-form-item :label="t('apiInventory.urlPullUrl')">
            <el-input
              v-model="urlPullUrl"
              placeholder="https://petstore.swagger.io/v2/swagger.json"
              maxlength="500"
              data-test="api-inventory-url-input"
            />
          </el-form-item>
          <p v-if="urlPullError" class="url-pull-error" data-test="api-inventory-url-error">{{ urlPullError }}</p>
        </el-form>
        <template #footer>
          <el-button data-test="api-inventory-url-cancel" @click="urlPullVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button
            type="primary"
            :loading="urlPulling"
            :disabled="!urlPullName.trim() || !urlPullUrl.trim()"
            data-test="api-inventory-url-submit"
            @click="submitUrlPull"
          >{{ t('apiInventory.urlPull') }}</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * API inventory page (AH batch — the contract-endpoint ledger of one
 * project: every endpoint of the anchored (latest) version of a chosen OpenAPI
 * contract name, each row carrying truthful asset counts (requests created from
 * it, cases promoted), the mock coverage twin-badge (deterministic vs
 * AI-fallback-unverified, R-8) and the freshness verdict (stable/changed/removed).
 *
 * What it does: pick a contract, read which endpoints have no test asset yet
 * (uncoveredCount on the summary card is the working list — the coverage
 * denominator excludes deprecated endpoints so the number stays honest).
 * Deep-linking from badges to request/case/mock detail doors is ; this
 * segment is display-only by design.
 *
 * Single-source rules honored here: project segment = AK global header context
 * (no in-page selector, posture); anchor-version and all counts are core
 * judgments (bridge passes fields through, nothing recomputed in the browser);
 * badges render the freshness/mock enums exactly as served.
 */
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Switch, Upload, Link } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import SpecSchemaFieldsPanel from '@/components/SpecSchemaFieldsPanel.vue'
import FileUploadDialog from '@/components/FileUploadDialog.vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import { useProjectContextStore } from '@/stores/projectContext'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import {
  endpointSuggestionText,
  fetchApiSpecByUrl,
  listApiSpecHistory,
  listApiSpecs,
  uploadApiSpec,
  type ApiSpecView,
} from '@/api/apiSpec'
import { buildVersionCompare, type CompareModel } from '@/utils/specCompare'
import { listSpecChangeAnalyses } from '@/api/specChangeAnalysis'
import {
  getInventoryCoverage,
  pageInventoryEndpoints,
  type InventoryCoverageSummary,
  type InventoryEndpointItem,
} from '@/api/apiInventory'

const { t } = useI18n()
const router = useRouter()
const { projectId } = useProjectPageContext()
const ctx = useProjectContextStore()
const auth = useAuthStore()

const rows = ref<InventoryEndpointItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)

/** Pending contract change-review count for the hint chip (null-safe: a failed
 *  read hides the chip rather than showing a wrong number). */
const reviewPending = ref(0)

/** Working-list switch: cuts the page window server-side to the endpoints
 * in the coverage denominator nobody has an asset for yet (the same honest
 * segment the summary card counts — deprecated/removed rows never appear).
 * Kept visible through a chip while active (SE rule 2: no invisible filter). */
const uncoveredOnly = ref(false)

/** Mock door mirrors the /mock/endpoints route meta.perm (menu/route code
 *  single-sourced): a jump that the guard would bounce stays disabled with
 *  plain-language guidance instead (G-group posture for gated entries). */
const canMockRead = computed(() => auth.hasPerm('perm:mock:read'))

/** Write gate for the migrated contract entrances: mirrors the requirement
 *  write surface exactly as the retired document panel did (admin or project
 *  owner role=2); the backend double-gate remains authoritative. */
const canWrite = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})

// ── Contract upload (entrance migrated off the document page) ─────────────
const SPEC_ACCEPT = '.json,.yaml,.yml'
const SPEC_MAX_SIZE_MB = 5
const specUploadVisible = ref(false)

async function doSpecUpload(file: File, onProgress: (p: number) => void): Promise<ApiSpecView> {
  if (!projectId.value) throw new Error(t('apiInventory.noProject'))
  // contract name = file name without extension; same-name re-upload extends
  // the version chain, identical content = backend 409 shown inline
  const name = file.name.replace(/\.(ya?ml|json)$/i, '') || file.name
  return uploadApiSpec(projectId.value, name, file, onProgress)
}

function onSpecUploaded(res: unknown): void {
  const spec = res as ApiSpecView
  ElMessage.success(t('apiInventory.uploadOk', { name: spec.name, version: spec.specVersion, n: spec.endpoints?.length || 0 }))
  // the new chain becomes selectable immediately; if it is the picked name,
  // the table re-cuts against the freshly anchored version
  void reload()
}

// ── URL pull (SSRF/timeout/size gates are core-side single source) ─────────
const urlPullVisible = ref(false)
const urlPulling = ref(false)
const urlPullName = ref('')
const urlPullUrl = ref('')
const urlPullError = ref('')

async function submitUrlPull(): Promise<void> {
  if (!projectId.value) return
  urlPulling.value = true
  urlPullError.value = ''
  try {
    const spec = await fetchApiSpecByUrl(projectId.value, urlPullName.value.trim(), urlPullUrl.value.trim())
    ElMessage.success(t('apiInventory.uploadOk', { name: spec.name, version: spec.specVersion, n: spec.endpoints?.length || 0 }))
    urlPullVisible.value = false
    urlPullName.value = ''
    urlPullUrl.value = ''
    void reload()
  } catch (e: unknown) {
    urlPullError.value = bizErrorMsg(e, t('apiInventory.urlPullFail'))
  } finally {
    urlPulling.value = false
  }
}

// ── Per-row generate (suggestion injection travels with the retired panel) ─
// The anchored version snapshot of the picked chain comes from the existing
// history endpoint (zero new backend): find the row's endpoint inside it and
// serialize it into /ai/generate?requirement= (A3 direct-link precedent).
const generatingKey = ref<string | null>(null)

async function goGenerate(row: InventoryEndpointItem): Promise<void> {
  if (!projectId.value || !pickedSpecName.value) return
  generatingKey.value = row.endpointKey
  try {
    const chain = await listApiSpecHistory(projectId.value, pickedSpecName.value)
    const anchored = chain.length ? chain[chain.length - 1] : null
    const ep = anchored?.endpoints?.find((e) => e.endpointKey === row.endpointKey)
    if (!anchored || !ep) {
      ElMessage.warning(t('apiInventory.genNoSnapshot'))
      return
    }
    void router.push({
      path: '/ai/generate',
      query: { projectId: String(projectId.value), requirement: endpointSuggestionText(anchored, ep) },
    })
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('apiInventory.genLoadFailed')))
  } finally {
    generatingKey.value = null
  }
}

const specNames = ref<{ name: string; latestVersion: number }[]>([])
const specsLoading = ref(false)
const pickedSpecName = ref<string | null>(null)
const coverage = ref<InventoryCoverageSummary | null>(null)

/** Contract name picker: page 1 of the project spec list carries every name
 *  chain (list endpoint is name-deduped latest rows; the pick is just the
 *  anchor-name feed — version stays core-side). History is loaded per pick to
 *  keep the picker honest about chains the user actually has. */
async function loadSpecNames(): Promise<void> {
  if (!projectId.value) {
    specNames.value = []
    return
  }
  specsLoading.value = true
  try {
    const res = await listApiSpecs({ projectId: projectId.value, page: 1, size: 200 })
    specNames.value = res.list.map((s) => ({ name: s.name, latestVersion: s.specVersion }))
    if (!pickedSpecName.value && res.list.length > 0) {
      pickedSpecName.value = res.list[0].name
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apiInventory.loadSpecsFailed')))
    specNames.value = []
  } finally {
    specsLoading.value = false
  }
}

async function loadRows(): Promise<void> {
  if (!projectId.value || !pickedSpecName.value) {
    rows.value = []
    total.value = 0
    coverage.value = null
    return
  }
  loading.value = true
  try {
    const [res, cov] = await Promise.all([
      pageInventoryEndpoints(projectId.value, pickedSpecName.value, page.value, pageSize.value, uncoveredOnly.value),
      getInventoryCoverage(projectId.value, pickedSpecName.value),
    ])
    rows.value = res.list
    total.value = res.total
    coverage.value = cov
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apiInventory.loadFailed')))
    rows.value = []
    total.value = 0
    coverage.value = null
  } finally {
    loading.value = false
  }
}

function applyFilter(): void {
  page.value = 1
  void loadRows()
}

/** Working-list toggle: re-cuts the window server-side, page back to 1. */
function toggleUncovered(v: boolean | string | number): void {
  uncoveredOnly.value = Boolean(v)
  applyFilter()
}

/**
 * Badge jump ( stage 2): every asset count on a row opens its door
 * pre-filtered by the endpoint path. Deep links reuse the destination pages'
 * existing keyword params (?projectId + ?keyword) — AG §12a-4 ruling, no new
 * query grammar and no route aliases. The path template carries `{id}` braces
 * and slashes: keyword search on the destinations is substring-based, so the
 * raw path prefills honestly.
 */
function jumpToAsset(door: 'cases' | 'requests' | 'mock', path: string): void {
  if (!projectId.value) return
  if (door === 'mock' && !canMockRead.value) return
  const query = { projectId: String(projectId.value), keyword: path }
  if (door === 'cases') void router.push({ path: '/cases', query })
  else if (door === 'requests') void router.push({ path: '/api-test', query })
  else void router.push({ path: '/mock/endpoints', query })
}

/** Review-queue deep link (plan-exit hint chip): the change-analysis page of
 * the same project segment; ?projectId is consumed by useProjectPageContext's
 * priority chain, so the jump lands with the right segment (no white jump). */
function gotoReviewQueue(): void {
  if (!projectId.value) return
  void router.push({ path: '/api-spec-change-analysis', query: { projectId: String(projectId.value) } })
}

async function reload(): Promise<void> {
  await loadSpecNames()
  await loadRows()
  await refreshReviewPending()
}

/** Hint-chip count = the WHOLE pending review queue of the project segment
 * (status=0 server-side segment, size=1 — only the total is read; the queue
 * truth stays in core, this never re-derives a second ledger). */
async function refreshReviewPending(): Promise<void> {
  if (!projectId.value) {
    reviewPending.value = 0
    return
  }
  try {
    const res = await listSpecChangeAnalyses({ projectId: projectId.value, status: 0, page: 1, size: 1 })
    reviewPending.value = res.total || 0
  } catch {
    reviewPending.value = 0
  }
}

/** Template segments ({id}) get their own accent so path variables read at a
 *  glance against literal segments — display-only split, zero re-derivation. */
function splitPath(path: string): { text: string; var: boolean }[] {
  return path.split(/(\/)/).filter((s) => s.length > 0).map((seg) => {
    if (seg === '/') return { text: '/', var: false }
    return { text: seg, var: seg.startsWith('{') && seg.endsWith('}') }
  })
}

function freshnessLabel(f: InventoryEndpointItem['freshness']): string {
  if (f === 'changed') return t('apiInventory.freshChanged')
  if (f === 'removed') return t('apiInventory.freshRemoved')
  return t('apiInventory.freshStable')
}

// ── Version compare drawer ( stage 1) ──────────────────────────────
// Data plane = the existing GET /api-specs/history chain of the picked
// contract name (zero new endpoints). Default pick = latest vs previous.
// The two-side join and the added/changed/removed triple run through
// utils/specCompare: an adjacent pair renders the core diffSummary ledger
// verbatim, any other pair is labeled as derived from the two snapshots.
const compareVisible = ref(false)
const compareLoading = ref(false)
const compareChain = ref<ApiSpecView[]>([])
const prevPickId = ref<number | null>(null)
const nextPickId = ref<number | null>(null)
const expandedChanged = ref<string[]>([])

const comparePrev = computed(() => compareChain.value.find((s) => s.id === prevPickId.value) ?? null)
const compareNext = computed(() => compareChain.value.find((s) => s.id === nextPickId.value) ?? null)

const compareModel = computed<CompareModel | null>(() => {
  if (!comparePrev.value || !compareNext.value || comparePrev.value.id === compareNext.value.id) return null
  return buildVersionCompare(comparePrev.value, compareNext.value)
})

async function openCompare(): Promise<void> {
  if (!projectId.value || !pickedSpecName.value) return
  compareLoading.value = true
  try {
    compareChain.value = await listApiSpecHistory(projectId.value, pickedSpecName.value)
    if (compareChain.value.length >= 2) {
      // chain arrives version-ascending; default = latest vs its predecessor
      const latest = compareChain.value[compareChain.value.length - 1]
      const prev = compareChain.value[compareChain.value.length - 2]
      nextPickId.value = latest.id
      prevPickId.value = prev.id
      expandedChanged.value = []
    } else {
      prevPickId.value = null
      nextPickId.value = null
    }
    compareVisible.value = true
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apiInventory.compareLoadFailed')))
  } finally {
    compareLoading.value = false
  }
}

// Project segment follows the header context: a switch re-seeds the name picker
// and clears the pick (the chain of another project must never linger).
watch(projectId, () => {
  pickedSpecName.value = null
  uncoveredOnly.value = false
  void reload()
}, { immediate: true })

// (apitest segment): a landed contract pull appends a spec version plus
// its endpoint rows — the inventory re-reads in place with the current page
// and filters, silently (iron rules 1-2; the spec ledger is exactly what this
// page shows).
useObjectSync({
  watch: ['APISPEC_WRITE'],
  onSync: () => void reload(),
})
</script>

<style scoped>
.api-inventory-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.review-chip { margin-top: 6px; cursor: pointer; }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.empty-block { padding: 24px 0; }
.coverage-card { display: flex; align-items: center; gap: 28px; flex-wrap: wrap; padding: 12px 18px; margin-bottom: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: var(--tp-radius, 10px); background: var(--el-fill-color-lighter); }
.cov-item { display: flex; flex-direction: column; gap: 2px; }
.cov-num { font-size: 20px; font-weight: 600; }
.cov-num-warn { color: var(--tp-danger, var(--el-color-danger)); }
.cov-num-accent { color: var(--tp-primary, var(--el-color-primary)); }
.cov-label { font-size: 12px; color: var(--el-text-color-secondary); }
.cov-anchor { margin-left: auto; font-size: 12px; color: var(--el-text-color-secondary); }
.endpoint-cell { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.path-text { font-size: 13px; font-weight: 600; }
.path-var { color: var(--tp-primary, var(--el-color-primary)); font-weight: 600; }
.title-line { margin-top: 4px; }
.method-tag { padding: 2px 8px; border-radius: 4px; background: var(--el-color-info-light-8); font-weight: 600; }
.method-tag.m-get { background: var(--el-color-success-light-8); color: var(--el-color-success); }
.method-tag.m-post { background: var(--el-color-warning-light-8); color: var(--el-color-warning); }
.method-tag.m-put { background: var(--el-color-primary-light-8); color: var(--el-color-primary); }
.method-tag.m-patch { background: var(--el-color-primary-light-8); color: var(--el-color-primary); }
.method-tag.m-delete { background: var(--el-color-danger-light-8); color: var(--el-color-danger); }
/* freshness = halo tags (no side bars, no dialogs — 10-10 ruling family):
   stable stays plain, changed warns with a tinted halo, removed halos danger. */
.fresh-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.fresh-stable { color: var(--el-text-color-secondary); }
.fresh-changed { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.fresh-removed { background: var(--tp-danger-weak, var(--el-color-danger-light-9)); color: var(--tp-danger, var(--el-color-danger)); }
.asset-num { font-weight: 600; }
/* badge deep links: counts read as numbers, jump as buttons (text style
   keeps the weight/size of the ledger — the door shows on hover) */
.asset-link { font-size: inherit; }
.cov-num-btn { font-size: 20px; height: auto; padding: 0; }
.mock-jump { padding: 0; }
/* AI-fallback provenance purple tier (single token family from theme.scss):
   an all-LLM mock never pretends to be deterministic coverage. */
.llm-tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; background: var(--tp-llm-weak); color: var(--tp-llm); }
/* version-compare drawer layout: pick row, ledger counts, two-column diff */
.compare-pick { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.pick-label { font-size: 12px; color: var(--el-text-color-secondary); }
.compare-source { margin: 0 0 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.compare-counts { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 12px; }
.cc { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 4px; }
.cc-added { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.cc-changed { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.cc-removed { background: var(--tp-danger-weak, var(--el-color-danger-light-9)); color: var(--tp-danger, var(--el-color-danger)); }
.diff-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.diff-col { min-width: 0; }
.diff-col h4 { margin: 0 0 6px; font-size: 12px; color: var(--el-text-color-secondary); }
.compare-key { margin-left: 8px; }
.cmp-h { margin: 12px 0 4px; font-size: 12px; }
.cmp-h.added { color: var(--el-color-success); }
.cmp-h.removed { color: var(--tp-danger, var(--el-color-danger)); }
.cmp-row { margin: 2px 0; font-size: 12px; }
.cmp-none { font-size: 12px; color: var(--el-text-color-secondary); padding: 8px 0; }
.url-pull-error { margin: 0; font-size: 13px; color: var(--tp-danger, var(--el-color-danger)); }
@media (max-width: 1100px) {
  .diff-cols { grid-template-columns: 1fr; }
}
</style>
