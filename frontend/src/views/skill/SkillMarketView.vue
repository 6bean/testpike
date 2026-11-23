<template>
  <div class="page-root" data-test="skill-market-page">
    <div :class="embedded ? 'skill-market-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-left">
          <!-- R23: page name lives on the top bar only; this market block is
 embedded as a tab, its duplicate h2 was retired . -->
          <span class="page-subtitle">{{ t('market.skillSubtitle') }}</span>
        </div>
        <div class="toolbar-right">
          <el-button type="primary" data-test="market-import-open" @click="openImport">
            <el-icon><Upload /></el-icon>{{ t('market.importSkill') }}
          </el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('market.searchSkillPh')"
          clearable
          style="width: 240px"
          data-test="market-keyword"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="query.type"
          :placeholder="t('market.type')"
          clearable
          style="width: 160px"
          popper-class="tp-market-type-popper"
          data-test="market-type-select"
          data-se1-exempt="closed enum SKILL_TYPE_KEYS, 5 fixed skill types"
          @change="reload"
        >
          <el-option v-for="opt in SKILL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select
          v-model="query.tagId"
          :placeholder="t('market.tags')"
          clearable
          filterable
          style="width: 200px"
          popper-class="tp-market-tag-popper"
          data-test="market-tag-select"
          @change="reload"
        >
          <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-button data-test="market-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('market.query') }}
        </el-button>
      </div>

      <TableSkeleton v-if="loading" :cols="7" data-test="skillmarketview-skeleton" />
      <el-table border
        v-else
        :data="list"
        row-key="id"
        data-test="market-table"
        :default-sort="{ prop: 'downloadCount', order: 'descending' }"
        @sort-change="onSortChange"
      >
        <el-table-column label="SKILL" min-width="220">
          <template #default="{ row }">
            <span class="skill-name">{{ row.name }}</span>
            <el-tag v-if="row.projectId == null" size="small" type="warning" style="margin-left: 6px">
              {{ t('market.systemLevel') }}
            </el-tag>
            <div class="skill-preview" data-test="market-preview">{{ row.bodyPreview || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="typeLabel" :label="t('market.type')" width="120" />
        <el-table-column :label="t('market.tags')" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="tag in row.tags || []" :key="tag" size="small" class="tag-chip">{{ tag }}</el-tag>
            <span v-if="!row.tags || row.tags.length === 0" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('market.author')" width="110">
          <template #default="{ row }">{{ row.authorName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="downloadCount" :label="t('market.downloads')" width="100" sortable="custom" align="right" data-test="col-downloads" />
        <el-table-column prop="refProjectCount" :label="t('market.refProjects')" width="110" sortable="custom" align="right" data-test="col-refprojects" />
        <el-table-column prop="call30dCount" :label="t('market.call30d')" width="110" sortable="custom" align="right" data-test="col-call30d" />
        <el-table-column :label="t('market.version')" width="80" align="center">
          <template #default="{ row }">v{{ row.currentVersion }}</template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="100" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onDownload(row, cmd as 'md' | 'zip')">
              <el-button link type="primary" data-test="market-download">{{ t('market.download') }}</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="md">{{ t('market.mdFile') }}</el-dropdown-item>
                  <el-dropdown-item command="zip">{{ t('market.zipPkg') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && list.length === 0" :title="t('market.skillEmptyTitle')" />

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="market-pagination"
          @current-change="load"
        />
      </div>
    </div>

    
    <FileUploadDialog
      v-model="importVisible"
      :title="t('market.importSkill')"
      accept=".md,.zip"
      :max-size-mb="50"
      :submit-text="t('market.submitImport')"
      :upload-fn="doImport"
      @uploaded="onImported"
    >
      <template #extra>
        <el-form label-width="100px">
          <el-form-item :label="t('market.ownerLabel')">
            <ProjectSelect
              v-if="authStore.isAdmin"
              v-model="importProjectId"
              :placeholder="t('market.ownerPhAdmin')"
              width="100%"
              data-test="market-import-project"
            />
            <ProjectSelect
              v-else
              v-model="importProjectId"
              :placeholder="t('market.ownerPhUser')"
              width="100%"
              data-test="market-import-project"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #tip>
        {{ t('market.skillTip') }}
      </template>
    </FileUploadDialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import FileUploadDialog from '@/components/FileUploadDialog.vue'
// Import-dialog project picker must be registered locally: the template
// references it (lines above), and an unresolved component emits a Vue
// warning whose console serialization touches window.sharedStorage, which
// Chromium reports as a DeprecationIssue and the E2E zero-tolerance gate
// fails (root cause of the skills-tab market issue).
import ProjectSelect from '@/components/ProjectSelect.vue'
import { getProjects } from '@/api/project'
import { SKILL_TYPE_KEYS } from '@/api/skillAdmin'
import { listTags, type TagItem } from '@/api/tags'
import {
  importSkill,
  marketDownload,
  marketList,
  type SkillMarketDownload,
  type SkillMarketItem,
  type SkillMarketQuery,
  type SkillView,
} from '@/api/skill'
import { useObjectSync } from '@/composables/useObjectSync'

/** AN-5 embedded flag: renders inside a host tab card when true (the
 * host already frames the content, so the outer card is dropped to avoid a
 * frame-in-frame). Default false keeps standalone semantics. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()
const authStore = useAuthStore()


const SKILL_TYPES = computed(() => Object.entries(SKILL_TYPE_KEYS).map(([value, key]) => ({ value: Number(value), label: t(key) })))


const SORT_KEY_MAP: Record<string, string> = {
  downloadCount: 'downloadCount',
  refProjectCount: 'refProjectCount',
  call30dCount: 'call30d',
}

const loading = ref(false)
const list = ref<SkillMarketItem[]>([])
const total = ref(0)
const tagOptions = ref<TagItem[]>([])

const query = reactive<SkillMarketQuery & { page: number; size: number }>({
  page: 1,
  size: 20,
  keyword: '',
  type: null,
  tagId: null,
  sortBy: 'downloadCount',
  sortDir: 'desc',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await marketList({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      type: query.type ?? undefined,
      tagId: query.tagId ?? undefined,
      sortBy: query.sortBy,
      sortDir: query.sortDir,
    })
    list.value = page.list || []
    total.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('market.skillLoadFailed'))
  } finally {
    loading.value = false
  }
}

// : market rows ride the same tp_skill ledger a landed skill
// draft converges to; re-read the current page silently (iron rules 2-3).
useObjectSync({
  watch: ['SKILL_WRITE'],
  onSync: () => void load(),
})

function reload(): void {
  query.page = 1
  void load()
}


function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    ElMessage.warning(t('market.sortUnsupported'))
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  query.page = 1
  void load()
}


async function loadTags(): Promise<void> {
  try {
    const page = await getProjects({ page: 1, size: 20, status: 1 })
    const seen = new Map<number, TagItem>()
    for (const p of (page.list || []).slice(0, 10)) {
      try {
        for (const tag of await listTags(p.id)) {
          if (!seen.has(tag.id)) seen.set(tag.id, tag)
        }
      } catch {
        
      }
    }
    tagOptions.value = [...seen.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  } catch {
    tagOptions.value = []
  }
}

async function onDownload(row: SkillMarketItem, format: 'md' | 'zip'): Promise<void> {
  try {
    const view: SkillMarketDownload = await marketDownload(row.id, format)
    const win = window.open(view.url, '_blank')
    if (!win) {
      ElMessage.warning(t('market.popBlocked'))
      return
    }
    ElMessage.success(t('market.downloaded', { file: view.fileName }))
    void load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('market.downloadFailed'))
  }
}

const importVisible = ref(false)
const importProjectId = ref<number | null>(null)

function openImport(): void {
  importProjectId.value = null
  importVisible.value = true
}

function doImport(file: File, onProgress: (p: number) => void): Promise<SkillView> {
  return importSkill(file, importProjectId.value, onProgress)
}

function onImported(res: unknown): void {
  const skill = res as SkillView
  ElMessage.success(t('market.imported', { name: skill.name }))
  void load()
}

onMounted(() => {
  void load()
  void loadTags()
})
</script>

<style scoped>
.skill-name {
  font-weight: 600;
}
.skill-preview {
  color: var(--tp-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-chip {
  margin-right: 4px;
}
.muted {
  color: var(--tp-text-secondary);
}
</style>
