<!-- tp-subtitle-exempt: workbench detail page — the h2 carries the dynamic
 space name (legal entity-name channel) and the subtitle is its detail
 descriptor, exempt by the stage-3 exemption ledger, item 6. -->
<!--
 AN-4 mock space workbench: the per-space detail page hosting the
 docs / scenarios / recording / inspector surfaces as tabs (menu leaves of
 through the embedded + spaceId props ( process / coverage
 precedent — no markup copy, their filters and actions stay the single
 source). Tab state is URL-query authoritative: ?tab= lands on the tab,
 switching writes back (replace = no history spam). The traffic inspector
 keeps its own permission gate INSIDE the leaf: the route carries
 perm:mock:read only, so the tab renders solely with perm:mock:traffic.
 A space id missing from the loaded list resolves honestly to a plain-word
 empty state pointing back to the spaces list — never a guessed space.
-->
<template>
  <div class="page-root mock-workbench-page" data-test="mock-workbench-page">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h2 class="page-title">{{ spaceName }}<el-icon
              class="title-help-icon"
              :size="15"
              data-test="mock-workbench-help-btn"
              :title="t('mockWorkbench.help')"
              @click="openHelp('T', t('layout.leafHelpAnchor.mockSpaces'))"
            >
              <QuestionFilled />
            </el-icon></h2>
          <p class="page-subtitle">{{ t('mockWorkbench.subtitle') }}</p>
        </div>
        <!-- Screening vs doing separation ( law inherited by the new
 page): the action slot hosts no selectors and no help icon. -->
        <div class="header-actions">
          <el-button data-test="mock-workbench-back" @click="router.push('/mock/spaces')">
            {{ t('mockWorkbench.backToList') }}
          </el-button>
        </div>
      </div>

      <el-tabs v-model="pageTab" data-test="mock-workbench-tabs" class="mock-workbench-tabs">
        <el-tab-pane name="docs">
          <template #label><span data-test="mock-workbench-tab-docs">{{ t('mockWorkbench.tabDocs') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="scenarios">
          <template #label><span data-test="mock-workbench-tab-scenarios">{{ t('mockWorkbench.tabScenarios') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="recording">
          <template #label><span data-test="mock-workbench-tab-recording">{{ t('mockWorkbench.tabRecording') }}</span></template>
        </el-tab-pane>
        <el-tab-pane v-if="canTraffic" name="inspector">
          <template #label><span data-test="mock-workbench-tab-inspector">{{ t('mockWorkbench.tabInspector') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <div v-if="!projectId" class="empty-block" data-test="mock-workbench-no-project">
        <EmptyState :title="t('mockWorkbench.noProject')" />
      </div>
      <div v-else-if="spaceMissing" class="empty-block" data-test="mock-workbench-space-missing">
        <EmptyState
          :title="t('mockWorkbench.spaceNotFound')"
          :hint="t('mockWorkbench.spaceNotFoundHint')"
          :action-text="t('mockWorkbench.backToList')"
          @action="router.push('/mock/spaces')"
        />
      </div>

      <template v-else-if="spaceId">
        <MockDocsView v-if="pageTab === 'docs'" embedded :space-id="spaceId" />
        <MockScenarioListView v-else-if="pageTab === 'scenarios'" embedded :space-id="spaceId" />
        <MockRecordingView v-else-if="pageTab === 'recording'" embedded :space-id="spaceId" />
        <MockInspectorView v-else-if="pageTab === 'inspector' && canTraffic" embedded :space-id="spaceId" />
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import MockDocsView from './MockDocsView.vue'
import MockScenarioListView from './MockScenarioListView.vue'
import MockRecordingView from './MockRecordingView.vue'
import MockInspectorView from './MockInspectorView.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import { listSpaces, type MockSpaceItem } from '@/api/mock'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// AK single-source posture: the project rides the global header context via
// the shared composable (URL-priority chain), no local selector copy.
const { projectId } = useProjectPageContext()

// Traffic inspector gate mirrored from the retired route meta — a leaf-level
// menu perm must not silently widen the traffic surface.
const canTraffic = computed(() => auth.hasPerm('perm:mock:traffic'))

type WorkbenchTab = 'docs' | 'scenarios' | 'recording' | 'inspector'
const TABS: WorkbenchTab[] = ['docs', 'scenarios', 'recording', 'inspector']

/** Query value wins on entry; unknown keys (or the gated tab without the
 *  permission) fall back to the docs tab instead of rendering a blank shell. */
function tabFromQuery(): WorkbenchTab {
  const tab = route.query.tab
  const known = TABS.includes(tab as WorkbenchTab) ? tab as WorkbenchTab : 'docs'
  return known === 'inspector' && !canTraffic.value ? 'docs' : known
}
const pageTab = ref<WorkbenchTab>(tabFromQuery())

// Switching tabs writes the query back so any tab state is shareable and
// refresh-stable (AN rule: tab state rides the URL).
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'docs' ? undefined : tab } })
})
// External navigations into this page carrying ?tab= (retired-path redirect,
// ⌘K deep link) must move the visible tab, not just the URL.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})

/** Route param :id → validated numeric space id (an unparseable id renders
 *  the honest missing state instead of fetching with NaN). */
const spaceId = computed<number | null>(() => {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isInteger(id) && id > 0 ? id : null
})

const spaces = ref<MockSpaceItem[]>([])
const spacesLoaded = ref(false)
const selectedSpace = computed(() => spaces.value.find((s) => s.id === spaceId.value) ?? null)
const spaceName = computed(() => selectedSpace.value?.name ?? '')
const spaceMissing = computed(() => spacesLoaded.value && spaceId.value != null && !selectedSpace.value)

async function loadSpaces(): Promise<void> {
  if (!projectId.value) {
    spaces.value = []
    spacesLoaded.value = false
    return
  }
  try {
    spaces.value = await listSpaces(projectId.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockWorkbench.loadSpacesFailed')))
    spaces.value = []
  } finally {
    spacesLoaded.value = true
  }
}

watch(projectId, () => {
  void loadSpaces()
})
onMounted(() => {
  void loadSpaces()
})
</script>
