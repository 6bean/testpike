<template>
  <!-- Edge-gap single source: the .page-root wrapper carries the theme 12px
       content padding; the card keeps its flush-inner shape as before. -->
  <div class="page-root">
  <div class="extensions-host page-card">
    <div class="host-head">
      <el-tabs v-model="pageTab" data-test="extensions-tabs" class="extensions-tabs">
        <el-tab-pane name="plugins">
          <template #label><span data-test="extensions-tab-plugins">{{ t('layout.plugins') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="mcp">
          <template #label><span data-test="extensions-tab-mcp">{{ t('layout.mcpServers') }}</span></template>
        </el-tab-pane>
      </el-tabs>
      <el-button
        text
        class="help-btn"
        :aria-label="t('extensionsHost.help')"
        @click="openHelp('G', pageTab === 'plugins' ? t('layout.leafHelpAnchor.plugins') : t('layout.leafHelpAnchor.mcpServers'))"
      >
        <el-icon><QuestionFilled /></el-icon>
      </el-button>
    </div>

    <!-- Plugin tab gate mirrors the retired route meta perm — the menu leaf
         carries no perm (that would narrow the MCP visible domain), so the
         key rides this in-page gate instead (canTraffic precedent). -->
    <PluginListView embedded v-if="pageTab === 'plugins' && canPlugin" />
    <McpServerView embedded v-else-if="pageTab === 'mcp'" />
    <div v-else-if="!canPlugin" class="empty-block" data-test="extensions-plugin-denied">
      <EmptyState :title="t('extensionsHost.pluginDenied')" :hint="t('extensionsHost.pluginDeniedHint')" />
    </div>
  </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AN-5 (menu IA second pass): extensions host. The industry-plugin list
 * and the MCP server management pages retired as menu leaves and merged into
 * this two-tab page. Old paths (/plugins, /mcp-servers) redirect here with
 * ?tab= preserved, and the tab state rides the URL (refresh/share/back stable).
 * The plugin tab keeps the old visibility semantics through the in-page perm
 * gate; MCP stays member-visible (backend McpAccessGuard is the authority).
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { QuestionFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import PluginListView from '@/views/plugin/PluginListView.vue'
import McpServerView from '@/views/mcp/McpServerView.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const canPlugin = computed(() => auth.hasPerm('perm:project:plugin-framework'))

type HostTab = 'plugins' | 'mcp'
const TABS: HostTab[] = ['plugins', 'mcp']

/** Query value wins on entry; an unknown tab (or the gated plugin tab
 *  without the permission) falls back to MCP instead of a blank shell. */
function tabFromQuery(): HostTab {
  const tab = route.query.tab
  const known = TABS.includes(tab as HostTab) ? (tab as HostTab) : 'mcp'
  return known === 'plugins' && !canPlugin.value ? 'mcp' : known
}
const pageTab = ref<HostTab>(tabFromQuery())

watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'mcp' ? undefined : tab } })
})
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})
</script>

<style scoped>
.extensions-host {
  display: flex;
  flex-direction: column;
  min-height: 320px;
}
.host-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.extensions-tabs {
  flex: 1;
}
.help-btn {
  flex-shrink: 0;
}
.empty-block {
  padding: 24px 0;
}
</style>
