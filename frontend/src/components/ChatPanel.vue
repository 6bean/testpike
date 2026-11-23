<template>
  <!--
 AI chat kernel : one conversation engine shared by the
 global dock (source=1) and the standalone page (source=2) — mount-only
 wrappers, zero second copy of the flow (rule §seven of the adjudication).
 Two-zone presentation: collapsible analysis-process area (state/reason/
 trace/draft/cite frames) plus the final answer stream (delta frames).
 The live stream rides the admin door (openChatStream — the browser never
 talks to ai-service directly); a dropped connection resumes from the
 acknowledged cursor and never starts a second run behind the user's back.
 The project segment comes from the global header context (single source);
 without a project the send door is honestly disabled with a hint.
 -->
  <div class="chat-root" data-test="chat-panel">
    <header v-if="showHeader" class="chat-header">
      <span class="chat-title">{{ t('layout.chatDock.title') }}</span>
      <span class="chat-ops">
        <el-tooltip :content="t('layout.chatDock.helpTip')" placement="bottom">
          <el-icon class="chat-icon" data-test="chat-dock-help" @click="openHelp('AI')"><QuestionFilled /></el-icon>
        </el-tooltip>
        <!-- New conversation, main position : the door the
 user called buried ("one level too deep") moves on top — no trip
 through the history view first. Reuses the ONE newConversation
 owner (seal the thread, clear the pointer) and the same locale
 wording as the list-view button: one concept, one word. -->
        <el-tooltip :content="t('layout.chatDock.sessionList.newChat')" placement="bottom">
          <el-icon class="chat-icon" data-test="chat-dock-new" :aria-label="t('layout.chatDock.sessionList.newChat')" @click="newConversation"><Plus /></el-icon>
        </el-tooltip>
        <!-- Session library entry : the dock body swaps WHOLE into
 the shared list view — no split inside the dock, no second title
 row (R23). Opening a row swaps back with that thread loaded. -->
        <el-tooltip :content="t('layout.chatDock.sessionList.entry')" placement="bottom">
          <el-icon class="chat-icon" data-test="chat-dock-history" :aria-label="t('layout.chatDock.sessionList.entry')" @click="historyOpen = !historyOpen"><Clock /></el-icon>
        </el-tooltip>
        <!-- Thread actions : export / save to knowledge.
 Shared component — the standalone page mounts the same piece. -->
        <ChatThreadActions :session-id="sessionId" />
        <!-- Composer display setting (shared one piece; see ChatComposerSettings). -->
        <ChatComposerSettings />
        <!-- Pin toggle : flips the
 light floating dock against the resident form. Un-pinned keeps
 the R33 click-outside behaviour byte-identical; pinned survives
 reloads through the shell's single writer. Esc/X still close in
 either state (D6). -->
        <el-icon
          v-if="closable"
          class="chat-icon"
          :class="{ 'chat-icon--active': chatDockState.pinned }"
          data-test="chat-dock-pin"
          role="button"
          :aria-pressed="chatDockState.pinned"
          :aria-label="t(chatDockState.pinned ? 'layout.chatDock.unpin' : 'layout.chatDock.pin')"
          :title="t(chatDockState.pinned ? 'layout.chatDock.unpin' : 'layout.chatDock.pin')"
          @click="setChatDockPin(!chatDockState.pinned)"
        ><Paperclip /></el-icon>
        <el-icon v-if="closable" class="chat-icon" data-test="chat-dock-close" :aria-label="t('layout.chatDock.close')" @click="emit('close')"><Close /></el-icon>
      </span>
    </header>

    <ChatSessionList
      v-if="historyOpen"
      class="chat-history-view"
      data-test="chat-history-view"
      :current-id="sessionId"
      @open="openSession"
      @create="newConversation"
    />

    <!-- Context waterline : read-only ledger mirror above the
 thread; numbers are server-computed, this mount passes ids only. -->
    <ChatWaterlineBar
      v-if="!historyOpen"
      :session-id="sessionId"
      :refresh-token="sealedTurnCount"
      :user-turn-texts="userTurnTextsForFold"
    />

    <!-- Pinned-object panel : sticky object view under the
 waterline fed by the wire triples (R30 segment = ctx.primaryId, single
 source). A version chip asks the comparison card through the
 SAME composer-prefill channel the suggestion chips use — zero second
 ask path, nothing sends without the user pressing send. -->
    <PinnedObjectPanel
      v-if="!historyOpen"
      :refs="refs"
      :project-id="ctx.primaryId"
      @compare="applySuggestion"
    />

    <div
      v-if="!historyOpen"
      ref="bodyEl"
      class="chat-body"
      data-test="chat-dock-body"
      :data-window-size="windowSize"
      :data-scroll-budget="chatScrollBudgetMs"
      :data-scroll-over="scrollOverBudget ? 'true' : undefined"
      @scroll="onBodyScroll"
    >
      <el-empty v-if="turns.length === 0" :description="t('layout.chatDock.empty')" :image-size="72" />
      <template v-else>
        <div v-if="padTop > 0" class="chat-pad" :style="{ height: `${padTop}px` }" data-test="chat-pad-top" />
        <div v-for="(m, mi) in visible" :key="m.id" class="chat-msg" :class="`chat-msg--${m.role}`" :data-role="m.role" :data-side="m.role" :data-state="m.state" data-test="chat-msg" data-windowed-row>
          <!-- Day divider : only the FIRST turn of a calendar day
 carries one — same-day follow-ups stay grouped under it. The
 comparison uses the FULL ledger at the true index so a scrolled
 window never loses the neighbour it must compare against. -->
          <div v-if="isFirstOfDay(turns, winStart + mi)" class="chat-day-divider" data-test="chat-day-divider">
            <span class="chat-day-divider-label">{{ dayLabelOf(m.createdAt) }}</span>
          </div>
          <div class="chat-msg-row">
            <span class="chat-msg-role" data-test="chat-msg-role">{{ t(m.role === 'user' ? 'layout.chatDock.roleQuestion' : 'layout.chatDock.roleAnswer') }}</span>
            <div class="chat-msg-main">
          <div v-if="m.process.length || isAwaitingTurn(m)" class="chat-process" data-test="chat-dock-process">
            <button
              type="button"
              class="chat-process-toggle"
              data-test="chat-process-toggle"
              :aria-expanded="!collapsedIds.has(m.id)"
              @click="toggleProcess(m.id)"
            >
              <!-- State glyph : the words
 alone left the affordance direction invisible; ▸/▾ names
 which way the click moves. -->
              <span class="chat-process-toggle-glyph" aria-hidden="true">{{ collapsedIds.has(m.id) ? '▸' : '▾' }}</span>
              <span v-if="!m.process.length && isAwaitingTurn(m)">{{ t('layout.chatDock.awaitingLabel') }}</span>
              <span v-else>{{ t(collapsedIds.has(m.id) ? 'layout.chatDock.expandProcess' : 'layout.chatDock.collapseProcess', { count: m.process.length }) }}</span>
            </button>
            <ul v-show="!collapsedIds.has(m.id)" class="chat-process-list">
              <li v-for="(p, i) in m.process" :key="i" class="chat-process-item" :data-kind="p.kind"><span
                  v-if="p.kind === 'user-inject'" class="chat-inject-chip">{{ t('layout.chatDock.injectChip') }}</span>{{ p.text }}</li>
            </ul>
          </div>
          <!-- Answer body : model answers are
 markdown (headings/tables/bold), and the shared surface already
 renders sealed chats through MarkdownPreview (share view) — the
 dock rendering raw source was a two-surface divergence, now
 closed on the same component. User turns stay plain text. -->
          <div v-if="m.role === 'assistant'" class="chat-msg-content chat-msg-content--md" data-test="chat-msg-md">
            <!-- Awaiting window : the blank pre-first-frame gap shows
 typing dots + dwell stage sentence, NEVER the shared markdown
 empty placeholder — an unfinished turn rendering "empty body"
 read as a dead server (user report: no feedback at all). -->
            <div v-if="isAwaitingTurn(m)" class="chat-awaiting" data-test="chat-awaiting">
              <span class="chat-typing" aria-hidden="true"><i /><i /><i /></span>
              <span v-if="awaitingBandOf(m) !== 'typing'" class="chat-awaiting-stage" data-test="chat-awaiting-stage" aria-live="polite">{{ t('layout.chatDock.stillProcessing', { n: awaitingSecondsOf(m), stage: awaitingStageTextOf(m) }) }}<template v-if="awaitingBandOf(m) === 'slow'">；{{ t('layout.chatDock.slowPathNote') }}</template></span>
            </div>
            <MarkdownPreview v-else-if="!isBlankAnswer(m.content)" :source="m.content" />
          </div>
          <div v-else class="chat-msg-content">{{ m.content }}</div>
          <!-- Credential-screenshot placeholder ( piece E, D2 = delete
 + placeholder): the original pixels of this turn's screenshot
 were purged at the terminal state, so the reload view names
 what happened instead of showing nothing. Flag folded at the
 ONE history-mapping point; reuses the muted label typography
 (theme single source, no new scoped style). -->
          <div v-if="m.credPlaceholder" class="chat-citations-label chat-cred-placeholder" data-test="chat-cred-placeholder">
            {{ t('layout.chatDock.credential.placeholder') }}
          </div>
          <!-- Data-moment mini-label (behaviour kernel round-2 item 5, front
               half): the as-of stamp folded from the retrieval frame's meta at
               the ONE reducer folding point. Absent whenever the pool carries
               no write time (untimed pool, zero-hit answer, legacy server,
               reloaded row) — the answer then shows no label instead of an
               invented clock. Reuses the muted label typography of the sources
               block (theme single source, no new scoped style). -->
          <div v-if="m.dataMoment" class="chat-citations-label chat-data-moment" data-test="chat-data-moment">
            {{ t('layout.chatDock.dataMomentLabel', { time: m.dataMoment }) }}
          </div>
          <div v-if="m.citations.length" class="chat-citations" data-test="chat-dock-citations">
            <div class="chat-citations-label">{{ t('layout.chatDock.sourcesLabel') }}</div>
            <button
              v-for="c in m.citations"
              :key="c.index"
              type="button"
              class="chat-cite-card"
              :data-test="`chat-cite-${c.index}`"
              :title="c.excerpt"
              @click="openCitation(c)"
            >
              <span class="chat-cite-index">[C{{ c.index }}]</span>
              <span class="chat-cite-title">{{ c.title }}</span>
              <span class="chat-cite-kind">{{ t(citeKindLabelKey(c.kind)) }}</span>
            </button>
          </div>
          <!-- Draft cards : landed
 draft frames become confirmable diff/field cards under the
 answer, routed by objectType (script repair / case create /
 case edit); un-landed ones stayed in the process area above
 (reducer folds, never shows twice). -->
          <!-- Draft cards ( stage 3c, generalized stage 3; group door
 piece 3): one answer with several drafts folds into a
 checkbox group with a single two-click acceptance; a lone draft
 keeps the plain card. Individual doors never disappear. -->
          <ChatDraftGroup v-if="m.repairs.length > 1" :message-id="m.messageId" :cards="m.repairs" :project-id="ctx.primaryId" />
          <ChatDraftCard v-else-if="m.repairs.length === 1" :card="m.repairs[0]" />
          <!-- Flow-guide action buttons : when the turn's trace
 journaled a case flow guide with live transitions, the dialog
 offers them as doors — each pushes exactly the page the core
 state machine names (already menu-guarded at fold time) with the
 case anchored through the action's own queryKey. The dock stays
 open: the conversation is the user's context to come back to. -->
          <div v-if="m.guide && m.guide.actions.length" class="chat-guide-actions" data-test="chat-guide-actions">
            <div
              class="chat-guide-label"
              :title="t('layout.chatDock.guideActionsLabel', { status: m.guide.currentStatusLabel || '', case: m.guide.caseTitle || ('#' + m.guide.caseId) })"
            >
              {{ t('layout.chatDock.guideActionsLabel', { status: m.guide.currentStatusLabel || '', case: m.guide.caseTitle || ('#' + m.guide.caseId) }) }}
            </div>
            <button
              v-for="a in m.guide.actions"
              :key="a.event"
              type="button"
              class="chat-guide-action"
              :data-test="`chat-guide-action-${a.event}`"
              @click="openGuideAction(m, a)"
            >
              {{ a.eventLabel }}
            </button>
          </div>
          <!-- Aggregate-query live card : when the turn's
 trace journaled the built-in aggregate.query answer, the dialog
 renders the server-computed numbers as a native-SVG chart
 (ECharts stays off this surface per ) with a collapsed value
 table underneath — the model composed the intent, the database
 produced every figure. No chart meta folded = no card, no
 placeholder. -->
          <ChatLiveCard v-if="m.chart" :chart="m.chart" />
          <!-- Multi-step plan card : the built-in plan.propose
 tool journaled an ordered step list and executed nothing. The
 card ticks/reorders the steps; its confirm button hands the
 chosen order to startPlanRun below — which sends it as the
 user's OWN next question through the regular door. A proposal
 alone never runs: the write-chain confirmation stays a click. -->
          <ChatPlanCard v-if="m.plan" :plan="m.plan" :running="running" @confirm="startPlanRun" />
          <!-- Goal-progress card : the run
 journaled a plan and finished with the ledger still owing
 steps. The card is the session's persistent state surface, so
 only the NEWEST open ledger owns it — older open-tails stay
 in the process history instead of stacking duplicate cards
 (R33 layering). Default-folded: the header row tells the
 whole story; the step list opens on demand. -->
          <ChatGoalCard v-if="m.goal && mi === lastGoalIndex" :goal="m.goal" :running="running" />
          <!-- Clarification card : the run suspended itself
 on a question the model could not answer; this card is the one
 door to un-suspend it. Pressing an option hands the answer to
 answerClarify below — the user's own next message through the
 regular send door, no auto-continue channel anywhere else. -->
          <ChatClarifyCard v-if="m.clarify" :card="m.clarify" :running="running" @answer="answerClarify" />
          <div v-if="errorTextOf(m)" class="chat-msg-error" data-test="chat-msg-error">
            <span>{{ errorTextOf(m) }}</span>
            <button v-if="canResend(m)" type="button" class="chat-msg-error-action" data-test="chat-msg-resend"
              @click="resendFailed(m)">{{ t('layout.chatDock.resend') }}</button>
            <button v-if="m.resendPayload" type="button" class="chat-msg-error-action" data-test="chat-msg-copy"
              @click="copyOriginal(m)">{{ t('layout.chatDock.copyOriginal') }}</button>
          </div>
          <div v-if="m.usage" class="chat-msg-usage">
            {{ t('layout.chatDock.usage', { prompt: m.usage.promptTokens, completion: m.usage.completionTokens }) }}
          </div>
          <!-- Per-turn rating : the observation
 signal of the self-evolution loop. Sealed assistant turns with a
 ledger id only — a running answer has nothing to rate yet, and an
 unsaved row has no id to post against. The reply is the fresh
 ledger row, so the folded value (never the click) is the truth on
 screen; a 👎 opens the one-sentence complaint door. -->
          <div
            v-if="m.role === 'assistant' && m.state === 'sealed' && m.messageId != null"
            class="chat-feedback"
            data-test="chat-feedback"
          >
            <button
              type="button"
              class="chat-feedback-btn"
              :class="{ 'is-active': m.feedback === 1 }"
              :aria-pressed="m.feedback === 1"
              :aria-label="t('layout.chatDock.feedbackUp')"
              :title="t('layout.chatDock.feedbackUpTip')"
              data-test="chat-feedback-up"
              @click="rate(m, 1)"
            >
              <span class="chat-feedback-glyph" aria-hidden="true">👍</span>
            </button>
            <button
              type="button"
              class="chat-feedback-btn"
              :class="{ 'is-active': m.feedback === -1 }"
              :aria-pressed="m.feedback === -1"
              :aria-label="t('layout.chatDock.feedbackDown')"
              :title="t('layout.chatDock.feedbackDownTip')"
              data-test="chat-feedback-down"
              @click="rate(m, -1)"
            >
              <span class="chat-feedback-glyph" aria-hidden="true">👎</span>
            </button>
            <span v-if="m.feedback === 1 || m.feedback === -1" class="chat-feedback-reset">
              <button
                type="button"
                class="chat-feedback-link"
                data-test="chat-feedback-clear"
                @click="clearRating(m)"
              >{{ t('layout.chatDock.feedbackClear') }}</button>
            </span>
            <!-- The complaint rides -1 only (server clears it on any other
                 state), so the composer never renders on a positive rating. -->
            <div v-if="pendingReasonTurnId === m.id" class="chat-feedback-reason" data-test="chat-feedback-reason">
              <el-input
                v-model="reasonDraft"
                type="textarea"
                :rows="3"
                maxlength="512"
                show-word-limit
                resize="none"
                data-test="chat-feedback-reason-input"
                :placeholder="t('layout.chatDock.feedbackReasonPlaceholder')"
              />
              <div class="chat-feedback-reason-ops">
                <el-button type="primary" size="small" data-test="chat-feedback-reason-send"
                  :disabled="reasonBusy" @click="submitReason(m)">
                  {{ t('layout.chatDock.feedbackReasonSend') }}
                </el-button>
                <el-button size="small" data-test="chat-feedback-reason-cancel" :disabled="reasonBusy" @click="cancelReason">
                  {{ t('layout.chatDock.feedbackReasonCancel') }}
                </el-button>
              </div>
              <div class="chat-feedback-reason-hint">{{ t('layout.chatDock.feedbackReasonHint') }}</div>
            </div>
            <div v-else-if="m.feedback === -1 && m.feedbackReason" class="chat-feedback-reason-text" data-test="chat-feedback-reason-text">
              {{ m.feedbackReason }}
            </div>
          </div>
          <div class="chat-msg-time" data-test="chat-msg-time">{{ timeLabelOf(m.createdAt) }}</div>
            </div>
          </div>
        </div>
        <div v-if="padBottom > 0" class="chat-pad" :style="{ height: `${padBottom}px` }" data-test="chat-pad-bottom" />
      </template>
    </div>

    <footer v-if="!historyOpen" class="chat-input">
      <!-- The standalone page has no in-page title band (R23): the same
           display setting mounts here so the preference stays reachable on
           both entry surfaces (one piece, zero second copy). -->
      <ChatComposerSettings v-if="!showHeader" />
      <!-- Composer height grab strip : drag vertically to
 resize the input area, double-click resets to the factory rows,
 ←/→ step one row when focused (X3: a drag is never the only
 path). Personal preference on localStorage; the ceiling is the
 governance key ai.chat.composer-max-rows. -->
      <div class="chat-composer-grab" data-test="chat-composer-resize" role="separator"
        aria-orientation="horizontal" tabindex="0" :aria-label="t('layout.chatDock.composerResizeAria')"
        @pointerdown="onComposerPointerDown" @dblclick="resetComposerRows" @keydown="onComposerResizeKeydown">
      </div>
      <!-- Popups ride above the composer (S-group: the list never covers the
           thread's newest answer). One input, two shapes: "/" opens the slash
           command list, "@" opens the grouped object candidates. Candidates
           empty = the popup closes honestly (typed text stays plain text). -->
      <div v-if="popupOpen" class="chat-popup" data-test="chat-popup" role="listbox"
        :aria-label="trigger.kind === 'slash' ? t('layout.chatDock.title') : t('layout.chatDock.mention.refsLabel')">
        <template v-if="trigger.kind === 'slash'">
          <button v-for="(k, i) in slashMatches" :key="k" type="button" role="option"
            :data-test="`chat-slash-${k}`" :aria-selected="i === activeIndex"
            class="chat-popup-item" @click="acceptSlash(k)" @mouseenter="activeIndex = i">
            {{ t(slashLabelKey(k)) }}
          </button>
        </template>
        <template v-else>
          <div v-if="ctx.primaryId == null" class="chat-popup-hint" data-test="chat-mention-no-project">
            {{ t('layout.chatDock.mention.noProjectHint') }}
          </div>
          <template v-else>
            <div v-for="g in mentionGroups" :key="g.kind" class="chat-popup-group">
              {{ t(`layout.chatDock.mention.group${g.kind[0].toUpperCase()}${g.kind.slice(1)}`) }}
            </div>
            <button v-for="(m, i) in mentionFlat" :key="`${m.kind}:${m.id}`" type="button" role="option"
              :data-test="`chat-mention-${m.kind}`" :aria-selected="i === activeIndex"
              class="chat-popup-item" @click="acceptMention(m)" @mouseenter="activeIndex = i">
              <span class="chat-popup-title">{{ m.title }}</span>
              <span class="chat-popup-id">#{{ m.id }}</span>
            </button>
            <div v-if="mentionFlat.length === 0" class="chat-popup-hint" data-test="chat-mention-empty">
              {{ t('layout.chatDock.mention.empty') }}
            </div>
          </template>
        </template>
      </div>
      <!-- Accepted chips: the SINGLE truth of a reference (typed @text without
           a chip is never parsed). Removing a chip removes it from the payload. -->
      <div v-if="refs.length > 0" class="chat-chips" data-test="chat-chips">
        <span class="chat-chips-label">{{ t('layout.chatDock.mention.refsLabel') }}</span>
        <el-tag v-for="r in refs" :key="`${r.kind}:${r.id}`" closable size="small"
          :data-test="`chat-chip-${r.kind}`" @close="removeRef(r)">
          {{ r.title }}
        </el-tag>
      </div>
      <!-- Pending user images :
 picking a file starts its upload immediately; the strip holds each
 door state (uploading / ready / failed with the server sentence)
 until the question is sent. Removing a pending image never touches
 storage again — the send simply stops carrying it. -->
      <div v-if="pendingImages.length > 0" class="chat-image-strip" data-test="chat-image-strip">
        <span class="chat-image-strip-hint" data-test="chat-image-queue-hint">{{ t('layout.chatDock.image.queueHint') }}</span>
        <el-tooltip v-for="img in pendingImages" :key="img.key" :disabled="img.status !== 'failed'"
          :content="img.error || ''" placement="top">
          <el-tag size="small" closable
            :type="img.status === 'failed' ? 'danger' : img.status === 'uploading' ? 'info' : 'success'"
            :data-test="`chat-image-item-${img.status}`" @close="removeImage(img)">
            {{ img.fileName }}{{ img.status === 'uploading' ? t('layout.chatDock.image.uploadingSuffix') : '' }}
          </el-tag>
        </el-tooltip>
      </div>
      <!-- Next-step suggestion chips : the sealed run
 offered 1-3 questions it can still serve this actor (the
 orchestrator dropped every capability outside the turn's
 mounted intersection before the frame was journaled). Pressing
 one hands the sentence to the regular send door — the user's
 OWN next message, never an auto-run. Shown on the newest sealed
 turn only, hidden mid-run by the suggestChips gate above. -->
      <div v-if="suggestChips.length > 0" class="chat-suggest-strip" data-test="chat-suggest-strip"
        :aria-label="t('layout.chatDock.suggest.label')">
        <span class="chat-suggest-label">{{ t('layout.chatDock.suggest.label') }}</span>
        <button v-for="(s, i) in suggestChips" :key="`${s.capability}:${i}`" type="button"
          class="chat-suggest-chip" :data-test="`chat-suggest-chip-${i}`"
          @click="applySuggestion(s.text)">{{ s.text }}</button>
      </div>
      <!-- WeChat-shape composer : the textarea owns a
 full-width row aligned with the thread (no button squeezed beside
 it), the action row underneath carries the left icon group and
 the right send group. -->
      <!-- Ask-from waterline hint : names WHERE the next answer is
 anchored before typing. Absent with no header segment — that door
 already points to the switch through the placeholder sentence. -->
      <div v-if="ctx.primaryId != null" class="chat-context-hint" data-test="chat-context-hint">
        {{ t('layout.chatDock.contextHint', { project: ctx.primaryName ?? '', page: currentPageTitle }) }}
      </div>
      <!-- Cross-project boundary (chat dock : a live conversation keeps
 its owning segment when the header moves on; the band names both
 sides and the one click starts clean in the segment at hand. -->
      <div v-if="projectBoundary" class="chat-project-boundary" data-test="chat-project-boundary">
        <span>{{ t('layout.chatDock.projectBoundaryHint', { owner: sessionProjectName ?? '', current: ctx.primaryName ?? '' }) }}</span>
        <button type="button" class="chat-project-boundary-new" data-test="chat-project-boundary-new"
          @click="startConversationInCurrentSlice()">{{ t('layout.chatDock.projectBoundaryNew', { owner: sessionProjectName ?? '', current: ctx.primaryName ?? '' }) }}</button>
      </div>
      <el-input
        ref="composerEl"
        v-model="draft"
        type="textarea"
        :rows="composerRows"
        resize="none"
        :placeholder="ctx.primaryId == null ? t('layout.chatDock.noProjectTip') : (running ? t('layout.chatDock.steerPlaceholder') : t('layout.chatDock.inputPlaceholder'))"
        data-test="chat-dock-input"
        @input="syncCaret"
        @keydown="onComposerKeydown"
        @paste="onComposerPaste"
      />
      <div class="chat-composer-actions">
        <div class="chat-action-group-left" data-test="chat-action-group-left">
          <!-- Photo attach door: refuses politely while no header segment is set
 (same named pointer as the send placeholder — single wording).
 Emoji/file slots stay unmounted until their features ship
 (WeChat-shape reservation, no placeholder buttons). -->
          <el-tooltip :disabled="ctx.primaryId != null" :content="t('layout.chatDock.noProjectTip')" placement="top">
            <span>
              <el-button :icon="Picture" data-test="chat-image-attach"
                :aria-label="t('layout.chatDock.image.attachTip')" :disabled="ctx.primaryId == null || running"
                @click="imageInputEl?.click()">{{ t('layout.chatDock.image.attachTip') }}</el-button>
            </span>
          </el-tooltip>
          <input ref="imageInputEl" type="file" hidden multiple
            accept="image/png,image/jpeg,image/gif,image/webp"
            data-test="chat-image-input" @change="onPickImages" />
        </div>
        <div class="chat-action-group-right" data-test="chat-action-group-right">
          <el-tooltip :disabled="ctx.primaryId != null" :content="t('layout.chatDock.noProjectTip')" placement="top">
            <span>
              <!-- Send door display = the composer preference (default off: Enter sends;
                   turned on in the title-bar / settings entry). The stop and
                   resume run-control doors here are never gated by it. -->
              <el-button v-if="!running && showSendButton" type="primary" data-test="chat-dock-send" :disabled="!canSend" @click="send">
                {{ t('layout.chatDock.send') }}
              </el-button>
              <el-button v-if="running" type="primary" plain data-test="chat-dock-steer" :disabled="!canSteer"
                @click="injectSupplement">
                {{ t('layout.chatDock.steerSend') }}
              </el-button>
              <el-button v-if="running" type="danger" plain data-test="chat-dock-stop" @click="stopStreaming">
                {{ t('layout.chatDock.stop') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button v-if="!running && resumeAvailable" data-test="chat-dock-resume" @click="resume">
            {{ t('layout.chatDock.resume') }}
          </el-button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resolveMenuTitleKey } from '@/config/menuStructure'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Close, Clock, Paperclip, Picture, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { useProjectContextStore } from '@/stores/projectContext'
import PinnedObjectPanel from '@/components/PinnedObjectPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { chatDockState, setChatDockPin, setChatStreaming, markChatUnreadCompleted, clearChatUnreadSession } from '@/composables/useChatDock'
import { countSealedUnread } from '@/composables/chatUnreadBadge'
import { openHelp } from '@/composables/useHelpDrawer'
import { knowledgeCitationRoute } from '@/composables/sourceAnchorJump'
import { useWindowedList } from '@/composables/useWindowedList'
import { useChatComposerPrefs } from '@/composables/useChatComposerPrefs'
import { ownsOtherSlice } from '@/composables/useChatSessionOwnership'
import { loadPasteTableConvertSwitch, pasteTableConvertEnabledView, planChatPaste } from '@/composables/chatPasteEnhance'
import { chatScrollBudgetMs, loadChatScrollBudget } from '@/composables/chatScrollBudget'
import {
  COMPOSER_DEFAULT_ROWS,
  clampComposerRows,
  composerMaxRowsView,
  loadComposerMaxRowsSwitch,
  readComposerRows,
  writeComposerRows,
} from '@/composables/useChatComposerRows'
import ChatComposerSettings from '@/components/ChatComposerSettings.vue'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import { awaitingBand, awaitingElapsedSeconds, isAwaitingTurn, stageOfProcess, type AwaitingBand } from '@/composables/chatAwaiting'
import { canResend, failureClassOf, isBlankAnswer } from '@/composables/chatFailureResend'
import ChatSessionList from '@/components/ChatSessionList.vue'
import ChatThreadActions from '@/components/ChatThreadActions.vue'
import ChatWaterlineBar from '@/components/ChatWaterlineBar.vue'
import {
  abortChatMessage,
  createChatSession,
  getChatActiveTurn,
  getChatHistory,
  injectChatSupplement,
  openChatStream,
  submitChatMessageFeedback,
  uploadChatImage,
  type ChatMessageRowView,
  type ChatStreamFailure,
  type ChatStreamHandle,
  type ChatStreamRequest,
  type RefObjectPayload,
} from '@/api/aiChat'
import { applyFrameToTurn, createAssistantTurn, createUserTurn, isFirstOfDay, type DockTurn, type FlowGuideActionButton, type SourceCitation } from '@/composables/chatDockReducer'
import ChatDraftCard from '@/components/ChatDraftCard.vue'
import ChatDraftGroup from '@/components/ChatDraftGroup.vue'
import ChatLiveCard from '@/components/ChatLiveCard.vue'
import ChatPlanCard from '@/components/ChatPlanCard.vue'
import ChatClarifyCard from '@/components/ChatClarifyCard.vue'
import ChatGoalCard from '@/components/ChatGoalCard.vue'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import { buildPageContext } from '@/composables/chatContextBuilder'
import type { ChatAskRequest, ReplayAskRequest, SelectionAskRef } from '@/composables/useChatDock'
import {
  detectTrigger,
  slashCommandKeys,
  slashLabelKey,
  type SlashCommandKey,
} from '@/composables/chatSlashCommands'
import {
  assembleMentionCandidates,
  dedupeRefs,
  filterMentionItems,
  toRefPayload,
  type MentionItem,
} from '@/composables/chatObjectCandidates'

/** Assistant turn plus the client-side failure kind (display text stays in locales). */
interface FailureTurn extends DockTurn {
  errorKind?: ChatStreamFailure['kind'] | 'empty' | null
  /**
 * The fresh-send request captured at send time (resend segment): the exact
 * question + attachments + page anchor a failed line replays on its
 * resend control. Turns loaded from history predate this feature and
 * carry nothing.
 */
  resendPayload?: ChatStreamRequest | null
}

const props = withDefaults(defineProps<{
  /** Entry surface recorded on the session: 1 dock / 2 standalone page / 3 badge link. */
  source: number
  /** The dock draws its own close button; the full page has nothing to close. */
  closable?: boolean
  /** Header band renders in the dock; the page carries its own page chrome. */
  showHeader?: boolean
  /** Execution pointer from a failure-badge deep link , if any. */
  badgeExecutionId?: number | null
  /** Chips parked by a list-page "Ask AI" selection . */
  selectionRefs?: SelectionAskRef[]
  /** Question parked by the ⌘K palette "Ask AI" row . */
  askQuestion?: ChatAskRequest | null
  replayAsk?: ReplayAskRequest | null
}>(), { closable: false, showHeader: true, badgeExecutionId: null, selectionRefs: () => [], askQuestion: null, replayAsk: null })

const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()
const ctx = useProjectContextStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
// Ask-from waterline for the composer hint: page display name through the
// SAME resolver the topbar uses (locale-reactive), header segment as project.
const currentPageTitle = computed(() => t(resolveMenuTitleKey(route.path, route.meta.titleKey)))
const { showSendButton, bindChatComposerPrefs } = useChatComposerPrefs()

/**
 * Cite-card jump : a help
 * chapter opens the help drawer anchored at its title keyword inside the cited
 * domain; a knowledge document deep-links the ledger page, which consumes
 * ?docId to open that document's drawer; a grounded defect row deep-links the
 * defect ledger with the cited title as its keyword — the page already reads
 * ?keyword, so no new page and no new query parameter is invented here.
 * Single-source doors only (useHelpDrawer / each page's own query contract) —
 * no second drawer state and no second role judgment: which defect rows exist
 * in this answer at all is decided server-side by the grounding kernel.
 */
const CITE_KIND_LABEL: Record<string, string> = {
  help: 'layout.chatDock.citeKindHelp',
  knowledge: 'layout.chatDock.citeKindKnowledge',
  defect: 'layout.chatDock.citeKindDefect',
}

/** One keyed lookup, so a kind the front has never seen gets the generic
 *  label instead of falling into the last branch of a ternary and being
 *  mislabeled with another kind's wording. */
function citeKindLabelKey(kind: string): string {
  return CITE_KIND_LABEL[kind] ?? 'layout.chatDock.citeKindOther'
}

function openCitation(c: SourceCitation): void {
  if (c.kind === 'help') {
    openHelp(c.domainCode ?? '', c.title)
    return
  }
  if (c.kind === 'defect') {
    void router.push({ path: '/defects', query: { keyword: c.title } })
    return
  }
  // Only a knowledge reference owns the /knowledge door. An unknown kind (a
  // future fourth source family) opens nothing rather than a wrong door — the
  // card keeps its generic label and its excerpt, the honest-degradation rule
  // the reducer already applies to unusable meta.
  if (c.kind === 'knowledge') {
    void router.push(knowledgeCitationRoute(c.refId, c.chunkId))
  }
}

/**
 * Flow-guide action button : pushes exactly the route the core
 * state machine named for this transition (menu-guarded at fold time — only
 * real leaves ever render) with the case anchored under the action's own
 * queryKey. The dock stays open on purpose: the conversation is the context
 * the user comes back to after performing the action.
 */
function openGuideAction(turn: DockTurn, a: FlowGuideActionButton): void {
  const caseId = turn.guide?.caseId
  if (caseId === null || caseId === undefined) return
  void router.push({ path: a.page, query: { [a.queryKey ?? 'caseId']: String(caseId) } })
}

/**
 * Session persistence key (personal preference stays client-side, same
 * posture as tp_proj_ctx_ / tp_chat_dock_width / tp_chat_fab precedents):
 * one conversation per user x project segment, re-read on mount so a page
 * reload shows the thread the user was in (TC-AO-06 reload survives).
 */
const CHAT_CTX_KEY_PREFIX = 'tp_chat_ctx_'
function chatCtxKey(): string | null {
  if (ctx.primaryId == null) return null
  return `${CHAT_CTX_KEY_PREFIX}${auth.user?.userId ?? 'anon'}_${ctx.primaryId}`
}

/**
 * Owning-slice pointer (design option A, §two.4): which project segment the open
 * conversation belongs to, stamped when the session is created or opened.
 * A conversation never mixes slices — after a header switch to another
 * project the panel names the boundary and offers the one-click fresh
 * start in the current segment, it never silently swaps the thread.
 */
const sessionProjectId = ref<number | null>(null)

/** Name of the owning segment through the same visible-domain lookup as the
 * ownership echo (single source, no second label assembly). */
const sessionProjectName = computed(
  () => auth.projects.find((p) => p.projectId === sessionProjectId.value)?.projectName ?? null,
)

/** True when the conversation belongs to another project than this page. */
const projectBoundary = computed(() => ownsOtherSlice(sessionProjectId.value, ctx.primaryId))

const turns = ref<FailureTurn[]>([])

/**
 * Worst-case scale (S group, 500-message fixture): the thread window-slices
 * through the shared useWindowedList — DOM rows stay ≤ one window + buffers
 * regardless of session length, spacers keep the scrollbar honest. A small
 * list renders unpadded with the identical shape.
 */
const list = useWindowedList(turns, { scroller: () => bodyEl.value, rowPitch: 64, buffer: 6 })
const visible = list.visible
const windowSize = list.windowSize
// Window start : the day divider compares against the FULL ledger, so
// the first row of a scrolled window needs its true index into `turns`.
const winStart = list.start
const padTop = list.padTop
const padBottom = list.padBottom
/**
 * Goal-card mount gate : the ledger is the session's persistent state
 * surface, so only the NEWEST visible row carrying an open tail owns the
 * card — older tails live on in their process history without stacking
 * duplicate cards down the thread.
 */
const lastGoalIndex = computed(() => {
  const rows = visible.value
  for (let i = rows.length - 1; i >= 0; i -= 1) {
    if (rows[i].goal) return i
  }
  return -1
})
const draft = ref('')
const running = ref(false)
// Awaiting dwell clock : one 1s ticker while a turn streams. Bands
// are pure functions of elapsed time (chatAwaiting), the component only
// supplies "now"; the interval dies with the stream or the unmount, never
// outliving the run (no unbounded timer).
const awaitingNow = ref(Date.now())
let awaitingTicker: ReturnType<typeof setInterval> | null = null
watch(running, (on) => {
  if (on) {
    awaitingNow.value = Date.now()
    awaitingTicker = setInterval(() => { awaitingNow.value = Date.now() }, 1000)
  } else if (awaitingTicker !== null) {
    clearInterval(awaitingTicker)
    awaitingTicker = null
  }
})
onBeforeUnmount(() => {
  if (awaitingTicker !== null) clearInterval(awaitingTicker)
  awaitingTicker = null
})

function awaitingBandOf(m: DockTurn): AwaitingBand {
  return awaitingBand(awaitingNow.value - m.createdAt)
}
function awaitingSecondsOf(m: DockTurn): number {
  return awaitingElapsedSeconds(awaitingNow.value - m.createdAt)
}
function awaitingStageTextOf(m: DockTurn): string {
  const stage = stageOfProcess(m.process)
  return stage.source === 'frame' ? stage.text : t(`layout.chatDock.awaitingStage.${stage.key}`)
}
// Mirror the live-stream flag into the shared dock state : the
// floating bubble shows its "running" halo while a turn streams and the dock
// is closed. One fold point — the bubble never guesses.
watch(running, (v) => setChatStreaming(v))
// Badge fold lives BELOW the sessionId declaration — its pair
// watch reads both sources at registration (immediate), and reaching the
// const before initialization breaks setup.
const resumeAvailable = ref(false)
/** Scroll container of the thread (auto-follow seam for the streaming view). */
const bodyEl = ref<HTMLElement | null>(null)
/**
 * Sticky-bottom discipline: the thread follows the live stream only while the
 * reader is at the bottom; scrolling up mid-stream stops forcing the view
 * (the user is reading earlier lines — hijacking the scroll is the defect).
 */
let pinnedToBottom = true
function onBodyScroll() {
  const el = bodyEl.value
  if (!el) return
  pinnedToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  list.sync()
  sampleScrollFrame()
}

/**
 * Long-session scroll accounting : the windowed thread must not only
 * stay small in DOM — a scroll frame over the budget (hot key
 * chat.scroll-budget-ms, default 16) is a felt stutter and is never hidden.
 * One rAF per burst measures the committed frame against the event origin;
 * the first breach latches data-scroll-over on the scroller (sticky until
 * the thread swaps) = the honest ledger the spectrum asserts. A clean run
 * never sets it, so the absence of the attribute is itself the pass signal.
 */
const scrollOverBudget = ref(false)
let scrollFrameArmed = false
function sampleScrollFrame() {
  if (scrollOverBudget.value || scrollFrameArmed) return
  const origin = performance.now()
  scrollFrameArmed = true
  requestAnimationFrame(() => {
    scrollFrameArmed = false
    if (performance.now() - origin > chatScrollBudgetMs.value) scrollOverBudget.value = true
  })
}
function scrollToBottom() {
  const el = bodyEl.value
  if (el && pinnedToBottom) el.scrollTop = el.scrollHeight
  list.sync()
  // Anchor must CONVERGE with the pitch-driven spacers: after measurePitch
  // corrects the row stride the scroll height changes again ( made rows
  // taller than the 64px fallback, and one assignment then lands the window
  // mid-ledger instead of on the tail — the 500-row spectrum caught it as a
  // real regression). A bounded follow-up pass re-pins until the bottom is
  // truly reached; off-bottom readers are never hijacked (pinned check each
  // pass keeps the sticky-bottom contract intact).
  void nextTick(() => {
    const still = bodyEl.value
    if (!still || !pinnedToBottom) return
    if (still.scrollTop < still.scrollHeight - still.clientHeight - 2) {
      still.scrollTop = still.scrollHeight
      list.sync()
      void nextTick(() => {
        const last = bodyEl.value
        if (last && pinnedToBottom) {
          last.scrollTop = last.scrollHeight
          list.sync()
        }
      })
    }
  })
}
/**
 * Tail honesty for appended rows: the window segment only advances through
 * sync, and the only triggers of sync were a user scroll, the history-load
 * block and the stream-frame callback. A question pressed with no frame yet
 * therefore left its brand-new rows (the user turn AND the running assistant
 * placeholder, .md-empty body included) outside the stale window segment,
 * hidden behind the bottom spacer for the whole warm-up — the steering E2E
 * gate proved it: `[data-state="running"]` held zero md children for 30s,
 * and with no running row on screen the process column had no host for the
 * live inject echo either. Watching the row count re-pins the bottom through
 * the SAME scrollToBottom (one owner of the follow decision; a reader who
 * scrolled up is never hijacked — pinnedToBottom guards it).
 */
watch(
  () => turns.value.length,
  () => {
    if (pinnedToBottom) void nextTick(scrollToBottom)
  },
)
/** The assistant turn the live stream writes into (resume/abort seam). */
const pendingTurnId = ref<number | null>(null)
const sessionId = ref<number | null>(null)
// Badge fold : entering this session while the dock opens clears
// only this session's anchor — other sessions keep their honest counts.
// The pair watch is deliberate: opening the dock AFTER a page reload mounts
// this panel past the visible flip (a lone visible watcher misses it) and
// the restored thread lands asynchronously, so the clear fires when BOTH
// the dock is open and the session id is in place. clearChatUnreadSession
// is idempotent (absent anchor = zero writes), so repeated edges are free.
watch(
  [() => chatDockState.visible, () => sessionId.value],
  ([open, sid]) => {
    if (open && sid != null) clearChatUnreadSession(String(sid))
  },
  { immediate: true },
)

/**
 * retrieval anchor: the oldest-first user texts of the thread
 * the panel ALREADY loaded (sealed ledger rows). The waterline bar lists
 * the folded turns from this feed — disclosure never opens a second
 * retrieval chain.
 */
const userTurnTextsForFold = computed(() =>
  turns.value.filter((m) => m.role === 'user').map((m) => m.content),
)
/** Sealed-answer row count: grows exactly when the ledger gains numbers, so
 * the waterline bar refetches after every turn instead of freezing on the
 * first-binding snapshot . */
const sealedTurnCount = computed(() =>
  turns.value.filter((m) => m.role === 'assistant' && m.state === 'sealed').length,
)
/**
 * Badge deep-link pointer awaiting its session row : set when the
 * panel mounts for a badge click, cleared the moment the session is opened —
 * the pointer then lives on the row (core folds it into every run of this
 * thread), so a second badge click in the same thread starts a new row.
 */
const badgeId = ref<number | null>(props.badgeExecutionId ?? null)
let handle: ChatStreamHandle | null = null
let rowSeq = 0

/**
 * Next-step suggestion chips : the done frame's gated
 * proposals, shown on the newest sealed assistant turn only. Mid-run they
 * hide (the answer is not finished yet, a chip would quote a stale tail);
 * once the user sends anything new, a fresher row takes the tail slot and
 * the old chips retire with it — no dismissal state to invent.
 */
const suggestChips = computed(() => {
  if (running.value) return []
  const last = turns.value[turns.value.length - 1]
  return last && last.role === 'assistant' && last.state === 'sealed' ? last.suggest : []
})

/** Send needs a question AND a header project segment — missing context is an honest door, not a late error. */
const canSend = computed(() => draft.value.trim() !== '' && ctx.primaryId != null && !running.value)

// ─────────────────────────────────────────────────────────────────────────────
// Message structure labels : the day divider opens a
// calendar day, the per-row label carries the clock time. Weekday names ride
// the browser locale (the same Intl single source the rest of the product
// uses); "today"/"yesterday" reuse the session-library group words so ONE
// concept never shows two different labels on two surfaces.
// ─────────────────────────────────────────────────────────────────────────────
const DAY_MS = 86_400_000
function midnightOf(ts: number): number {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}
function dayLabelOf(ts: number): string {
  const age = midnightOf(Date.now()) - midnightOf(ts)
  if (age === 0) return t('layout.chatDock.sessionList.groupToday')
  if (age === DAY_MS) return t('layout.chatDock.sessionList.groupYesterday')
  // A single-call "month+day+weekday" Intl pattern renders glued in zh-CN
  // (numeric date butts straight against the weekday word); mixed-script
  // spacing requires a visible gap, so both parts come from their own Intl
  // call and join with an explicit space.
  const datePart = new Intl.DateTimeFormat(locale.value, { month: 'numeric', day: 'numeric' }).format(ts)
  const weekdayPart = new Intl.DateTimeFormat(locale.value, { weekday: 'short' }).format(ts)
  return `${datePart} ${weekdayPart}`
}
function timeLabelOf(ts: number): string {
  return new Intl.DateTimeFormat(locale.value, { hour: '2-digit', minute: '2-digit' }).format(ts)
}

// ─────────────────────────────────────────────────────────────────────────────
// Slash commands + @-mentions .
// Popup state derives from (value, caret) only; the composables own the word
// table and the endpoint assembly (chatSlashCommands / chatObjectCandidates —
// zero hard-coded display text here, locale single source). The chip list
// (refs) is the single truth of a reference: the payload derives from it, and
// typed "@text" that never became a chip stays plain text.
// ─────────────────────────────────────────────────────────────────────────────
const caret = ref(0)
const refs = ref<RefObjectPayload[]>([])
const mentionItems = ref<MentionItem[]>([])
const mentionLoading = ref(false)
const activeIndex = ref(0)
/** Structural view of the el-input instance (textarea element + focus door). */
const composerEl = ref<{ focus: () => void; textarea?: HTMLTextAreaElement } | null>(null)

const trigger = computed(() => detectTrigger(draft.value, caret.value))
/** Slash list = command keys whose locale label contains the typed token. */
const slashMatches = computed<SlashCommandKey[]>(() => {
  if (trigger.value.kind !== 'slash') return []
  const kw = trigger.value.token.toLowerCase()
  return slashCommandKeys().filter((k) => t(slashLabelKey(k)).toLowerCase().includes(kw))
})
const mentionGroups = computed(() => {
  const order: MentionItem['kind'][] = []
  for (const m of mentionItems.value) if (!order.includes(m.kind)) order.push(m.kind)
  return order.map((kind) => ({ kind, items: mentionItems.value.filter((m) => m.kind === kind) }))
})
const mentionFlat = computed(() => mentionGroups.value.flatMap((g) => g.items))
/**
 * Popup honesty: it exists only while there is something to pick. Empty slash
 * matches close it silently (typed text stays plain text, no dead popup);
 */
const popupOpen = computed(() => {
  if (running.value) return false
  if (trigger.value.kind === 'slash') return slashMatches.value.length > 0
  if (trigger.value.kind === 'mention') {
    // No project segment: the popup becomes an honest pointer, not a dead list.
    if (ctx.primaryId == null) return true
    return mentionLoading.value || mentionItems.value.length > 0 || trigger.value.token === ''
  }
  return false
})

/** Caret position feeds the trigger detector (input/keyup re-reads it). */
function syncCaret() {
  const el = composerEl.value?.textarea
  if (el) caret.value = el.selectionStart ?? el.value.length
}

// Candidate assembly is debounced and sequence-guarded: typing "@log" must
// not fire five searches, and a late answer of an older keystroke is dropped.
let mentionTimer: ReturnType<typeof setTimeout> | null = null
let mentionSeq = 0
watch(
  () => (trigger.value.kind === 'mention' ? `${ctx.primaryId}|${trigger.value.token}` : null),
  (sig) => {
    if (mentionTimer) clearTimeout(mentionTimer)
    if (sig == null) {
      mentionItems.value = []
      mentionLoading.value = false
      return
    }
    mentionLoading.value = true
    mentionTimer = setTimeout(async () => {
      const seq = ++mentionSeq
      const token = trigger.value.token
      const projectId = ctx.primaryId
      if (projectId == null) {
        // Honest pointer state (the popup shows its hint line); no door fired.
        mentionItems.value = []
        mentionLoading.value = false
        return
      }
      const items = await assembleMentionCandidates(token, projectId)
      // A newer keystroke (or the popup closing) supersedes this answer.
      if (seq !== mentionSeq) return
      mentionItems.value = filterMentionItems(items, token)
      mentionLoading.value = false
    }, 200)
  },
)

watch(popupOpen, (open) => { if (!open) activeIndex.value = 0 })

/** Accept one command: its locale text becomes the question template. */
function acceptSlash(key: SlashCommandKey) {
  const label = t(slashLabelKey(key))
  draft.value = label
  caret.value = draft.value.length
  void nextTick(() => {
    const el = composerEl.value?.textarea
    if (el) { el.focus(); el.setSelectionRange(caret.value, caret.value) }
  })
}

/** Accept one object = a chip lands, the "@token" fragment leaves the text. */
function acceptMention(m: MentionItem) {
  refs.value = dedupeRefs([...refs.value, toRefPayload(m)])
  const at = trigger.value.markerAt
  const tokenEnd = at + 1 + trigger.value.token.length
  draft.value = (draft.value.slice(0, at) + draft.value.slice(tokenEnd)).trimStart()
  caret.value = Math.min(at, draft.value.length)
  mentionItems.value = []
  void nextTick(() => {
    const el = composerEl.value?.textarea
    if (el) { el.focus(); el.setSelectionRange(caret.value, caret.value) }
  })
}

/** Removing the chip is removing the reference (chip = single truth). */
function removeRef(r: RefObjectPayload) {
  refs.value = refs.value.filter((x) => !(x.kind === r.kind && x.id === r.id))
}

// ─────────────────────────────────────────────────────────────────────────────
// User-image attachments on a fresh question ( P4 , mobile-photo
// bug report). Picking starts the upload right away so the strip shows the
// truth before send; the SIZE/FORMAT/MAGIC verdict stays core's single gate —
// its refusal sentence is displayed verbatim, never a second local rule.
// ─────────────────────────────────────────────────────────────────────────────
interface PendingImage {
  key: number
  fileName: string
  objectKey: string
  status: 'uploading' | 'ready' | 'failed'
  error?: string
}

const pendingImages = ref<PendingImage[]>([])
const imageInputEl = ref<HTMLInputElement | null>(null)
let imageSeq = 0

/** Upload one picked file through the door; state lands on the strip tag.
 *  Reactivity posture: pushing the PLAIN object and mutating the local
 *  reference never wakes the strip (the proxy in the array is a different
 *  identity) — the chip froze on the "uploading" tag after a 200 upload (browser-proven,
 *  E2E test_chat_image_attach_spectrum). The proxy read back from the
 *  reactive array is the only handle that renders; matched by key because
 *  concurrent uploads can finish out of push order. */
async function uploadOneImage(projectId: number, file: File) {
  const seq = ++imageSeq
  pendingImages.value.push({ key: seq, fileName: file.name, objectKey: '', status: 'uploading' })
  const item = pendingImages.value.find((x) => x.key === seq)
  if (!item) return
  try {
    const view = await uploadChatImage(projectId, file)
    item.objectKey = view.objectKey
    item.fileName = view.fileName
    item.status = 'ready'
  } catch (err) {
    item.status = 'failed'
    item.error = (err as Error)?.message || t('layout.chatDock.image.uploadFailed')
  }
}

/** Shared image door for BOTH entry paths (picker + paste, ): one
 * queue, one upload call site, one honest hint when no project is sliced.
 * Per-question cap stays enforced by the run door — the client never
 * hard-codes a second threshold. */
function attachImageFiles(files: File[]) {
  if (files.length === 0) return
  if (ctx.primaryId == null) {
    ElMessage.warning(t('layout.chatDock.noProjectTip'))
    return
  }
  for (const file of files) void uploadOneImage(ctx.primaryId, file)
}

/** Every file chosen at once starts its upload through the shared door. */
function onPickImages(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  attachImageFiles(files)
}

// Paste spectrum : clipboard images ride the attachment door, table
// clipboard converts to GFM at the caret while the hot-update switch is on,
// everything else keeps the browser default. Pure routing lives in
// chatPasteEnhance; this handler only owns the side effects.
const pasteConvertEnabled = pasteTableConvertEnabledView()
let pasteOffTipShown = false

function onComposerPaste(e: ClipboardEvent) {
  const dt = e.clipboardData
  if (!dt) return
  const images: File[] = []
  for (const item of Array.from(dt.items)) {
    if (item.kind === 'file' && item.type.startsWith('image/')) {
      const f = item.getAsFile()
      if (f) images.push(f)
    }
  }
  const plan = planChatPaste(
    { html: dt.getData('text/html'), text: dt.getData('text/plain'), images },
    pasteConvertEnabled.value,
  )
  attachImageFiles(plan.images)
  if (plan.consumed && plan.insertText != null) {
    e.preventDefault()
    insertAtComposerCaret(plan.insertText)
    return
  }
  // Off-state honesty once per view (R32 pointer shape: page path + key name).
  if (plan.tableHit && !pasteConvertEnabled.value && !pasteOffTipShown) {
    pasteOffTipShown = true
    ElMessage.info(t('layout.chatDock.paste.convertOffTip'))
  }
}

/** Replace the current selection with the converted table and park the
 *  caret after it (editable result, never a locked block). */
function insertAtComposerCaret(text: string) {
  const el = composerEl.value?.textarea
  const start = el?.selectionStart ?? draft.value.length
  const end = el?.selectionEnd ?? start
  draft.value = draft.value.slice(0, start) + text + draft.value.slice(end)
  caret.value = start + text.length
  void nextTick(() => {
    const node = composerEl.value?.textarea
    if (node) {
      node.focus()
      node.setSelectionRange(caret.value, caret.value)
    }
  })
}

/** Dropping a pending image only takes it off this question (zero storage call). */
function removeImage(img: PendingImage) {
  pendingImages.value = pendingImages.value.filter((x) => x.key !== img.key)
}

/**
 * Keyboard path while the popup is open: ↑/↓ move, Enter accepts the active
 * row, Esc closes. With no popup, Enter sends and Shift+Enter breaks a line
 * (composer default). The popup swallows these keys ONLY while visible.
 */
function onComposerKeydown(e: KeyboardEvent) {
  syncCaret()
  if (!popupOpen.value) {
    // No popup: Enter sends, Shift+Enter breaks a line (R33 composer contract).
    // IME guard: a keystroke that confirms CJK candidates (isComposing / the
    // legacy keyCode 229) must NEVER fire a send with half-composed text.
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
      e.preventDefault()
      submitComposer()
    }
    return
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const size = trigger.value.kind === 'slash' ? slashMatches.value.length : mentionFlat.value.length
    if (size === 0) return
    activeIndex.value = (activeIndex.value + (e.key === 'ArrowDown' ? 1 : size - 1)) % size
    return
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    // A visible popup with rows: Enter picks the row, never sends half-typed text.
    if (trigger.value.kind === 'slash' && slashMatches.value.length > 0) {
      e.preventDefault()
      acceptSlash(slashMatches.value[activeIndex.value])
    } else if (trigger.value.kind === 'mention' && mentionFlat.value.length > 0) {
      e.preventDefault()
      acceptMention(mentionFlat.value[activeIndex.value])
    }
    return
  }
  if (e.key === 'Escape') {
    // Close honestly: drop the trigger fragment focus stays with the user.
    e.preventDefault()
    caret.value = draft.value.length
    mentionItems.value = []
  }
}

/**
 * Re-read the persisted conversation on mount / segment change (TC-AO-06): the
 * ledger door returns sealed turns with answer bodies rebuilt server-side;
 * folding onto visible turns is the shared chatHistoryMapper (one point).
 * A stale or foreign pointer is refused by the core ownership check — then
 * the key is dropped and the panel honestly starts empty (no fake history).
 */
async function loadHistory() {
  if (running.value || turns.value.length > 0) return
  scrollOverBudget.value = false
  const key = chatCtxKey()
  if (key == null) return
  const stored = Number(localStorage.getItem(key))
  if (!Number.isFinite(stored) || stored <= 0) return
  try {
    const rows = await getChatHistory(stored)
    sessionId.value = stored
    // The pointer is kept per user × segment, so a restored thread owns the
    // segment it was read from — the boundary band stays honest after a reload.
    sessionProjectId.value = ctx.primaryId
    turns.value = mapHistoryToTurns(rows, rowSeq) as FailureTurn[]
    rowSeq += rows.length
    void armResumeForActiveTurn(stored)
  } catch {
    localStorage.removeItem(key)
  }
  void nextTick(() => {
    list.measurePitch()
    list.sync()
    scrollToBottom()
  })
}

/**
 * Reload-resume seam (mid-turn reload defect): the sealed-history door leaves
 * the still-streaming turn out by design, so a page that came back during an
 * answer showed neither the running row nor a resume door. Probe the honest
 * active-turn door; when one is unsealed, book the live assistant row the
 * existing stream path already writes into and raise the resume door — the
 * SSE replay owns the content, no second fold semantic is introduced.
 */
async function armResumeForActiveTurn(sid: number): Promise<void> {
  try {
    const active = await getChatActiveTurn(sid)
    if (!active || sessionId.value !== sid || pendingTurnId.value != null) return
    const turn: FailureTurn = createAssistantTurn(++rowSeq, Date.now())
    turns.value.push(turn)
    pendingTurnId.value = turn.id
    resumeAvailable.value = true
    void nextTick(scrollToBottom)
  } catch {
    // A refused or unreachable door keeps the plain sealed view: no door is
    // better than a fake one, and the next history read retries honestly.
  }
}

const collapsedIds = ref(new Set<number>())
/** Turns whose collapse state the reader set by hand — auto-collapse never overrides it. */
const manualToggles = new Set<number>()

/**
 * Session library view flag : the dock body swaps WHOLE between the
 * conversation kernel and the shared ChatSessionList (CQ rule: no split
 * inside the dock, no second title row). Opening a row seals the current
 * thread's on-screen state (a live stream keeps running server-side — the
 * sealed turns land in the history door) and loads the chosen thread.
 */
const historyOpen = ref(false)

async function openSession(id: number) {
  historyOpen.value = false
  resetThread()
  sessionId.value = id
  sessionProjectId.value = ctx.primaryId
  // Entering a session reads it : zero its unread.
  clearChatUnreadSession(String(id))
  const key = chatCtxKey()
  if (key) localStorage.setItem(key, String(id))
  try {
    const rows = await getChatHistory(id)
    if (sessionId.value !== id) return
    turns.value = mapHistoryToTurns(rows, rowSeq) as FailureTurn[]
    rowSeq += rows.length
    void armResumeForActiveTurn(id)
  } catch {
    // A refused door (foreign/stale row): start honestly empty, drop the pointer.
    if (key) localStorage.removeItem(key)
  }
  void nextTick(() => {
    list.measurePitch()
    list.sync()
    scrollToBottom()
  })
}

/** New conversation = seal the current thread (never delete): pointer cleared. */
function newConversation() {
  historyOpen.value = false
  resetThread()
  const key = chatCtxKey()
  if (key) localStorage.removeItem(key)
  void nextTick(() => composerEl.value?.focus?.())
}

// The standalone page drives the same two doors from its left column — one
// kernel, one state owner (the list never keeps a second session pointer).
// sessionId + running are exposed read-only so the host column can mark the
// open row and re-read the ledger when a turn seals (no second state copy).
defineExpose({ openSession, newConversation, sessionId, running })

function toggleProcess(id: number) {
  const next = new Set(collapsedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsedIds.value = next
  manualToggles.add(id)
}

/**
 * One keyed lookup per display class (the fold lives in failureClassOf —
 * the dock never branches on raw wire kinds), so a kind the front has never
 * seen gets the interrupted-shaped generic sentence instead of being
 * mislabeled with a specific class's wording.
 */
const FAILURE_CLASS_KEYS: Record<string, string> = {
  network: 'layout.chatDock.errNetwork',
  interrupted: 'layout.chatDock.errInterrupted',
  timeout: 'layout.chatDock.errTimeout',
  empty: 'layout.chatDock.errEmpty',
  server: 'layout.chatDock.errRefused',
}

/** Error line of a turn: server sentence verbatim, or a locale entry per kind. */
function errorTextOf(m: FailureTurn): string {
  if (m.errorText) return m.errorText
  if (m.state === 'failed' && m.errorKind) {
    const cls = failureClassOf(m.errorKind)
    return t((cls && FAILURE_CLASS_KEYS[cls]) || 'layout.chatDock.errInterrupted')
  }
  return ''
}

function pendingTurn(): FailureTurn | undefined {
  return turns.value.find((m) => m.id === pendingTurnId.value)
}

/**
 * Plan card confirm : confirming IS the user asking the next
 * turn. The chosen step order is composed into the composer and sent through
 * the same door a typed question uses (guard included: no run without a
 * project segment, no double-send). No auto-run channel exists anywhere else —
 * this function is the only consumer of the plan card's confirm emit.
 */
function startPlanRun(titles: string[]) {
  draft.value = t('layout.chatDock.plan.runMessage', {
    list: titles.map((x, i) => `${i + 1}. ${x}`).join('\n'),
  })
  void send()
}

/**
 * Clarify card answer : pressing an option IS the user's
 * next message — composed into the composer and sent through the same door
 * a typed question uses (guard included: no send without a project segment,
 * no double-send). No auto-continue channel exists anywhere else — this
 * function is the only consumer of the clarify card's answer emit (the same
 * posture as startPlanRun above: proposals never run themselves).
 */
function answerClarify(option: string) {
  draft.value = t('layout.chatDock.clarify.answerMessage', { option })
  void send()
}

/**
 * Suggestion chip press : the proposed question IS the
 * user's next message, composed into the composer VERBATIM (the server wrote
 * it in the user's own voice — no wrapper sentence) and sent through the same
 * door a typed question uses (guard included: no send without a project
 * segment, no double-send). No auto-run channel exists anywhere else — this
 * function is the only consumer of the done frame's suggest member.
 */
function applySuggestion(text: string) {
  draft.value = text
  void send()
}

/** Ask one question: the first send opens the session, later sends reuse it. */
async function send() {
  const text = draft.value.trim()
  if (text === '' || running.value) return
  // Missing header segment = a NAMED refusal, never silent: with the send button
  // hidden (the default composer shape) there is no disabled door to hover, so
  // Enter itself must point the user to the switch (same locale sentence as the
  // placeholder hint — single source, no second wording).
  if (ctx.primaryId == null) {
    ElMessage.warning(t('layout.chatDock.noProjectTip'))
    return
  }
  // Images must settle BEFORE the question opens (an in-flight upload would
  // otherwise silently ride out of the payload). A failed pick never blocks:
  // it is dropped with its name spoken, then the send proceeds without it.
  if (pendingImages.value.some((p) => p.status === 'uploading')) {
    ElMessage.warning(t('layout.chatDock.image.uploadingTip'))
    return
  }
  const failedImages = pendingImages.value.filter((p) => p.status === 'failed')
  if (failedImages.length > 0) {
    ElMessage.warning(t('layout.chatDock.image.droppedTip', { names: failedImages.map((f) => f.fileName).join('、') }))
  }
  // F5 double-send guard : flip the running flag SYNCHRONOUSLY before
  // the first await. Opening the session door is itself an await — without
  // this flip a second Enter/click during that window would pass the re-entry
  // check above and start a second run behind the user's back.
  running.value = true
  turns.value.push(createUserTurn(++rowSeq, text, Date.now()))
  draft.value = ''
  // Chips ride as the reference payload (chip = single truth); they are
  // consumed by this question, so the composer starts clean afterwards.
  const payload = refs.value.length > 0 ? dedupeRefs(refs.value) : undefined
  refs.value = []
  // Ready images ride this question and are consumed with it — the strip
  // starts clean exactly like the chips do.
  const imagePayload = pendingImages.value
    .filter((p) => p.status === 'ready')
    .map((p) => ({ objectKey: p.objectKey, fileName: p.fileName }))
  pendingImages.value = []
  try {
    await startStream({
      prompt: text,
      refObjects: payload,
      attachments: imagePayload.length > 0 ? imagePayload : undefined,
      // Per-question ask-from anchor : header project + the page this
      // question is sent from, built by the SAME whitelist builder the
      // session door uses; core re-validates and lands it on the audited
      // frame. A reconnect (resume) never carries an anchor.
      questionContext: buildPageContext(route.path, route.query, ctx.primaryId),
    })
  } catch {
    // A refused session door restores the composer — running never sticks on.
    running.value = false
  }
}

/**
 * One-click resend of a failed line: replays the captured request verbatim
 * (question text + attachments + page anchor) through the SAME send door a
 * typed question uses — the ledger records the replay as one NEW turn, the
 * failed line stays as its own record, nothing is double-booked. The guard
 * mirrors the send door (running flag flips synchronously; no project segment
 * is an honest door, not a late error).
 */
async function resendFailed(turn: FailureTurn): Promise<void> {
  const p = turn.resendPayload
  if (!canResend(turn) || !p || running.value) return
  if (ctx.primaryId == null) {
    ElMessage.warning(t('layout.chatDock.noProjectTip'))
    return
  }
  running.value = true
  turns.value.push(createUserTurn(++rowSeq, p.prompt ?? '', Date.now()))
  try {
    await startStream({
      prompt: p.prompt,
      refObjects: p.refObjects,
      attachments: p.attachments,
      questionContext: p.questionContext,
    })
  } catch {
    running.value = false
  }
}

/** Copy the failed question's original text so the reader can retry elsewhere. */
async function copyOriginal(turn: FailureTurn): Promise<void> {
  const text = turn.resendPayload?.prompt ?? ''
  if (text === '') return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('layout.chatDock.originalCopied'))
  } catch {
    ElMessage.warning(t('layout.chatDock.copyDenied'))
  }
}

/** Re-attach after a dropped connection: replay from the acknowledged cursor only. */
function resume() {
  if (sessionId.value == null || pendingTurnId.value == null) return
  resumeAvailable.value = false
  void startStream({ resumeCursor: handle?.cursor() ?? 0 })
}

/** Stop reading AND stop the server-side turn once its assistant message id is known. */
async function stopStreaming() {
  const turn = pendingTurn()
  if (turn?.messageId != null) {
    // The ledger seals the turn as aborted; the partial answer stays on screen.
    await abortChatMessage(turn.messageId).catch(() => undefined)
  }
  handle?.close()
}

/**
 * Mid-turn steering door : while an answer is still streaming the
 * composer stays alive and what the reader types rides the session's injects
 * door instead of opening a second run. The echo on screen is the journaled
 * `user-inject` frame arriving on the SAME open stream (the ledger is the
 * single truth — the click paints nothing), so live view, reconnect replay and
 * history reload all show the supplement in the same place. The door degrades
 * honestly: when the turn ended a moment earlier the backend already submitted
 * the text as a regular message, so the reader is told and the thread is
 * re-read from the ledger once this stream settles (never a second loader —
 * the shared session door reopens it). A refused door keeps the words in the
 * composer: a steering sentence is never silently dropped.
 */
let threadReloadPending = false
async function injectSupplement() {
  const text = draft.value.trim()
  if (text === '' || sessionId.value == null) return
  try {
    const landing = await injectChatSupplement(sessionId.value, text)
    draft.value = ''
    if (!landing.injectedIntoRunningTurn) {
      ElMessage.info(t('layout.chatDock.steerDegraded'))
      threadReloadPending = true
    }
  } catch {
    ElMessage.error(t('layout.chatDock.steerFailed'))
  }
}

// Steer door: the composer stays alive while an answer streams .
const canSteer = computed(() => running.value && draft.value.trim() !== '')

/** Enter while an answer streams = steer it, never a second question. */
function submitComposer(): void {
  if (running.value) void injectSupplement()
  else void send()
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-turn rating . The message row is the one
// observation ledger: a click POSTs, the SERVER's row is folded back (so the
// screen never claims a rating the ledger refused), and a complaint sentence
// rides a 👎 only. Re-clicking the active thumb clears back to 0.
// ─────────────────────────────────────────────────────────────────────────────
/** Turn awaiting its complaint sentence (one open composer at a time). */
const pendingReasonTurnId = ref<number | null>(null)
const reasonDraft = ref('')
const reasonBusy = ref(false)

/** Fold the authoritative fields of a returned ledger row onto the turn. */
function foldFeedback(turn: FailureTurn, view: ChatMessageRowView): void {
  turn.feedback = view.feedback ?? null
  // The server keeps a reason on -1 only; fold the same invariant so a
  // positive/cleared rating can never display a stale complaint.
  turn.feedbackReason = view.feedback === -1 ? (view.feedbackReason ?? null) : null
}

async function postFeedback(turn: FailureTurn, feedback: 1 | -1 | 0, reason?: string | null): Promise<boolean> {
  if (turn.messageId == null) return false
  reasonBusy.value = true
  try {
    const view = await submitChatMessageFeedback(turn.messageId, feedback, reason ?? null)
    foldFeedback(turn, view)
    return true
  } catch {
    // A refused rating names its consequence (R27: no internal codes on screen).
    ElMessage.error(t('layout.chatDock.feedbackFailed'))
    return false
  } finally {
    reasonBusy.value = false
  }
}

/** Thumb click: same thumb again = clear; 👎 landing = open the sentence door. */
async function rate(turn: FailureTurn, value: 1 | -1): Promise<void> {
  if (turn.messageId == null || reasonBusy.value) return
  const next: 1 | -1 | 0 = turn.feedback === value ? 0 : value
  const ok = await postFeedback(turn, next, next === 0 ? null : undefined)
  if (!ok) return
  if (next === -1) {
    // Keep the complaint door on the row the user just disliked.
    pendingReasonTurnId.value = turn.id
    reasonDraft.value = turn.feedbackReason ?? ''
    return
  }
  // Any other state closes the door and drops an unsent sentence (the ledger
  // cleared it server-side too, so an old complaint can never reappear).
  pendingReasonTurnId.value = null
  reasonDraft.value = ''
}

/** Send the optional one-sentence complaint onto the existing 👎. */
async function submitReason(turn: FailureTurn): Promise<void> {
  const text = reasonDraft.value.trim()
  const ok = await postFeedback(turn, -1, text === '' ? null : text)
  if (!ok) return
  pendingReasonTurnId.value = null
  reasonDraft.value = ''
  ElMessage.success(t('layout.chatDock.feedbackSaved'))
}

/** Drop the sentence without touching the recorded 👎. */
function cancelReason(): void {
  pendingReasonTurnId.value = null
  reasonDraft.value = ''
}

/** Explicit "clear rating": drops the rating AND the complaint (0 on the ledger). */
async function clearRating(turn: FailureTurn): Promise<void> {
  const ok = await postFeedback(turn, 0, null)
  if (!ok) return
  pendingReasonTurnId.value = null
  reasonDraft.value = ''
}

/** One door for both stream shapes (fresh prompt / resume cursor). */
async function startStream(req: ChatStreamRequest) {
  if (sessionId.value == null) {
    if (ctx.primaryId == null) return
    const session = await createChatSession({
      projectId: ctx.primaryId,
      title: req.prompt?.slice(0, 60) ?? '',
      source: props.source,
      // Where the user is asking from: route + segment + one object pointer,
      // never filter/tab/keyword noise (whitelist, core re-validates). The
      // badge pointer rides in when this thread was born from a
      // failure badge; the core run door folds it into the diagnosis chain.
      contextJson: buildPageContext(route.path, route.query, ctx.primaryId, badgeId.value),
    })
    sessionId.value = session.id
    sessionProjectId.value = session.projectId
    // The pointer lives on the session row from here on — later turns of the
    // same thread must not re-stamp a second badge onto it.
    badgeId.value = null
    // Pin the conversation to this user × segment so a reload re-reads it.
    const key = chatCtxKey()
    if (key) localStorage.setItem(key, String(session.id))
  }
  if (req.resumeCursor == null) {
    const turn: FailureTurn = createAssistantTurn(++rowSeq, Date.now())
    // The one door a failed line replays through: keep the request itself
    // (not a re-read of the composer, which has moved on since).
    turn.resendPayload = req
    turns.value.push(turn)
    pendingTurnId.value = turn.id
    // A fresh turn means "follow me": re-pin the reader to the bottom.
    pinnedToBottom = true
  }
  resumeAvailable.value = false
  running.value = true
  handle = openChatStream(
    sessionId.value,
    req,
    (frame) => {
      const turn = pendingTurn()
      if (turn) applyFrameToTurn(turn, frame)
      // A done frame that seals NO answer text is an empty completion: an
      // honest failed line with the resend door, never a fake sealed bubble
      // on the shared empty-body placeholder (the fourth failure class).
      if (turn && frame.event === 'done' && turn.state === 'sealed' && isBlankAnswer(turn.content)) {
        turn.state = 'failed'
        turn.errorKind = 'empty'
      }
      // Live stream keeps the newest line in view while the reader stayed at
      // the bottom (scroll-to-follow, never hijacking an upward read).
      void nextTick(scrollToBottom)
      // Sealed turn: the process area collapses to its summary row (S-group
      // default-collapsed at scale) — watching it live served the stream,
      // after completion the answer is what deserves the space.
      if (turn && (frame.event === 'done' || frame.event === 'error') && turn.process.length > 0) {
        if (!manualToggles.has(turn.id)) collapsedIds.value = new Set([...collapsedIds.value, turn.id])
      }
    },
    (failure) => {
      const turn = pendingTurn()
      if (turn && turn.state === 'running') {
        turn.state = 'failed'
        if (failure.kind === 'refused' && failure.serverMsg !== '') {
          // The server sentence is the authority — shown as-is, never rephrased.
          turn.errorText = failure.serverMsg
        } else {
          turn.errorKind = failure.kind
          resumeAvailable.value = failure.kind === 'network' || failure.kind === 'interrupted'
        }
      }
    },
  )
  await handle.settled
  running.value = false
  if (threadReloadPending) {
    // A degraded steering note is already on the ledger as its own message:
    // re-open the thread through the shared session door (one loader, no
    // second history mapper) so the screen shows what the server kept.
    threadReloadPending = false
    const settledId = sessionId.value
    if (settledId != null) void openSession(settledId)
  }
  const turn = pendingTurn()
  if (turn && turn.state === 'running') {
    // EOF without a terminal frame (idle cut on the server): honest seal, no fake completion.
    turn.state = 'failed'
    turn.errorKind = 'interrupted'
    resumeAvailable.value = true
  }
  // Badge fold : a turn SEALED behind a closed dock raises the
  // bubble's unread count (answer 1, each draft card awaiting confirmation 1,
  // terminal failure 1). Stream frames never reach this line — the fold is
  // the seal point, so per-frame counting is structurally impossible.
  const sealedSession = sessionId.value
  if (turn && sealedSession != null && !chatDockState.visible) {
    markChatUnreadCompleted(String(sealedSession), countSealedUnread(turn))
  }
}

// Clears the on-screen thread back to an honest empty state: the next first
// question starts clean (design option A: a NEW conversation in the current segment).
function resetThread(): void {
  if (handle) handle.close()
  handle = null
  running.value = false
  resumeAvailable.value = false
  pendingTurnId.value = null
  sessionProjectId.value = null
  sessionId.value = null
  turns.value = []
  // A fresh thread drops any half-typed complaint with it.
  pendingReasonTurnId.value = null
  reasonDraft.value = ''
  collapsedIds.value = new Set()
  manualToggles.clear()
  pinnedToBottom = true
  list.resetWindow()
  // A different segment: chips/candidates belonged to the old one — drop them.
  refs.value = []
  mentionItems.value = []
  pendingImages.value = []
  caret.value = 0
}

// A header segment switch never swaps or clears a live conversation (design
// option A: no auto swap or silent clear on page switch) — the boundary
// band names the mismatch instead.
// Only an empty panel starts clean in the new segment, re-reading whatever
// thread that segment had persisted before.
function startConversationInCurrentSlice(): void {
  // The one-click door beside the boundary sentence: seal the foreign
  // thread on screen and continue in a fresh conversation of the segment
  // the user is standing on (the old session stays in its own history).
  resetThread()
  void loadHistory()
}

watch(
  () => ctx.primaryId,
  () => {
    if (sessionId.value == null || turns.value.length === 0) {
      resetThread()
      void loadHistory()
      return
    }
    // A live thread survives the switch: sessionProjectId keeps naming its
    // owning segment, the band + one-click above carry the honest handoff.
  },
)

/**
 * Badge deep link : a failure badge parked an execution pointer.
 * The thread resets and the persisted pointer is dropped — the diagnosis must
 * open a NEW source=3 session whose whitelist context carries this executionId
 * (the core run door folds the pointer from the row; a reused old thread has
 * no pointer on its row). The composer pre-fills one editable question;
 * nothing sends until the user presses send.
 */
const badgeQuestion = computed(() =>
  t('layout.chatDock.badgeQuestion', { id: badgeId.value ?? '' }),
)

/** Replay capsule wording : same pointer, different question. */
const replayQuestion = computed(() =>
  t('layout.chatDock.replayQuestion', { id: props.replayAsk?.taskId ?? '' }),
)

/**
 * Selection deep link : a list page parked its checked rows and
 * opened the dock. The set lands as composer chips through the shared dedupe
 * (chip key = single truth, re-asking the same rows never doubles a chip);
 * the question itself stays the user's — nothing sends until they press send,
 * and an over-cap selection is refused by the server door naming the numbers
 * (no client-side copy of ai.chat.ref.max-objects).
 */
watch(
  () => props.selectionRefs,
  (selection) => {
    if (!selection || selection.length === 0) return
    refs.value = dedupeRefs([...refs.value, ...selection])
  },
  { deep: true, immediate: true },
)
watch(
  () => props.badgeExecutionId,
  (id) => {
    if (id == null) return
    badgeId.value = id
    resetThread()
    const key = chatCtxKey()
    if (key) localStorage.removeItem(key)
    draft.value = badgeQuestion.value
  },
  { immediate: true },
)

/**
 * Replay ask deep link : the batch-detail capsule rode the badge
 * pointer channel, so the pointer wiring above already installed the task id
 * and reset the thread; this watch (declared after the badge watch on purpose —
 * same flush, later callback wins) swaps the prefilled wording to the replay
 * question. Prefill only — the send door stays the human's.
 */
watch(
  () => props.replayAsk,
  (request) => {
    if (request?.taskId == null) return
    draft.value = replayQuestion.value
    void nextTick(() => composerEl.value?.focus())
  },
  { immediate: true },
)

/**
 * Palette ask deep link : the ⌘K dialog turned its typed natural
 * language into a dock question. Prefill only — same posture as the batch
 * report ?ask= channel (auto-sending on arrival would bypass the send door).
 * A draft the user already started is never overwritten; an empty composer
 * takes the question and gains focus so one Enter-less review-then-send closes
 * the loop.
 */
watch(
  () => props.askQuestion,
  (request) => {
    const question = request?.question?.trim()
    if (!question) return
    if (draft.value.trim() !== '') return
    draft.value = question
    void nextTick(() => composerEl.value?.focus())
  },
  { immediate: true },
)

/**
 * AO-7 stage 3 deep-link prefill: the batch report page pushes /ai/chat?ask=...
 * — the question lands in the composer for the human to review and send
 * (prefill only; auto-sending on arrival would bypass the send door). The
 * ?ask query is one-shot: it is dropped from the URL after consuming so a
 * refresh does not re-seed the composer.
 */
function consumeAskPrefill(): void {
  const ask = route.query.ask
  if (typeof ask === 'string' && ask.trim() !== '' && draft.value.trim() === '') {
    draft.value = ask
    void router.replace({ path: route.path, query: { ...route.query, ask: undefined } })
    nextTick(() => composerEl.value?.focus())
  }
}

/**
 * Composer prefs bucket: the localStorage key is stringly typed while the
 * session carries a numeric id — normalize at the call site .
 */
function composerPrefsBucket(): string | null {
  return auth.user?.userId != null ? String(auth.user.userId) : null
}

/* ---------------------------------------------------------------------
 * Composer height : rows is a personal localStorage
 * preference clamped by the governance ceiling ai.chat.composer-max-rows.
 * Drag geometry folds through clampComposerRows ONLY (grab strip, keyboard
 * steps, initial read, ceiling change) — same discipline as the dock width
 * strip, no second clamp per path (R40). Declared before onMounted/identity
 * watch, which bind and load it.
 * ------------------------------------------------------------------- */
const composerMaxRows = composerMaxRowsView()
/** One composer row ≈ one 14px/21px line box (el-textarea inner metrics). */
const COMPOSER_LINE_PX = 21
const composerRows = ref(readComposerRows(localStorage, composerPrefsBucket(), composerMaxRows.value))
let composerDragStartY = 0
let composerDragStartRows = 0
let composerDragMoved = false

function persistComposerRows(): void {
  writeComposerRows(localStorage, composerPrefsBucket(), composerRows.value, composerMaxRows.value)
}

function onComposerPointerDown(e: PointerEvent): void {
  const target = e.currentTarget as HTMLElement
  composerDragStartY = e.clientY
  composerDragStartRows = composerRows.value
  composerDragMoved = false
  const onMove = (ev: PointerEvent) => {
    const deltaY = composerDragStartY - ev.clientY
    if (Math.abs(deltaY) >= 3) composerDragMoved = true
    // Dragging UP grows the composer; one row ≈ one line box.
    composerRows.value = clampComposerRows(
      composerDragStartRows + Math.round(deltaY / COMPOSER_LINE_PX), composerMaxRows.value)
  }
  const onUp = () => {
    target.removeEventListener('pointermove', onMove)
    target.removeEventListener('pointerup', onUp)
    target.removeEventListener('pointercancel', onUp)
    target.releasePointerCapture?.(e.pointerId)
    // A strip click must never swallow the composer focus door.
    if (!composerDragMoved) composerEl.value?.focus()
    else persistComposerRows()
  }
  target.setPointerCapture?.(e.pointerId)
  target.addEventListener('pointermove', onMove)
  target.addEventListener('pointerup', onUp)
  target.addEventListener('pointercancel', onUp)
}

/** Double-click on the strip = drop the bucket, factory rows show again. */
function resetComposerRows(): void {
  writeComposerRows(localStorage, composerPrefsBucket(), null, composerMaxRows.value)
  composerRows.value = COMPOSER_DEFAULT_ROWS
}

/** Keyboard equivalence on the focused strip (X3): ←/→ one row, ↑/↓ four. */
function onComposerResizeKeydown(e: KeyboardEvent): void {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight' && e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return
  e.preventDefault()
  const delta = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? 1 : e.key === 'ArrowLeft' ? -1 : -4
  composerRows.value = clampComposerRows(composerRows.value + delta, composerMaxRows.value)
  persistComposerRows()
}

// The ceiling can move while the surface is open (config page save is
// hot-updatable): re-fold the live rows into the new interval and persist.
watch(composerMaxRows, (ceiling) => {
  composerRows.value = clampComposerRows(composerRows.value, ceiling)
  persistComposerRows()
})

onMounted(() => {
  bindChatComposerPrefs(composerPrefsBucket())
  composerRows.value = readComposerRows(localStorage, composerPrefsBucket(), composerMaxRows.value)
  void loadHistory()
  void loadPasteTableConvertSwitch()
  void loadComposerMaxRowsSwitch()
  void loadChatScrollBudget()
  consumeAskPrefill()
})

// The identity (and so the persistence bucket) can land after mount while the
// profile request is in flight: re-read once the user is known, guarded by the
// empty-thread check inside loadHistory so a live conversation is never reloaded.
watch(() => auth.user?.userId, () => {
  bindChatComposerPrefs(composerPrefsBucket())
  composerRows.value = readComposerRows(localStorage, composerPrefsBucket(), composerMaxRows.value)
  void loadHistory()
  void loadPasteTableConvertSwitch()
  void loadComposerMaxRowsSwitch()
  void loadChatScrollBudget()
})

onBeforeUnmount(() => {
  if (handle) handle.close()
  // Leaving the surface (standalone page navigation) drops the mirror flag;
  // the dock shell never unmounts its panel, so a collapsed-dock stream keeps
  // its halo through this seam untouched.
  setChatStreaming(false)
})
</script>

<style scoped>
.chat-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
}
.chat-title {
  font-weight: 600;
}
.chat-ops {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chat-icon {
  cursor: pointer;
  font-size: 16px;
}
/* Pin active state : the flipped form must read at a glance. */
.chat-icon--active {
  color: var(--tp-primary, #6366f1);
}
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
}
/* Session library swap : fills the body area with its own scroll box. */
.chat-history-view {
  flex: 1;
  min-height: 0;
  padding: 12px 16px;
}
.chat-msg {
  margin-bottom: 12px;
}
/* Day divider : a hairline with the day word centred — the
 boundary marker, never a heavy bar. */
.chat-day-divider {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 0 10px;
  color: var(--tp-text-secondary, #98a2b3);
  font-size: 12px;
}
.chat-day-divider::before,
.chat-day-divider::after {
  content: '';
  flex: 1;
  /* Hairline rides the divider's own label color (one hue drives the whole
 day marker) instead of --tp-border: on the dark canvas that token reads
 ~1.2:1 against the surface and the day boundary went invisible
 ( vision pass, DOM-measured rgb(30,41,59) on rgb(11,18,32)). */
  border-top: 1px solid currentColor;
  opacity: 0.45;
}
.chat-day-divider-label {
  flex: none;
}
/* Role row: label rides the outside edge so the reading direction is obvious
   at a glance (question right capsule / answer left card, §sixteen rule 5). */
.chat-msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.chat-msg-role {
  flex: none;
  margin-top: 6px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-msg-main {
  min-width: 0;
  max-width: 88%;
}
.chat-msg--user .chat-msg-row {
  flex-direction: row-reverse;
}
.chat-msg--user .chat-msg-main {
  background: var(--tp-primary-weak);
  border-radius: 10px;
  padding: 8px 12px;
}
.chat-msg--assistant .chat-msg-main {
  background: var(--tp-bg-card);
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 10px;
  padding: 8px 12px;
}
.chat-msg-time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-msg-content {
  white-space: pre-wrap;
}

/* Rendered markdown blocks own their line breaks; the plain-text pre-wrap
 would double the spacing inside tables and lists . */
.chat-msg-content--md {
  white-space: normal;
}
/* Process zone / answer boundary : 6px with no
 divider left the trace block visually glued to the markdown answer body,
 so the two functional zones read as one. Hairline + breathing room,
 same theme border token as the input row divider. */
.chat-process {
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
}
.chat-process-toggle {
  background: none;
  border: none;
  color: var(--tp-text-secondary, #98a2b3);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 0;
  /* Vision round (plan card faces): the collapsed-analysis caption with its
     step count broke onto two lines at narrow widths; the caption is a
     fixed short label, so keep it on one line. */
  white-space: nowrap;
}
.chat-process-list {
  margin: 4px 0 0;
  padding: 0 0 0 14px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
  list-style: none;
}
/* stage 4 vision round 6: reasoning fragments arrive as hundreds of
 tiny pieces and the space that separates words rides on the fragment
 boundary; the default `normal` collapsing swallowed every one of them
 (a two-word fragment pair "so this" rendered glued as "sothis"). `break-spaces` keeps the wire
 spaces visible while still wrapping long paragraphs at word boundaries
 (plain `pre-wrap` would hard-break on every embedded newline mid-line). */
.chat-process-item {
  white-space: break-spaces;
}

/* Mid-turn steering chip : the user's own supplement reads as a spoken
 interjection inside the analysis process, one notch apart from tool lines. */
.chat-inject-chip {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 8px;
  font-size: 11px;
  line-height: 16px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.chat-citations {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chat-citations-label {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-cite-card {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 6px;
  color: inherit;
  cursor: pointer;
  font-size: 12px;
  padding: 4px 8px;
  text-align: left;
  max-width: 100%;
}
.chat-cite-card:hover {
  border-color: var(--el-color-primary, #409eff);
}
.chat-cite-index {
  color: var(--tp-text-secondary, #98a2b3);
  flex: none;
}
.chat-cite-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-cite-kind {
  flex: none;
  color: var(--tp-text-secondary, #98a2b3);
}
/* Flow-guide action buttons : a chip row under the answer —
 same quiet border/rounding family as cite cards, primary-tinted because
 these are doors to act, not references to read. */
.chat-guide-actions {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chat-guide-label {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-guide-action {
  align-self: flex-start;
  background: none;
  border: 1px solid var(--el-color-primary-light-5, rgba(64, 158, 255, 0.5));
  border-radius: 6px;
  color: var(--el-color-primary, #409eff);
  cursor: pointer;
  font-size: 12px;
  min-height: 24px;
  padding: 4px 10px;
}
.chat-guide-action:hover {
  background: var(--el-color-primary-light-9, rgba(64, 158, 255, 0.1));
}
.chat-msg-error {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--el-color-danger, #f56c6c);
  border-radius: 6px;
  color: var(--el-color-danger, #f56c6c);
  font-size: 13px;
}
/* Failure-line actions (red-bordered reason + resend + copy): 24px target
   floor per the reachability rules, bordered to read as actions not text. */
.chat-msg-error-action {
  min-height: 24px;
  padding: 2px 10px;
  border: 1px solid currentColor;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
}
.chat-msg-usage {
  margin-top: 4px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
/* Per-turn rating row : thumbs doors sit under the answer. Hit
 targets stay ≥24px (X group); the active thumb takes the accent colour so
 a recorded rating is readable at a glance without pressing again. */
.chat-feedback {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.chat-feedback-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  min-height: 28px;
  padding: 0 6px;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s ease, border-color 0.15s ease;
}
.chat-feedback-btn:hover { opacity: 1; }
.chat-feedback-btn.is-active {
  opacity: 1;
  border-color: var(--el-color-primary);
}
.chat-feedback-glyph { font-size: 14px; line-height: 1; }
.chat-feedback-link {
  border: none;
  background: transparent;
  padding: 0;
  min-height: 24px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
  cursor: pointer;
  text-decoration: underline;
}
.chat-feedback-reason {
  flex: 1 1 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chat-feedback-reason-ops { display: flex; gap: 8px; }
.chat-feedback-reason-hint {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-feedback-reason-text {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-input {
  display: flex;
  flex-direction: column;
  gap: var(--tp-composer-band-gap, 6px);
  /* top inset rides the theme band-gap token (was 12px + 1px border = 13px
 facing the message band, under the 16px rubric line; close-out) */
  padding: var(--tp-band-gap, 16px) 16px 12px;
  border-top: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
}
/* Composer height grab strip : a hairline across the footer top,
 hover/focus brightens it to hint draggability, touch-action none keeps
 pointer drag working on touch (same affordance law as the dock strip). */
.chat-composer-grab {
  height: 6px;
  margin: -4px -16px 0;
  cursor: ns-resize;
  touch-action: none;
  border-top: 1px solid transparent;
  border-bottom: 1px solid transparent;
}
.chat-composer-grab:hover,
.chat-composer-grab:focus-visible {
  border-bottom-color: var(--tp-border-strong, rgba(148, 163, 184, 0.45));
}
/* WeChat-shape action row : left icon group, right send group, the
 textarea above occupies the full row (thread-aligned, S-17 judge). */
.chat-composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.chat-context-hint {
  font-size: 12px;
  line-height: 1.4;
  opacity: 0.65;
  user-select: none;
}
/* Cross-project boundary band (design option A): readable reminder, one door. */
.chat-project-boundary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  line-height: 1.4;
  padding: 4px 8px;
  border: 1px solid var(--el-color-warning, #e6a23c);
  border-radius: 6px;
  user-select: none;
}
.chat-project-boundary-new {
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  font-size: 12px;
  color: var(--el-color-primary, #409eff);
  white-space: nowrap;
}
.chat-action-group-left,
.chat-action-group-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* Pending-image queue hint : names what the chips will do on send. */
.chat-image-strip-hint {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
  margin-right: 4px;
}
/* Popup rides above the composer, bounded so a long candidate list never
   eats the thread (max-height + its own scroll = S-group worst-case rule). */
.chat-popup {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 6px;
  padding: 4px;
  box-shadow: var(--tp-shadow);
}
.chat-popup-group {
  /* Palette section header: one step up in weight from the rows it owns,
 and a hairline above every group but the first so the reader sees the
 boundary without the model having to trust font size alone (
 closing vision follow-up). */
  font-size: 12px;
  font-weight: 600;
  color: var(--tp-text-secondary, #98a2b3);
  padding: 4px 8px 2px;
}
.chat-popup-group:not(:first-child) {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
}
.chat-popup-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  text-align: left;
  background: none;
  border: none;
  border-radius: 4px;
  color: inherit;
  cursor: pointer;
  font-size: 13px;
  padding: 6px 8px;
}
.chat-popup-item:hover,
.chat-popup-item[aria-selected='true'] {
  background: var(--tp-primary-weak, rgba(56, 189, 248, 0.14));
}
.chat-popup-title {
  /* Scale band: a long title folds with an ellipsis instead of pushing the
     id column out of the row (knife 4a vision follow-up). */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-popup-id {
  flex: 0 0 auto;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-popup-hint {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
  padding: 6px 8px;
}
.chat-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.chat-chips-label {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-image-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chat-suggest-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.chat-suggest-label {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.chat-suggest-chip {
  min-height: 24px;
  padding: 3px 12px;
  border-radius: 999px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.chat-suggest-chip:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
/* Awaiting window : typing dots + dwell stage sentence inside the
 answer bubble. Dots reuse the muted text token (theme single source). */
.chat-awaiting {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0;
}
.chat-typing {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
.chat-typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-secondary);
  animation: chat-typing-pulse 1.2s infinite ease-in-out;
}
.chat-typing i:nth-child(2) {
  animation-delay: 0.18s;
}
.chat-typing i:nth-child(3) {
  animation-delay: 0.36s;
}
.chat-awaiting-stage {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
@keyframes chat-typing-pulse {
  0%, 60%, 100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-2px);
  }
}
</style>
