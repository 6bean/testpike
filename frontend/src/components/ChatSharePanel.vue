<template>
  <!--
 Owner-side share panel : freeze the current dialog
 into a read-only snapshot link, list the live shares of this session and
 revoke them. The panel is the ONLY surface that renders shareToken —
 the token is the public credential of the frozen copy and never leaves
 this owner view (any other page showing it would hand out the link).
 Judgment stays in core (ttl clamp, ownership, expiry, anti-enumeration);
 this component only forwards the choice and shows core's own sentences.
 -->
  <el-popover
    :width="360"
    trigger="click"
    placement="bottom-end"
    popper-class="chat-share-pop"
    @show="onPanelShow"
  >
    <template #reference>
      <span class="chat-share-ref">
        <el-tooltip :content="t('layout.chatDock.threadActions.shareAction')" placement="bottom">
          <el-button
            text
            size="small"
            data-test="chat-share-btn"
            :disabled="sessionId == null"
            :aria-label="t('layout.chatDock.threadActions.shareAction')"
          >
            <el-icon><Share /></el-icon>
          </el-button>
        </el-tooltip>
      </span>
    </template>

    <div class="share-panel" data-test="chat-share-panel">
      <div class="share-create" data-test="chat-share-create">
        <span class="share-label">{{ t('layout.chatDock.chatShare.ttlLabel') }}</span>
        <el-select v-model="ttlHours" size="small" data-test="chat-share-ttl" style="width: 120px">
          <el-option
            v-for="opt in ttlOptions"
            :key="opt.hours"
            :value="opt.hours"
            :label="t(opt.labelKey)"
          />
        </el-select>
        <el-button
          type="primary"
          size="small"
          data-test="chat-share-create-btn"
          :loading="creating"
          @click="doCreate"
        >
          {{ t('layout.chatDock.chatShare.create') }}
        </el-button>
      </div>
      <p class="share-hint">{{ t('layout.chatDock.chatShare.freezeHint') }}</p>

      <el-divider style="margin: 8px 0" />

      <div v-if="loading" class="share-state"><el-skeleton :rows="2" animated /></div>
      <p v-else-if="shares.length === 0" class="share-state" data-test="chat-share-empty">
        {{ t('layout.chatDock.chatShare.empty') }}
      </p>
      <ul v-else class="share-list" data-test="chat-share-list">
        <li v-for="row in shares" :key="row.id" class="share-row">
          <div class="share-row-main">
            <span class="share-row-meta" data-test="chat-share-row-meta">
              {{ t('layout.chatDock.chatShare.linkMeta', {
                turns: row.turnCount,
                expires: formatExpires(row.expiresAt),
              }) }}
            </span>
            <el-tag v-if="row.revoked === 1" size="small" type="info" data-test="chat-share-revoked-tag">
              {{ t('layout.chatDock.chatShare.revokedTag') }}
            </el-tag>
          </div>
          <div class="share-row-ops">
            <el-button
              v-if="row.revoked !== 1"
              text
              size="small"
              data-test="chat-share-copy-btn"
              :aria-label="t('layout.chatDock.chatShare.copy')"
              @click="copyLink(row.shareToken)"
            >
              <el-icon><CopyDocument /></el-icon>
              {{ t('layout.chatDock.chatShare.copy') }}
            </el-button>
            <el-button
              v-if="row.revoked !== 1"
              text
              size="small"
              type="danger"
              data-test="chat-share-revoke-btn"
              :loading="revokingId === row.id"
              :aria-label="t('layout.chatDock.chatShare.revoke')"
              @click="doRevoke(row.id)"
            >
              {{ t('layout.chatDock.chatShare.revoke') }}
            </el-button>
          </div>
        </li>
      </ul>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElDivider, ElIcon, ElMessage, ElOption, ElPopover, ElSelect, ElSkeleton, ElTag, ElTooltip } from 'element-plus'
import { CopyDocument, Share } from '@element-plus/icons-vue'
import { createChatShare, listChatShares, revokeChatShare, type ChatShareInfo } from '@/api/aiChat'
import { formatLocalDateTime } from '@/utils/localDateTime'

/**
 * Panel body of the dialog-share door. Props: the current thread id — the
 * panel only lights up once a session exists (before the first exchange
 * there is nothing to freeze; the host button disables honestly instead of
 * failing later with a generic error).
 */
const props = defineProps<{
  sessionId: number | null
}>()

const { t } = useI18n()
const creating = ref(false)
const loading = ref(false)
const revokingId = ref<number | null>(null)
const shares = ref<ChatShareInfo[]>([])

// Choices stay inside core's window (1h..7d) so the picker never offers a
// value that would silently clamp; core keeps the single source of the bounds.
const ttlOptions = [
  { hours: 1, labelKey: 'layout.chatDock.chatShare.ttl1h' },
  { hours: 24, labelKey: 'layout.chatDock.chatShare.ttl24h' },
  { hours: 72, labelKey: 'layout.chatDock.chatShare.ttl3d' },
  { hours: 168, labelKey: 'layout.chatDock.chatShare.ttl7d' },
] as const
const ttlHours = ref<number>(24)

/** Refusal sentences come from core — copy zero judgment here. */
function refusalText(e: unknown, fallbackKey: string): string {
  const msg = e instanceof Error ? e.message : ''
  return msg || t(fallbackKey)
}

/** The public link: this app, hash route, token-carrying path. */
function shareUrl(token: string): string {
  return `${window.location.origin}${window.location.pathname}#/share/chat/${token}`
}

function formatExpires(expiresAt: string): string {
  return formatLocalDateTime(expiresAt)
}

async function loadShares() {
  if (props.sessionId == null) return
  loading.value = true
  try {
    shares.value = await listChatShares(props.sessionId)
  } catch (e) {
    ElMessage.error(refusalText(e, 'layout.chatDock.chatShare.loadFailed'))
  } finally {
    loading.value = false
  }
}

// The list is the owner's live view — pull it every time the panel opens
// (el-popover's show event; no second visible ref, no watcher on internals).
function onPanelShow() {
  if (props.sessionId != null) void loadShares()
}

async function doCreate() {
  if (props.sessionId == null || creating.value) return
  creating.value = true
  try {
    const row = await createChatShare(props.sessionId, ttlHours.value)
    shares.value = [row, ...shares.value]
    await copyLink(row.shareToken)
  } catch (e) {
    ElMessage.error(refusalText(e, 'layout.chatDock.chatShare.createFailed'))
  } finally {
    creating.value = false
  }
}

async function copyLink(token: string) {
  try {
    await navigator.clipboard.writeText(shareUrl(token))
    ElMessage.success(t('layout.chatDock.chatShare.linkCopied'))
  } catch {
    ElMessage.warning(t('layout.chatDock.chatShare.clipboardDenied'))
  }
}

async function doRevoke(shareId: number) {
  if (revokingId.value != null) return
  revokingId.value = shareId
  try {
    const row = await revokeChatShare(shareId)
    // Honest local patch from the server's echo (no refetch storm).
    shares.value = shares.value.map((r) => (r.id === row.id ? row : r))
    ElMessage.success(t('layout.chatDock.chatShare.revokedDone'))
  } catch (e) {
    ElMessage.error(refusalText(e, 'layout.chatDock.chatShare.revokeFailed'))
  } finally {
    revokingId.value = null
  }
}
</script>

<style scoped>
.share-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.share-create {
  display: flex;
  align-items: center;
  gap: 8px;
}
.share-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.share-hint {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.share-state {
  margin: 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.share-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
}
.share-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.share-row:last-child {
  border-bottom: none;
}
.share-row-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.share-row-meta {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.share-row-ops {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}
</style>
