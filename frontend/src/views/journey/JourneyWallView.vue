<!-- tp-sort-exempt: this wall is a navigation surface, not a data ledger — each
 row's only action is opening its journey plan. Journeys live under one
 program at a time (hand-authored plans, bounded set) and the page bridge
 ships no sort contract; a client-side sort over one page segment would present
 a false global order, which is worse than no sort at all.
 tp-column-config-exempt: all four columns are load-bearing for the jump —
 name is the link, owner states who signs the exit gate, status separates
 active from archived walls; hiding any of them removes either the entity or
 the safety context of the action, so column settings carry zero operational
 value here (R25 honest absence, same basis as the inventory navigation
 surfaces on record). -->
<template>
  <div class="page-root" data-test="journey-wall-page">
    <div class="page-card">
      <!-- Filter band (R21): conditions left with the inline query button last,
 page-level action (create) merged into the tail slot ahead of view
 state. The program picker is a query-parameter selector (R30
 exemption ②): journeys are walls of one program at a time and the
 program console is not shipped yet, so the choice is this
 page's filter, never a second global segment switch. -->
      <div class="filter-bar">
        <el-select
          v-model="programId"
          :placeholder="t('journeys.programPlaceholder')"
          style="width: 200px"
          filterable
          data-test="journey-program-select"
          @change="onProgramChange"
        >
          <el-option v-for="p in programs" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-input
          v-model="keyword"
          :placeholder="t('journeys.keywordPlaceholder')"
          clearable
          style="width: 220px"
          data-test="journey-keyword-input"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button data-test="journey-query" @click="search">
          <el-icon><Search /></el-icon>{{ t('journeys.query') }}
        </el-button>
        <div class="band-tail">
          <el-tooltip v-if="!programId" :content="t('journeys.createNeedProgram')" placement="top">
            <span>
              <el-button type="primary" disabled data-test="journey-create-disabled">
                <el-icon><Plus /></el-icon>{{ t('journeys.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button v-else type="primary" data-test="journey-create" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('journeys.create') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="4" data-test="journey-wall-skeleton" />
      <DataTablePage
        v-else
        :data="rows"
        row-key="id"
        :empty-title="t('journeys.emptyTitle')"
        :empty-hint="t('journeys.emptyHint')"
        data-test="journey-table"
      >
        <el-table-column :label="t('journeys.colName')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" :data-test="`journey-open-${row.id}`" @click="openJourney(row)">
              {{ row.name }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('journeys.colOwner')" width="180" align="center">
          <template #default="{ row }">
            <span :data-test="`journey-owner-${row.id}`">{{ ownerLabel(row.ownerUserId) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('journeys.colStatus')" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'" :data-test="`journey-status-${row.id}`">
              {{ row.status === 1 ? t('journeys.statusActive') : t('journeys.statusArchived') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('journeys.colAction')" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :data-test="`journey-plan-${row.id}`" @click="openJourney(row)">
              {{ t('journeys.openPlan') }}
            </el-button>
          </template>
        </el-table-column>
      </DataTablePage>
      <div class="pagination-bar" data-test="journey-pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="loadList"
        />
      </div>
    </div>

    <!-- Create dialog: the owning program is echoed read-only from the band
         selection (G4 single source — no second picker inside the form). -->
    <el-dialog v-model="createVisible" :title="t('journeys.createTitle')" width="480px" data-test="journey-create-dialog">
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('journeys.fieldName')">
          <el-input v-model="createForm.name" maxlength="120" show-word-limit data-test="journey-create-name" />
        </el-form-item>
        <el-form-item :label="t('journeys.colProgram')">
          <el-input :model-value="programName" readonly data-test="journey-create-program-echo" />
        </el-form-item>
        <el-form-item :label="t('journeys.colOwner')">
          <el-input :model-value="ownerLabel(currentUserId)" readonly data-test="journey-create-owner-echo" />
          <div class="form-hint">{{ t('journeys.ownerSignHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('journeys.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="creating"
          :disabled="!createForm.name.trim()"
          data-test="journey-create-submit"
          @click="submitCreate"
        >{{ t('journeys.createSubmit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * Journey wall : paged list of journey plans under one
 * program, with the worst-scale contract served by the core page bridge
 * (page/pageSize/keyword, total = whole filtered count). Creating a journey
 * lands straight on its segment-table plan page — a plan without segments is
 * the expected empty shape, and expansion needs at least one segment.
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import DataTablePage from '@/components/DataTablePage.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useAuthStore } from '@/stores/auth'
import { listPrograms, type ProgramView } from '@/api/program'
import {
  createJourney,
  pageJourneys,
  type JourneyView,
} from '@/api/journey'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const PAGE_SIZE = 20
const programs = ref<ProgramView[]>([])
const programId = ref<number>(Number(route.query.programId) || 0)
const keyword = ref<string>(String(route.query.keyword ?? ''))
const page = ref<number>(Number(route.query.page) || 1)
const pageSize = PAGE_SIZE
const rows = ref<JourneyView[]>([])
const total = ref(0)
const loading = ref(false)

const currentUserId = computed(() => auth.user?.userId ?? 0)
const programName = computed(() => programs.value.find((p) => p.id === programId.value)?.name ?? '')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '' })

/** Owner column shows the account number (user roster mapping is a later ledger note); the create dialog echoes it as the signer. */
function ownerLabel(userId: number): string {
  return t('journeys.ownerFormat', { id: userId })
}

function syncQuery() {
  router.replace({
    query: {
      ...(programId.value ? { programId: String(programId.value) } : {}),
      ...(keyword.value ? { keyword: keyword.value } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

async function loadList() {
  if (!programId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const view = await pageJourneys({
      programId: programId.value,
      page: page.value,
      pageSize,
      keyword: keyword.value || undefined,
    })
    rows.value = view.list
    total.value = view.total
  } catch (e) {
    // request wrapper already surfaces the server sentence; keep the table honestly empty.
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
    syncQuery()
  }
}

function search() {
  page.value = 1
  loadList()
}

function onProgramChange() {
  page.value = 1
  loadList()
}

function openJourney(row: JourneyView) {
  router.push(`/journeys/${row.id}`)
}

function openCreate() {
  createForm.name = ''
  createVisible.value = true
}

async function submitCreate() {
  const name = createForm.name.trim()
  if (!name || !programId.value) return
  creating.value = true
  try {
    const id = await createJourney({ programId: programId.value, name, ownerUserId: currentUserId.value })
    ElMessage.success(t('journeys.createdMsg', { name }))
    createVisible.value = false
    router.push(`/journeys/${id}`)
  } finally {
    creating.value = false
  }
}

// Deep links (/journeys?programId=…) survive reload: the URL is the single source of page state (R22).
watch(
  () => [route.query.programId, route.query.keyword, route.query.page],
  () => {
    const qProgram = Number(route.query.programId) || 0
    const qKeyword = String(route.query.keyword ?? '')
    const qPage = Number(route.query.page) || 1
    const reload =
      qProgram !== programId.value || qKeyword !== keyword.value || qPage !== page.value
    if (qProgram !== programId.value) programId.value = qProgram
    if (qKeyword !== keyword.value) keyword.value = qKeyword
    if (qPage !== page.value) page.value = qPage
    // A query-only navigation (deep link, browser back) must re-fetch: updating the
    // refs without reloading left the table showing the previous segment — the
    // "changed but nothing reacted" silence this page's contract forbids (R25).
    // Only when the URL differs from local state, so loadList's own syncQuery
    // (identical query) never re-triggers a fetch.
    if (reload) loadList()
  },
)

onMounted(async () => {
  // The programs fetch is only for the selector and the single-program default;
  // it must not gate the table (a slow /programs/page used to stall loadList).
  const programsLoad = listPrograms()
    .then((ps) => { programs.value = ps.filter((p) => p.state === 1) })
    .catch(() => { programs.value = [] })
  if (!programId.value) {
    await programsLoad
    if (programs.value.length === 1) programId.value = programs.value[0].id
  }
  await loadList()
})
</script>

<style scoped>
.form-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
