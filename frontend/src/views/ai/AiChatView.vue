<template>
  <!--
 Standalone AI chat page .
 Mount-only wrapper: the conversation kernel is the same ChatPanel the dock
 uses (one logic, zero second copy). Two-column shape : the shared
 session library rides the left column of the page card — the whole ledger
 lives server-side, the column pages/searches/groups through those doors.
 Opening a row drives the kernel through its exposed door (the list holds
 no second session state). The page name rides the global header (R23 — no
 in-page title row). The project segment is the header context single source
 (R30 — no page-level selector); a deep-linked ?projectId lands through
 useProjectPageContext and syncs to the header one-way.
 Worst-case scale note (S group): the session column windowizes rows
 (useWindowedList inside ChatSessionList); the thread virtualizes through
 the kernel's own window — no unbounded inline heavy asset on this page.
 -->
  <!-- Edge-gap single source: .page-root wraps the card so the theme 12px
       content padding applies on all sides (the card itself used to hug the
       viewport edge with gap 0). Height token --tp-layout-content-offset =
       header band + page-root vertical padding, live-measured. -->
  <div class="page-root chat-page tp-fill-off" data-test="ai-chat-page">
    <div class="page-card chat-page-card">
      <aside class="chat-page-side" data-test="chat-page-side">
        <ChatSessionList
          ref="sessionListRef"
          :current-id="panelRef?.sessionId ?? null"
          @open="openFromList"
          @create="createViaList"
        />
      </aside>
      <div class="chat-page-main">
        <!-- Thread actions : the standalone page hides the
 kernel header, so the same shared piece rides a slim op strip on
 the main column (view-state actions, never a title row — R23). -->
        <div class="chat-page-ops" data-test="chat-page-ops">
          <ChatThreadActions :session-id="panelRef?.sessionId ?? null" />
        </div>
        <ChatPanel ref="panelRef" :source="2" :show-header="false" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ChatPanel from '@/components/ChatPanel.vue'
import ChatSessionList from '@/components/ChatSessionList.vue'
import ChatThreadActions from '@/components/ChatThreadActions.vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'

// Deep-link consumption + one-way sync to the header context (rule: explicit
// query wins, header switch follows back). The kernel reads ctx.primaryId.
useProjectPageContext()

/** Kernel handle — the page only forwards list actions through its exposed
 *  doors (openSession/newConversation); session state stays kernel-owned. */
const panelRef = ref<InstanceType<typeof ChatPanel> | null>(null)
const sessionListRef = ref<InstanceType<typeof ChatSessionList> | null>(null)

// One-shot kernel door for the ?session=<id> deep link (the draft receipt of
// the document-change disposition page lands the user straight on the
// conversation holding the pending confirmations). Later sidebar clicks keep
// driving the same exposed door — no second session state here either.
const route = useRoute()
let sessionDoorUsed = false
watch([panelRef, () => route.query.session] as const, ([panel, sid]) => {
  if (panel && sid && !sessionDoorUsed) {
    sessionDoorUsed = true
    panel.openSession(Number(sid))
  }
}, { immediate: true })

function openFromList(id: number) {
  panelRef.value?.openSession(id)
}

function createViaList() {
  panelRef.value?.newConversation()
  void sessionListRef.value?.reload()
}

// The library row only exists once the ledger books a message ( rule:
// empty shells stay out of the list), and the kernel adopts the session id at
// the START of the first stream — so the column re-reads on both edges: the
// id appears, and the turn seals (running falls back). Zero second state: the
// page watches the kernel's exposed doors only.
watch([() => panelRef.value?.sessionId, () => panelRef.value?.running],
  () => void sessionListRef.value?.reload())
</script>

<style scoped>
.chat-page {
  height: calc(100vh - var(--tp-layout-content-offset));
  min-height: 420px;
  display: flex;
}
.chat-page-card {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}
.chat-page-side {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--tp-border-color, var(--el-border-color-light));
  padding: 12px;
}
.chat-page-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
</style>
