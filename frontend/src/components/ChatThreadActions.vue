<template>
  <!--
 Thread actions : export the
 conversation as Markdown, or save it into the knowledge base. ONE shared
 component for both surfaces (dock header + standalone page) — the mount
 wrappers never copy the flow (reuse >= 2 extracted, red line 6).

 The human confirmation gate lives HERE, on the button the user clicks:
 nothing server-side ever writes a knowledge document on its own (core
 only lands what this door asks for). Refusals (foreign session, dialog
 without Q&A yet, same-minute re-save) are shown with core's own wording —
 the client copies zero judgment, so there is no empty-dialog pre-check.
 Each action disables while in flight (F-group double-submit guard); the
 success toast names its object and the sediment one is one click away
 from the document management tab (W-group: point at the object, reachable).
 -->
  <span class="chat-thread-actions" data-test="chat-thread-actions">
    <el-tooltip :content="t('layout.chatDock.threadActions.exportAction')" placement="bottom">
      <el-button
        text
        size="small"
        data-test="chat-export-btn"
        :loading="exporting"
        :disabled="sessionId == null"
        :aria-label="t('layout.chatDock.threadActions.exportAction')"
        @click="doExport"
      >
        <el-icon><Download /></el-icon>
      </el-button>
    </el-tooltip>
    <el-tooltip :content="t('layout.chatDock.threadActions.sedimentAction')" placement="bottom">
      <el-button
        text
        size="small"
        data-test="chat-sediment-btn"
        :loading="sedimenting"
        :disabled="sessionId == null"
        :aria-label="t('layout.chatDock.threadActions.sedimentAction')"
        @click="doSediment"
      >
        <el-icon><Collection /></el-icon>
      </el-button>
    </el-tooltip>
    <!-- Read-only snapshot share : the token itself renders ONLY
 inside the owner panel this button opens, never on the op strip. -->
    <ChatSharePanel :session-id="sessionId" />
    <!-- One-key undo : always present while a session is
 open — whether it holds an accepted write is core-side knowledge, so
 no second grey-out rule is invented here; refusals echo core's own
 sentence (same zero-judgment door as export/sediment above). -->
    <el-tooltip :content="t('layout.chatDock.threadActions.undoAction')" placement="bottom">
      <el-button
        text
        size="small"
        data-test="chat-undo-btn"
        :loading="undoing"
        :disabled="sessionId == null"
        :aria-label="t('layout.chatDock.threadActions.undoAction')"
        @click="doUndo"
      >
        <el-icon><RefreshLeft /></el-icon>
      </el-button>
    </el-tooltip>
  </span>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElTooltip, ElButton, ElIcon, ElLink } from 'element-plus'
import { h } from 'vue'
import { Collection, Download, RefreshLeft } from '@element-plus/icons-vue'
import { exportChatSession, sedimentChatSession, undoChatSession } from '@/api/aiChat'
import { downloadTextFile } from '@/utils/exportDownload'
import ChatSharePanel from '@/components/ChatSharePanel.vue'

const props = defineProps<{
  /** Current thread id — null before the first exchange opens a session. */
  sessionId: number | null
}>()

const { t } = useI18n()
const router = useRouter()
const exporting = ref(false)
const sedimenting = ref(false)
const undoing = ref(false)

/** Refusal sentences come from core (already human, already names the why). */
function refusalText(e: unknown, fallback: string): string {
  const msg = e instanceof Error ? e.message : ''
  return msg || fallback
}

async function doExport() {
  if (props.sessionId == null || exporting.value) return
  exporting.value = true
  try {
    const res = await exportChatSession(props.sessionId)
    downloadTextFile(res.markdown, res.filename, 'text/markdown;charset=utf-8')
    ElMessage.success(t('layout.chatDock.threadActions.exportDone', { turns: res.turnCount }))
  } catch (e) {
    ElMessage.error(refusalText(e, t('layout.chatDock.threadActions.exportFailed')))
  } finally {
    exporting.value = false
  }
}

async function doSediment() {
  if (props.sessionId == null || sedimenting.value) return
  sedimenting.value = true
  try {
    const res = await sedimentChatSession(props.sessionId)
    // Success reports its object AND offers the one-click route to where the
    // document now lives (the documents tab of the extraction page).
    ElMessage({
      duration: 8000,
      message: h('span', null, [
        t('layout.chatDock.threadActions.sedimentDone', { doc: res.documentName }),
        ' ',
        h(ElLink, { type: 'primary', onClick: () => router.push({ path: '/extraction', query: { tab: 'documents' } }) },
          () => t('layout.chatDock.threadActions.viewDocuments')),
      ]),
    })
  } catch (e) {
    ElMessage.error(refusalText(e, t('layout.chatDock.threadActions.sedimentFailed')))
  } finally {
    sedimenting.value = false
  }
}

async function doUndo() {
  if (props.sessionId == null || undoing.value) return
  undoing.value = true
  try {
    const cardId = await undoChatSession(props.sessionId)
    // Success names its object (the card id) and where to confirm it; the
    // toast says plainly that nothing rolled back yet — the confirmation door
    // on the anchored card is the only writer.
    ElMessage({
      duration: 8000,
      message: t('layout.chatDock.threadActions.undoDone', { cardId }),
    })
  } catch (e) {
    ElMessage.error(refusalText(e, t('layout.chatDock.threadActions.undoFailed')))
  } finally {
    undoing.value = false
  }
}
</script>

<style scoped>
.chat-thread-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
</style>
