<template>
  <!--
 Public read-only snapshot page .
 Mounted OUTSIDE the authenticated layout (meta.public route): a link
 holder signs in nowhere and sees nothing but this frozen copy. The page
 copies zero judgment — expired / revoked / unknown tokens all answer with
 core's own sentence carried by the response; there is no client-side
 expiry clock and no invented second wording. No session id, no owner,
 no login affordance ever renders here.
 -->
  <!-- tp-page-root-exempt: public link-holder page mounted outside the
       authenticated layout — it owns its full-viewport background and 24px
       centering padding (like the password-expiry screen), so the theme
       .page-root content-flow padding does not apply to this surface. -->
  <div class="share-page" data-test="share-chat-page">
    <div class="share-card page-card">
      <header class="share-head" data-test="share-chat-head">
        <h1 class="share-title">{{ snapshot?.title || pageTitle }}</h1>
        <span v-if="snapshot" class="share-meta" data-test="share-chat-meta">
          {{ t('layout.shareChat.meta', { turns: snapshot.turnCount, expires: expiresText }) }}
        </span>
      </header>

      <el-skeleton v-if="loading" :rows="6" animated data-test="share-chat-loading" />

      <div v-else-if="error" class="share-dead" data-test="share-chat-dead">
        <el-empty :description="error">
          <span class="share-dead-hint">{{ t('layout.shareChat.deadHint') }}</span>
        </el-empty>
      </div>

      <MarkdownPreview v-else-if="snapshot" :source="snapshot.markdown" data-test="share-chat-body" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElEmpty, ElSkeleton } from 'element-plus'
import { fetchChatShareSnapshot, type ChatShareSnapshot } from '@/api/aiChat'
import { formatLocalDateTime } from '@/utils/localDateTime'
import MarkdownPreview from '@/components/MarkdownPreview.vue'

/**
 * Reads the token from the hash route (/share/chat/:token) and freezes the
 * page around whatever the public door answers — nothing else is fetched,
 * so the view cannot drift toward the live session.
 */
const props = defineProps<{ token?: string }>()

const { t } = useI18n()
const route = useRoute()
const loading = ref(true)
const error = ref('')
const snapshot = ref<ChatShareSnapshot | null>(null)

const pageTitle = computed(() => t('layout.shareChat.title'))

const shareToken = computed(() => props.token ?? String(route.params.token ?? ''))

const expiresText = computed(() => {
  if (!snapshot.value) return ''
  return formatLocalDateTime(snapshot.value.expiresAt)
})

onMounted(async () => {
  try {
    snapshot.value = await fetchChatShareSnapshot(shareToken.value)
  } catch (e) {
    // Core's sentence first (it names the real cause); the locale fallback
    // only fires when the transport carried none.
    const msg = e instanceof Error ? e.message : ''
    error.value = msg || t('layout.shareChat.dead')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  padding: 24px;
  background: var(--tp-bg-color, var(--el-bg-color-page));
  display: flex;
  justify-content: center;
}
.share-card {
  width: min(880px, 100%);
  align-self: flex-start;
}
.share-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}
.share-title {
  margin: 0;
  font-size: 18px;
  line-height: 1.4;
}
.share-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.share-dead-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
