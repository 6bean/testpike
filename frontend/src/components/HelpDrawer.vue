<template>
 
  <el-drawer
    :model-value="visible"
    :with-header="true"
    :append-to-body="false"
    size="72%"
    class="help-drawer"
    data-test="help-drawer"
    @update:model-value="onVisibleChange"
  >
    <template #header>
      <span class="help-drawer-title" data-test="help-drawer-title">{{ t('help.title') }}</span>
    </template>
    <div class="help-layout">
      <aside class="help-index">
        <div class="help-search" data-test="help-search">
          <el-input v-model="keyword" :placeholder="t('help.searchPlaceholder')" clearable />
        </div>
        <div v-if="!filteredGroups.length" class="help-empty" data-test="help-empty">{{ t('help.empty') }}</div>
        <div v-for="grp in filteredGroups" :key="grp.domain" class="help-domain">
          <div class="help-domain-head" data-test="help-domain-group">{{ domainLabel(grp.domain) }}</div>
          <ul class="help-doc-list">
            <li
              v-for="item in grp.items"
              :key="item.id"
              class="help-doc-item"
              :class="{ active: activeId === item.id }"
              data-test="help-doc-item"
              @click="onSelect(item)"
            >
              {{ item.title }}
            </li>
          </ul>
        </div>
      </aside>
      <section class="help-body">
        <template v-if="activeDoc">
          <h2 class="help-doc-title">{{ activeDoc.title }}</h2>
          <p class="help-doc-meta">
            <span>{{ t('help.metaVersion') }}: {{ activeDoc.version }}</span>
            <span>{{ t('help.metaUpdated') }}: {{ activeDoc.updatedAt }}</span>
          </p>
          <p v-if="!activeDoc.bodyMd || !activeDoc.bodyMd.trim()" class="help-doc-missing" data-test="help-detail-empty">
            {{ t('help.detailEmpty') }}
          </p>
          <MarkdownPreview v-else :source="activeDoc.bodyMd" />
        </template>
        <p v-else class="help-doc-hint" data-test="help-no-selection">{{ t('help.noSelection') }}</p>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">

import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getHelpDomainDocs, listHelpDocs, type HelpDocItem } from '@/api/help'
import { closeHelp, helpDrawerState } from '@/composables/useHelpDrawer'
import MarkdownPreview from '@/components/MarkdownPreview.vue'

const { t, te } = useI18n()


function domainLabel(code: string): string {
  const key = `help.domain.${code}`
  return te(key) ? t(key) : code
}

const visible = computed(() => helpDrawerState.visible)
const index = ref<HelpDocItem[]>([])
const keyword = ref('')
const activeId = ref<number | null>(null)
const activeDoc = ref<HelpDocItem | null>(null)

const domainCache = reactive<Record<string, HelpDocItem[]>>({})


const filteredGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const groups: { domain: string; items: HelpDocItem[] }[] = []
  const byDomain = new Map<string, HelpDocItem[]>()
  for (const item of index.value) {
    if (kw && !item.title.toLowerCase().includes(kw) && !domainLabel(item.domainCode).toLowerCase().includes(kw)) continue
    let arr = byDomain.get(item.domainCode)
    if (!arr) {
      arr = []
      byDomain.set(item.domainCode, arr)
      groups.push({ domain: item.domainCode, items: arr })
    }
    arr.push(item)
  }
  return groups
})

function onVisibleChange(next: boolean): void {
  if (!next) closeHelp()
}


async function loadDomain(domainCode: string): Promise<HelpDocItem[]> {
  if (domainCache[domainCode]) return domainCache[domainCode]
  const docs = await getHelpDomainDocs(domainCode)
  domainCache[domainCode] = docs
  return docs
}

async function selectDoc(item: HelpDocItem): Promise<void> {
  activeId.value = item.id
  const docs = await loadDomain(item.domainCode)
  activeDoc.value = docs.find((d) => d.id === item.id) ?? item
}

function onSelect(item: HelpDocItem): void {
  void selectDoc(item)
}


async function applyAnchor(): Promise<void> {
  const domain = helpDrawerState.domain
  if (!domain) return
  const docs = index.value.filter((x) => x.domainCode === domain)
  const anchor = helpDrawerState.anchor.trim().toLowerCase()
  const target = (anchor && docs.find((x) => x.title.toLowerCase().includes(anchor))) || docs[0]
  if (target) await selectDoc(target)
}

watch(visible, async (opened) => {
  if (!opened) return
  try {
    index.value = await listHelpDocs()
  } catch {
    index.value = []
  }
  await applyAnchor()
}, { immediate: true })
</script>

<style scoped lang="scss">
.help-drawer-title {
  font-size: 15px;
  font-weight: 600;
}
.help-layout {
  display: flex;
  gap: 16px;
  height: 100%;
  min-height: 0;
}
.help-index {
  flex: 0 0 240px;
  overflow-y: auto;
  border-right: 1px solid var(--tp-border-color);
  padding-right: 12px;
}
.help-domain-head {
  margin: 10px 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--tp-text-secondary);
}
.help-doc-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.help-doc-item {
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  color: var(--tp-text-primary);

  &:hover {
    background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.08));
  }
  &.active {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }
}
.help-body {
  flex: 1;
  overflow-y: auto;
  min-width: 0;
}
.help-doc-title {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 600;
}
.help-doc-meta {
  display: flex;
  gap: 16px;
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.help-doc-hint,
.help-empty,
.help-doc-missing {
  color: var(--tp-text-secondary);
  font-size: 13px;
}
</style>
