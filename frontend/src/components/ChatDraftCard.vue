<template>
  <!--
 Draft confirmation card :
 a draft frame landed by the core projection renders its proposal in the
 card's own vocabulary — a SCRIPT_REPAIR shows the field-level diff of the
 proposed action JSON against the case's current script; a CASE_WRITE
 shows the case fields it will create, or the field diff against the case
 as it stands for an edit (never a raw JSON dump). Both kinds share the
 two click doors of the confirmation chain: first click opens the one-shot
 ticket (POST confirm-token, TTL countdown shown), second click applies it
 (POST apply with the ticket). The receipt names its object and version
 (R22). Every rejection maps to its own human line — a 40901 conflict says
 the object moved under the proposal, an expired ticket says re-confirm;
 nothing is ever swallowed silently.
 Interaction state (idle/ticketed/applied) is component-local by design:
 the reducer card stays a pure frame fold, one click never mutates the
 folded ledger shape.
 -->
  <div class="draft-card" data-test="chat-draft-card" tabindex="0" @keydown.enter="onEnter">
    <div class="draft-head">
      <span class="draft-title">{{ t(draftTitleKey) }}</span>
      <router-link v-if="card.caseId > 0" class="draft-case" data-test="chat-draft-case-link" :to="`/cases/${card.caseId}`">
        {{ t('layout.chatDock.draft.caseLink', { id: card.caseId }) }}
      </router-link>
    </div>
    <p class="draft-summary" data-test="chat-draft-summary">{{ card.summary }}</p>
    <!-- CASE_WRITE cards fold their proposal body into case-field lines (a
         create shows what will be written, an edit diffs against the case as
         it stands now) — one vocabulary, never a raw JSON dump. -->
    <template v-if="card.objectType === 'CASE_WRITE'">
      <ul v-if="card.actionKind === 'create'" class="draft-diff draft-fields" data-test="chat-draft-fields">
        <li v-for="line in caseWriteLines" :key="line.field" class="draft-diff-row" data-state="changed">
          <span class="draft-field">{{ fieldWord(line.field) }}</span>
          <span class="draft-after">{{ compactValue(line.value, shapeWord) }}</span>
        </li>
      </ul>
      <ul v-if="caseWriteDiff !== null" class="draft-diff" data-test="chat-draft-diff">
        <li v-for="c in caseWriteDiff" :key="c.field" class="draft-diff-row" :data-state="c.state">
          <span class="draft-field">{{ fieldWord(c.field) }}</span>
          <template v-if="c.state === 'changed'">
            <span class="draft-before">{{ compactValue(c.before, shapeWord) }}</span>
            <span class="draft-arrow">→</span>
            <span class="draft-after">{{ compactValue(c.after, shapeWord) }}</span>
          </template>
          <span v-else class="draft-after">{{ t(`layout.chatDock.draft.${c.state}`) }}: {{
            c.state === 'added' ? compactValue(c.after, shapeWord) : compactValue(c.before, shapeWord) }}</span>
        </li>
        <li v-if="caseWriteDiff.length === 0" class="draft-none" data-test="chat-draft-nochange">
          {{ t('layout.chatDock.draft.noFieldChange') }}
        </li>
      </ul>
      <p v-else-if="card.actionKind === 'edit'" class="draft-none" data-test="chat-draft-nodiff">{{ t('layout.chatDock.draft.noDiff') }}</p>
      <ul v-if="stepLines" class="draft-diff draft-steps" data-test="chat-draft-steps">
        <li v-for="s in stepLines" :key="s.stepNo" class="draft-diff-row">
          <span class="draft-field">{{ t('layout.chatDock.draft.stepNo', { n: s.stepNo }) }}</span>
          <span class="draft-after">{{ s.description }}</span>
          <span v-if="s.isAssert" class="draft-assert-tag">{{ t('layout.chatDock.draft.stepAssert') }}</span>
        </li>
      </ul>
      <p v-else-if="stepCountLine" class="draft-none" data-test="chat-draft-stepcount">{{ stepCountLine }}</p>
    </template>
    <template v-else-if="card.objectType === 'REQUIREMENT_WRITE'">
      <!-- Requirement cards fold the proposal into the requirement fields it
           writes (title / acceptance criteria / chapter) with the SAME
           vocabulary the requirement pages already use — never a script diff,
           never a raw JSON dump. -->
      <ul class="draft-diff draft-fields" data-test="chat-draft-requirement-fields">
        <li v-for="line in requirementLines" :key="line.field" class="draft-diff-row" data-state="changed">
          <span class="draft-field">{{ requirementFieldWord(line.field) }}</span>
          <span class="draft-after" :title="fullValueTitle(line.value)">{{ compactValue(line.value, shapeWord) }}</span>
        </li>
      </ul>
      <p v-if="requirementDual" class="draft-none" data-test="chat-draft-dual-diff">
        {{ t('layout.chatDock.draft.dualDiff', { id: Number(requirementDual.update.targetId),
          version: Number(requirementDual.update.baseVersion) }) }}
      </p>
    </template>
    <template v-else-if="card.objectType === 'EVOLUTION_PROPOSAL'">
      <!-- Evolution proposal cards fold their payload into object-name lines
 (family, target, body preview) — never a raw JSON dump (the same
 vocabulary rule the case cards follow, stage 5). -->
      <ul class="draft-diff draft-fields" data-test="chat-draft-evolution-fields">
        <li v-for="line in evolutionLines" :key="line.field" class="draft-diff-row" data-state="changed">
          <span class="draft-field">{{ evolutionFieldWord(line.field) }}</span>
          <span class="draft-after" :title="fullValueTitle(line.value)">{{ compactValue(line.value, shapeWord) }}</span>
        </li>
      </ul>
    </template>
    <template v-else-if="card.objectType === 'API_ACCESS_WRITE'">
      <!-- Pass-through cards fold the catalog entry and the declared arguments it will send into
           readable lines (one vocabulary with the case/requirement/evolution cards, never a raw
           JSON dump). Argument names are the endpoint's own parameter names — that is exactly what
           a human must read before approving a call against this application's own door, so an
           unknown name travels verbatim instead of being silently dropped. -->
      <ul class="draft-diff draft-fields" data-test="chat-draft-api-access-fields">
        <li v-for="line in apiAccessLines" :key="line.field" class="draft-diff-row" data-state="changed">
          <span class="draft-field">{{ apiAccessFieldWord(line.field) }}</span>
          <span class="draft-after" :title="fullValueTitle(line.value)">{{ compactValue(line.value, shapeWord) }}</span>
        </li>
      </ul>
    </template>
    <template v-else-if="card.objectType === 'LOGIN_STATE_WRITE'">
      <!-- Login-state cards render the full candidate table
 as a check list: every row the server parsed rides the snapshot
 (unticked rows included), and ticking is the authorisation act —
 the subset is written back before the confirm ticket is asked for.
 Values never appear on the card and never travel with the edit. -->
      <p v-if="loginStateRows.length === 0" class="draft-none" data-test="chat-draft-loginstate-empty">
        {{ t('layout.chatDock.draft.loginStateUnreadable') }}
      </p>
      <ul v-else class="draft-diff draft-fields" data-test="chat-draft-loginstate-rows">
        <li v-for="row in loginStateRows" :key="row.name" class="draft-diff-row">
          <el-checkbox
            :model-value="loginChecked.has(row.name)"
            :disabled="stage !== 'idle' || terminalFailure"
            data-test="chat-draft-loginstate-tick"
            @change="toggleLoginStateRow(row.name)">
            {{ row.name }}
          </el-checkbox>
        </li>
      </ul>
      <p v-if="loginStateRows.length > 0" class="draft-none" data-test="chat-draft-loginstate-count">
        {{ t('layout.chatDock.draft.loginStateCount', { n: loginChecked.size }) }}
      </p>
    </template>
    <template v-else>
      <ul v-if="changes" class="draft-diff" data-test="chat-draft-diff">
      <li v-for="c in changes" :key="c.field" class="draft-diff-row" :data-state="c.state">
        <span class="draft-field">{{ c.field }}</span>
        <template v-if="c.state === 'changed'">
          <span class="draft-before">{{ compactValue(c.before, shapeWord) }}</span>
          <span class="draft-arrow">→</span>
          <span class="draft-after">{{ compactValue(c.after, shapeWord) }}</span>
        </template>
        <span v-else class="draft-after">{{ t(`layout.chatDock.draft.${c.state}`) }}: {{
          c.state === 'added' ? compactValue(c.after, shapeWord) : compactValue(c.before, shapeWord) }}</span>
      </li>
      <li v-if="changes.length === 0" class="draft-none" data-test="chat-draft-nochange">
        {{ t('layout.chatDock.draft.noChange') }}
      </li>
      </ul>
      <p v-else class="draft-none" data-test="chat-draft-nodiff">{{ t('layout.chatDock.draft.noDiff') }}</p>
    </template>

    <div class="draft-ops">
      <template v-if="stage === 'applied'">
        <span class="draft-receipt" data-test="chat-draft-receipt">{{ appliedLine }}</span>
        <!-- Landed evolution proposals keep an undo door (AF-i phase 3): the
             prompt family rolls back through its version chain; tool/grounding
             families honestly surface the core refusal naming where the
             object is managed (the frontend never pre-judges the kind). -->
        <el-button
          v-if="card.objectType === 'EVOLUTION_PROPOSAL' && !landedRolledBack"
          size="small"
          data-test="chat-draft-rollback"
          :loading="rollingBack"
          @click="rollback"
        >
          {{ t('layout.chatDock.draft.rollback') }}
        </el-button>
      </template>
      <!-- Voided ledger row: the proposal is dead, the door is honestly
           closed with its reason instead of a silent re-openable confirm. -->
      <template v-else-if="landedVoided">
        <el-button type="primary" size="small" data-test="chat-draft-confirm" disabled>
          {{ t('layout.chatDock.draft.confirm') }}
        </el-button>
        <span class="draft-hint" data-test="chat-draft-voided">
          {{ t('layout.chatDock.draft.landedVoided') }}
        </span>
      </template>
      <template v-else-if="stage === 'ticketed'">
        <el-button type="primary" size="small" data-test="chat-draft-apply" :disabled="applying || terminalFailure" @click="apply">
          {{ requirementDual
            ? t('layout.chatDock.draft.applyBranch', { branch: branchWord(selectedAction, requirementDual) })
            : t('layout.chatDock.draft.apply') }}
        </el-button>
        <el-button v-if="requirementDual" size="small" data-test="chat-draft-branch-switch"
          :disabled="applying || terminalFailure" @click="switchBranch">
          {{ selectedAction === 'update'
            ? t('layout.chatDock.draft.branchCreate')
            : t('layout.chatDock.draft.branchUpdate', { id: Number(requirementDual.update.targetId) }) }}
        </el-button>
        <span class="draft-ttl" data-test="chat-draft-ttl">
          {{ t('layout.chatDock.draft.ticketLeft', { seconds: ticketSeconds }) }}
        </span>
      </template>
      <template v-else-if="requirementDual">
        <el-button type="primary" size="small" data-test="chat-draft-branch-update"
          :disabled="loadingTicket || terminalFailure" @click="chooseBranch('update')">
          {{ t('layout.chatDock.draft.branchUpdate', { id: Number(requirementDual.update.targetId) }) }}
        </el-button>
        <el-button size="small" data-test="chat-draft-branch-create"
          :disabled="loadingTicket || terminalFailure" @click="chooseBranch('create')">
          {{ t('layout.chatDock.draft.branchCreate') }}
        </el-button>
        <span class="draft-hint" data-test="chat-draft-dual-hint">{{ t('layout.chatDock.draft.dualHint') }}</span>
      </template>
      <template v-else>
        <el-button type="primary" size="small" data-test="chat-draft-confirm" :disabled="loadingTicket || terminalFailure || zeroTickBlocked" @click="openTicket">
          {{ t('layout.chatDock.draft.confirm') }}
        </el-button>
        <!-- A closed door does not keep explaining the road: once a refusal is terminal the hint
             that asks the user to confirm would contradict the reason line right beside it. -->
        <span v-if="!terminalFailure" class="draft-hint">{{ t(zeroTickBlocked ? 'layout.chatDock.draft.loginStateNeedTick' : draftHintKey) }}</span>
      </template>
      <p v-if="failureLine" class="draft-error" data-test="chat-draft-error">{{ failureLine }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { BizError } from '@/api/request'
import { getCase, getCaseScript } from '@/api/case'
import { applyRepairDraft, requestDraftConfirmTicket, rollbackEvolutionProposal, updateDraftCheckedSubset } from '@/api/aiChat'
import { compactValue, diffActionJson, type RepairFieldChange } from '@/composables/chatRepairDiff'
import { caseWriteFieldLines, diffCaseFields, readableStepLines, stepSummary } from '@/composables/chatCaseWriteFold'
import type { RepairDraftCard } from '@/composables/chatDockReducer'

const props = defineProps<{ card: RepairDraftCard }>()

const { t } = useI18n()

/**
 * Card title routes off the folded objectType : one component,
 * per-family wording, no second card clone. Evolution families get their own
 * title per subKind; an unknown family (unparsable payload) still titles as a
 * generic proposal so the confirmation door never renders without a name.
 */
const draftTitleKey = computed(() => {
  if (props.card.objectType === 'EVOLUTION_PROPOSAL') {
    return `layout.chatDock.draft.titleEvolution${props.card.subKind || 'Unknown'}`
  }
  if (props.card.objectType === 'CASE_WRITE') {
    return props.card.actionKind === 'create'
      ? 'layout.chatDock.draft.titleCaseCreate'
      : 'layout.chatDock.draft.titleCaseEdit'
  }
  // AL batch writeback cards (D10): a REQUIREMENT_WRITE draft must title
  // itself in the requirement family — the script-repair default told the
  if (props.card.objectType === 'REQUIREMENT_WRITE') {
    return props.card.actionKind === 'create'
      ? 'layout.chatDock.draft.titleRequirementCreate'
      : 'layout.chatDock.draft.titleRequirementEdit'
  }
  // A pass-through card titles itself as the interface change it is: the script-repair default
  // would tell the human the click writes a script, on a door that calls this application's own
  // endpoint instead
  // (same family as the REQUIREMENT_WRITE correction above).
  if (props.card.objectType === 'API_ACCESS_WRITE') return 'layout.chatDock.draft.titleApiAccess'
  // A login-state card names its own family : the door
  // registers login states, it never writes a script.
  if (props.card.objectType === 'LOGIN_STATE_WRITE') return 'layout.chatDock.draft.titleLoginState'
  // A tag-create card names its own family : the door creates a
  // tag in the session's project, it never writes a script.
  if (props.card.objectType === 'TAG_WRITE') return 'layout.chatDock.draft.titleTagWrite'
  // Undo cards of the repeated-edit batch): the rollback draft must
  // name its own family on the door — the generic script-repair title would
  // tell the human the click writes a script while it actually rolls the case
  // back to the version the summary names.
  if (props.card.objectType === 'CASE_ROLLBACK') return 'layout.chatDock.draft.titleCaseRollback'
  return 'layout.chatDock.draft.title'
})

/** Idle hint names what the second click writes for THIS family. */
const draftHintKey = computed(() => {
  if (props.card.objectType === 'EVOLUTION_PROPOSAL') return 'layout.chatDock.draft.evolutionHint'
  if (props.card.objectType === 'REQUIREMENT_WRITE') return 'layout.chatDock.draft.requirementHint'
  if (props.card.objectType === 'API_ACCESS_WRITE') return 'layout.chatDock.draft.apiAccessHint'
  if (props.card.objectType === 'LOGIN_STATE_WRITE') return 'layout.chatDock.draft.loginStateHint'
  if (props.card.objectType === 'TAG_WRITE') return 'layout.chatDock.draft.tagWriteHint'
  if (props.card.objectType === 'CASE_ROLLBACK') return 'layout.chatDock.draft.caseRollbackHint'
  return props.card.objectType === 'CASE_WRITE' ? 'layout.chatDock.draft.caseHint' : 'layout.chatDock.draft.hint'
})

/**
 * Evolution payload folded to readable object-name lines (never a JSON dump):
 * family, target the receipt names, and a short body preview so the human
 * sees what they are confirming. Unparsable payload honestly says the content
 * is unreadable instead of rendering an empty promise.
 */
const evolutionLines = computed<Array<{ field: string; value: string }>>(() => {
  let payload: Record<string, unknown> | null = null
  try {
    payload = JSON.parse(props.card.actionJson) as Record<string, unknown>
  } catch {
    payload = null
  }
  if (!payload) {
    return [{ field: 'content', value: t('layout.chatDock.draft.evolutionUnreadable') }]
  }
  const lines: Array<{ field: string; value: string }> = []
  const name = typeof payload.name === 'string' ? payload.name
    : typeof payload.filename === 'string' ? payload.filename : ''
  if (name) lines.push({ field: 'target', value: name })
  if (typeof payload.domain === 'string') lines.push({ field: 'domain', value: payload.domain })
  if (typeof payload.description === 'string') lines.push({ field: 'description', value: payload.description })
  if (typeof payload.content === 'string') {
    const flat = payload.content.replace(/\s+/g, ' ').trim()
    lines.push({ field: 'content', value: flat.length > 60 ? `${flat.slice(0, 60)}…` : flat })
  }
  return lines
})

function evolutionFieldWord(field: string): string {
  const known: Record<string, string> = {
    target: t('layout.chatDock.draft.evoTarget'),
    domain: t('layout.chatDock.draft.evoDomain'),
    description: t('layout.chatDock.draft.evoDescription'),
    content: t('layout.chatDock.draft.evoContent'),
  }
  return known[field] ?? field
}

/**
 * Requirement proposal folded to readable field lines (AL batch D10): the
 * field names ride the requirement pages' own vocabulary (requirement.*),
 * one concept one word (W-group). An unparsable payload keeps the shared
 * honest-unreadable line — the confirmation door stays real either way.
 */
/** The woven either-or shape the card-time duplicate check produces. */
interface DualRequirementBranches {
  create: Record<string, unknown>
  update: Record<string, unknown>
}

/**
 * Dual-payload fold : a requirement create proposal
 * whose card-time duplicate check hit an existing row is woven into
 * {create, update} core-side. The field lines fold the proposed content off
 * the create branch — both branches write the same content, only the landing
 * row differs; single-shape cards keep parsing their body directly and an
 * unparsable payload honestly stays unreadable instead of rendering empty.
 */
const requirementDual = computed<DualRequirementBranches | null>(() => {
  if (props.card.objectType !== 'REQUIREMENT_WRITE') return null
  let payload: Record<string, unknown> | null = null
  try {
    payload = JSON.parse(props.card.actionJson) as Record<string, unknown>
  } catch {
    return null
  }
  const create = payload?.create
  const update = payload?.update
  if (create && typeof create === 'object' && update && typeof update === 'object') {
    return { create: create as Record<string, unknown>, update: update as Record<string, unknown> }
  }
  return null
})

/** Locale word for the held branch (the apply door names what it will write). */
function branchWord(action: 'create' | 'update' | null, dual: DualRequirementBranches): string {
  return action === 'create'
    ? t('layout.chatDock.draft.branchCreate')
    : t('layout.chatDock.draft.branchUpdate', { id: Number(dual.update.targetId ?? 0) })
}

const requirementLines = computed<Array<{ field: string; value: string }>>(() => {
  const dual = requirementDual.value
  let payload: Record<string, unknown> | null = dual ? dual.create : null
  if (!dual) {
    try {
      payload = JSON.parse(props.card.actionJson) as Record<string, unknown>
    } catch {
      payload = null
    }
  }
  if (!payload) {
    return [{ field: 'content', value: t('layout.chatDock.draft.evolutionUnreadable') }]
  }
  const lines: Array<{ field: string; value: string }> = []
  if (!dual && props.card.requirementId > 0) {
    lines.push({ field: 'target', value: `#${props.card.requirementId}` })
  }
  if (typeof payload.title === 'string' && payload.title.trim()) lines.push({ field: 'title', value: payload.title.trim() })
  if (typeof payload.acceptanceCriteria === 'string' && payload.acceptanceCriteria.trim()) {
    lines.push({ field: 'acceptanceCriteria', value: payload.acceptanceCriteria.trim() })
  }
  if (typeof payload.chapter === 'string' && payload.chapter.trim()) {
    lines.push({ field: 'chapter', value: payload.chapter.trim() })
  }
  if (lines.length === (props.card.requirementId > 0 ? 1 : 0)) {
    lines.push({ field: 'content', value: t('layout.chatDock.draft.evolutionUnreadable') })
  }
  return lines
})

/**
 * Pass-through payload folded to readable lines: the catalog entry being called, the owning project
 * (the platform re-stamps it from the chat session, and the card says so instead of pretending the
 * payload decides), then every declared argument the change will send.
 */
const apiAccessLines = computed<Array<{ field: string; value: string }>>(() => {
  let payload: Record<string, unknown> | null = null
  try {
    payload = JSON.parse(props.card.actionJson) as Record<string, unknown>
  } catch {
    payload = null
  }
  const endpoint = typeof payload?.endpointKey === 'string' ? payload.endpointKey.trim() : ''
  if (!endpoint) {
    return [{ field: 'note', value: t('layout.chatDock.draft.apiAccessUnreadable') }]
  }
  const args = payload?.arguments && typeof payload.arguments === 'object'
    ? payload.arguments as Record<string, unknown>
    : {}
  const lines: Array<{ field: string; value: string }> = [{ field: 'endpoint', value: endpoint }]
  const project = Number(args.projectId ?? 0)
  if (project > 0) lines.push({ field: 'project', value: `#${project}` })
  for (const [key, value] of Object.entries(args)) {
    if (key === 'projectId') continue
    if (value === null || value === undefined || value === '') continue
    lines.push({ field: key, value: typeof value === 'string' ? value.trim() : JSON.stringify(value) })
  }
  return lines
})

function apiAccessFieldWord(field: string): string {
  const known: Record<string, string> = {
    endpoint: t('layout.chatDock.draft.apiAccessEndpoint'),
    project: t('layout.chatDock.draft.apiAccessProject'),
    note: t('layout.chatDock.draft.apiAccessNote'),
  }
  return known[field] ?? field
}

function requirementFieldWord(field: string): string {
  const known: Record<string, string> = {
    target: t('layout.chatDock.draft.evoTarget'),
    title: t('requirement.title'),
    acceptanceCriteria: t('requirement.acceptanceCriteria'),
    chapter: t('requirement.chapter'),
    content: t('layout.chatDock.draft.evoContent'),
  }
  return known[field] ?? field
}

/**
 * Locale wording for collapsed values (vision T429s3c after-round: the raw
 * bracket shapes `[2]`/`{3}` read as internal notation on screen — the card
 * names what the user actually sees: N items / N fields).
 */
function shapeWord(kind: 'array' | 'object', count: number): string {
  return kind === 'array'
    ? t('layout.chatDock.draft.shapeArray', { n: count })
    : t('layout.chatDock.draft.shapeObject', { n: count })
}

/**
 * Human word for one case field of a CASE_WRITE proposal — the same locale
 * vocabulary the case list/detail already use (case.*), so the card never
 * invents a third name for the same field (W-group: one concept one word).
 */
function fieldWord(field: string): string {
  const known: Record<string, string> = {
    title: t('case.targetTitle'),
    precondition: t('case.precondition'),
    expectedResult: t('case.expectedResult'),
    priority: t('case.priority'),
    confidence: t('case.confidence'),
    endType: t('case.endType'),
    phase: t('case.phase'),
    steps: t('case.steps'),
  }
  return known[field] ?? field
}

/**
 * Diff source of truth = the case's CURRENT script (the draft only knows the
 * base version it was cut against; a moved script must still read honestly —
 * the optimistic lock at apply time is what refuses the write, the card only
 * displays). Load failures degrade to the "cannot compare" line, never a fake
 * empty diff. CASE_WRITE edit loads the case row itself instead — its diff is
 * over case fields, not script JSON.
 */
const currentJson = ref<string | null | undefined>(undefined)
if (props.card.objectType === 'SCRIPT_REPAIR') {
  void getCaseScript(props.card.caseId)
    .then((script) => { currentJson.value = script?.actionJson ?? null })
    .catch(() => { currentJson.value = null })
}
const currentCase = ref<Record<string, unknown> | null | undefined>(undefined)
if (props.card.objectType === 'CASE_WRITE' && props.card.actionKind === 'edit') {
  void getCase(props.card.caseId)
    .then((row) => { currentCase.value = row as unknown as Record<string, unknown> })
    .catch(() => { currentCase.value = null })
}

const changes = computed<RepairFieldChange[] | null>(() => {
  if (props.card.objectType !== 'SCRIPT_REPAIR') return null
  if (currentJson.value === undefined) return null
  return diffActionJson(currentJson.value, props.card.actionJson)
})

/** Create card: the fields the proposal writes, folded to readable lines. */
const caseWriteLines = computed(() => (props.card.actionKind === 'create' ? caseWriteFieldLines(props.card.actionJson) ?? [] : []))

/** Edit card: what changes against the current case (null = cannot compare). */
const caseWriteDiff = computed<RepairFieldChange[] | null>(() => {
  if (props.card.objectType !== 'CASE_WRITE' || props.card.actionKind !== 'edit') return null
  if (currentCase.value === undefined) return null
  return diffCaseFields(currentCase.value, props.card.actionJson)
})

/** Step lines when proposed steps carry readable text; else null and the
 *  count sentence inside the field/diff fold above is all the card shows. */
const stepLines = computed(() =>
  props.card.objectType === 'CASE_WRITE' ? readableStepLines(props.card.actionJson) : null,
)

/** Honest fallback when a proposal carries steps without readable text: the
 *  N-steps count sentence (shapeArray locale), never an empty step zone. */
const stepCountLine = computed(() => {
  if (props.card.objectType !== 'CASE_WRITE') return ''
  const summary = stepSummary(props.card.actionJson)
  return summary ? t('layout.chatDock.draft.shapeArray', { n: summary.steps }) : ''
})

/**
 * Login-state candidate fold . The server snapshot carries
 * EVERY parsed row (unticked ones with selected:false); the card renders the
 * whole table and the human re-ticks before the door opens. Names are the
 * only thing read and written — values live server-side in the transient
 * capture slot and never appear on screen.
 */
const loginStateRows = computed<Array<{ name: string; selected: boolean }>>(() => {
  if (props.card.objectType !== 'LOGIN_STATE_WRITE') return []
  try {
    const payload = JSON.parse(props.card.actionJson) as { entries?: unknown }
    const rows = Array.isArray(payload.entries) ? payload.entries : []
    const out: Array<{ name: string; selected: boolean }> = []
    for (const raw of rows) {
      const row = raw as Record<string, unknown>
      const name = typeof row.name === 'string' ? row.name.trim() : ''
      if (!name) continue
      out.push({ name, selected: row.selected === true })
    }
    return out
  } catch {
    return []
  }
})

/** State name the receipt prints (payload anchor; '' keeps the honest fallback). */
const loginStateName = computed<string>(() => {
  if (props.card.objectType !== 'LOGIN_STATE_WRITE') return ''
  try {
    const payload = JSON.parse(props.card.actionJson) as { stateName?: unknown }
    return typeof payload.stateName === 'string' ? payload.stateName.trim() : ''
  } catch {
    return ''
  }
})

/** Live ticks held locally; a fresh Set per edit keeps Vue reactivity honest. */
const loginChecked = ref<Set<string>>(
  new Set(loginStateRows.value.filter((row) => row.selected).map((row) => row.name)),
)

function toggleLoginStateRow(name: string) {
  const next = new Set(loginChecked.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  loginChecked.value = next
}

/** True while the local ticks differ from the snapshot defaults (skip the
 *  write-back when the human changed nothing — core would no-op it anyway). */
const loginSubsetDirty = computed(() => {
  if (props.card.objectType !== 'LOGIN_STATE_WRITE') return false
  const snapshot = new Set(loginStateRows.value.filter((row) => row.selected).map((row) => row.name))
  if (snapshot.size !== loginChecked.value.size) return true
  for (const name of loginChecked.value) {
    if (!snapshot.has(name)) return true
  }
  return false
})

/** Zero ticks authorises nothing: the door is honestly disabled, and the
 *  hint names the missing act instead of a silent dead button. */
const zeroTickBlocked = computed(() =>
  props.card.objectType === 'LOGIN_STATE_WRITE' && loginChecked.value.size === 0)

/** Confirmation stage of THIS card (component-local, see the header comment).
 * A reloaded card seeds its stage straight from the ledger state (
 * stage 5 fix : an applied proposal re-renders its receipt instead
 * of pretending to be new, and a voided one keeps an honest closed door.
 * Live-stream cards carry no landed stamp — they always start idle. */
const landed = props.card.landed
const landedVoided = landed?.status === 3
const landedRolledBack = landed?.rolledBack === true
const stage = ref<'idle' | 'ticketed' | 'applied'>(landed?.status === 2 ? 'applied' : 'idle')
const loadingTicket = ref(false)
const applying = ref(false)
const ticketSeconds = ref(0)
const receiptCaseId = ref(landed?.status === 2 ? props.card.caseId : 0)
const receiptVersion = ref(landed?.status === 2 ? (landed.appliedVersion ?? 0) : 0)
/** Rollback door state (evolution cards only, applied stage). A reloaded
 *  rolled-back card shows the once-only line without re-asking the server. */
const rollingBack = ref(false)
const rolledBackLine = ref('')
/** Held between the two clicks — the browser never re-sends a guessed ticket. */
let ticket = ''
let ticker: ReturnType<typeof setInterval> | null = null

/**
 * Applied receipt names its object per family (R22): the evolution card
 * reports the proposal's own target (template family/tool row/document) and
 * the version where the chain moved, never the case-centric wording a repair
 * card uses — a prompt proposal has no case id to name in its receipt.
 */
const appliedLine = computed(() => {
  if (rolledBackLine.value) return rolledBackLine.value
  // A reloaded card already stamped rolled-back shows the once-only outcome
  // line; the rollback door itself is gone (never offered a second time).
  if (landedRolledBack) return t('layout.chatDock.draft.landedRolledBack')
  if (props.card.objectType === 'EVOLUTION_PROPOSAL') {
    const name = props.card.targetName || t('layout.chatDock.draft.evolutionUnknownTarget')
    // Tool/grounding families have no version chain (the core strategy lands
    // a draft tool / a document); claiming "version N" there would be a
    // fabricated number — the receipt names the object and what it became.
    if (!receiptVersion.value) {
      return props.card.subKind === 'TOOL_DEF'
        ? t('layout.chatDock.draft.appliedEvolutionTool', { name })
        : t('layout.chatDock.draft.appliedEvolutionDoc', { name })
    }
    return t('layout.chatDock.draft.appliedEvolution', { name, version: receiptVersion.value })
  }
  if (props.card.objectType === 'CASE_WRITE' && props.card.actionKind === 'create') {
    return t('layout.chatDock.draft.appliedCaseCreate', { id: receiptCaseId.value, version: receiptVersion.value })
  }
  if (props.card.objectType === 'CASE_WRITE') {
    return t('layout.chatDock.draft.appliedCaseEdit', { id: receiptCaseId.value, version: receiptVersion.value })
  }
  // The apply receipt re-reads the ledger row after landing, so a created
  // requirement already carries its new id in receipt.targetId — the receipt
  // names the requirement it wrote, never the case-centric default.
  if (props.card.objectType === 'REQUIREMENT_WRITE'
      && (requirementDual.value ? selectedAction.value === 'create' : props.card.actionKind === 'create')) {
    return t('layout.chatDock.draft.appliedRequirementCreate', { id: receiptCaseId.value, version: receiptVersion.value })
  }
  if (props.card.objectType === 'REQUIREMENT_WRITE') {
    return t('layout.chatDock.draft.appliedRequirementEdit', { id: receiptCaseId.value, version: receiptVersion.value })
  }
  // A pass-through change may create an object (the landing door returns the produced id) or move
  // an existing one; the receipt names the entry that ran and says honestly when no id came back,
  // instead of printing "case #0 version null" from the case-centric default.
  if (props.card.objectType === 'API_ACCESS_WRITE') {
    const endpoint = apiAccessLines.value[0]?.value ?? ''
    return receiptCaseId.value > 0
      ? t('layout.chatDock.draft.appliedApiAccessProduced', { endpoint, id: receiptCaseId.value })
      : t('layout.chatDock.draft.appliedApiAccess', { endpoint })
  }
  // The login-state receipt names the state it registered —
  // the case-centric default would print "case #0" on a vault registration.
  if (props.card.objectType === 'LOGIN_STATE_WRITE') {
    return t('layout.chatDock.draft.appliedLoginState', { name: loginStateName.value })
  }
  return t('layout.chatDock.draft.applied', { id: receiptCaseId.value, version: receiptVersion.value })
})

/**
 * Undo a landed proposal through the core door: the prompt version chain
 * copies its predecessor forward (never an in-place rewrite). Every guard
 * answer arrives as the server's own sentence — a tool/grounding card shows
 * exactly where the object is managed, so no second refusal wording is
 * invented client-side (W-group: one concept one word, backend names the
 * remedy). A completed rollback replaces the receipt with its outcome.
 */
async function rollback() {
  if (rollingBack.value) return
  rollingBack.value = true
  failureKey.value = null
  genericMsg.value = ''
  try {
    const rolled = await rollbackEvolutionProposal(props.card.draftId)
    rolledBackLine.value = t('layout.chatDock.draft.rollbackDone', {
      name: rolled.name, version: rolled.version,
    })
  } catch (err) {
    // Refusals ride the generic line with the server's sentence verbatim (it
    // already names the kind, the state, or the place to manage the object).
    genericMsg.value = err instanceof BizError ? err.message : ''
    failureKey.value = 'generic'
  } finally {
    rollingBack.value = false
  }
}

/** First click: open the one-shot ticket and start the countdown echo. */
async function openTicket() {
  if (loadingTicket.value) return
  loadingTicket.value = true
  failureKey.value = null
  try {
    // Login-state cards authorise a ticked subset: when the human re-ticked,
    // the write-back runs BEFORE the ticket — a refused subset (a name the
    // snapshot does not carry) surfaces the server sentence and costs no
    // ticket .
    if (props.card.objectType === 'LOGIN_STATE_WRITE' && loginSubsetDirty.value) {
      await updateDraftCheckedSubset(props.card.draftId, [...loginChecked.value])
    }
    const granted = await requestDraftConfirmTicket(props.card.draftId)
    ticket = granted.token
    ticketSeconds.value = granted.expiresInSeconds
    stage.value = 'ticketed'
    ticker = setInterval(() => {
      if (ticketSeconds.value > 0) {
        ticketSeconds.value -= 1
        return
      }
      stopTicker()
      ticket = ''
      stage.value = 'idle'
      // Honest expiry line: the ticket is gone, the draft is not — re-confirm.
      failureKey.value = 'ticketExpired'
    }, 1000)
  } catch (err) {
    failureKey.value = failureKeyOf(err)
    genericMsg.value = err instanceof BizError ? err.message : ''
  } finally {
    loadingTicket.value = false
  }
}

/**
 * Either-or branch held between the door clicks (dual-payload requirement
 * card, ). The one-shot ticket belongs to the draft row, not to a
 * branch — switching branches costs no new confirmation click, the choice
 * only rides onto the apply body and the core landing door honours it.
 */
const selectedAction = ref<'create' | 'update' | null>(null)

/** First click on one branch door: record the choice, open the shared ticket. */
function chooseBranch(action: 'create' | 'update') {
  if (loadingTicket.value) return
  selectedAction.value = action
  void openTicket()
}

/** Swap the held branch while the ticket is still live. */
function switchBranch() {
  if (applying.value || !requirementDual.value) return
  selectedAction.value = selectedAction.value === 'update' ? 'create' : 'update'
}

/** Enter on the focused card accepts the recommended branch (dual cards
 *  default to the change side — the duplicate gate already named the hit on
 *  the card face). A click on a focused button rides native keyboard
 *  activation instead, so this handler never double-fires. */
function onEnter(event: Event) {
  if ((event.target as HTMLElement)?.tagName === 'BUTTON') return
  if (!requirementDual.value) return
  if (stage.value === 'idle') chooseBranch('update')
  else if (stage.value === 'ticketed') void apply()
}

/** Second click: apply with the held ticket (single source: nothing else
 *  rides; a dual card also rides its chosen branch). */
async function apply() {
  if (applying.value || ticket === '') return
  applying.value = true
  try {
    const receipt = await applyRepairDraft(
      props.card.draftId,
      ticket,
      requirementDual.value ? (selectedAction.value ?? 'update') : undefined,
    )
    ticket = ''
    stopTicker()
    ticketSeconds.value = 0
    stage.value = 'applied'
    // Generic receipt projected onto the repair card's case/version view
    // .
    receiptCaseId.value = receipt.targetId ?? 0
    receiptVersion.value = receipt.appliedVersion ?? 0
  } catch (err) {
    stage.value = 'idle'
    failureKey.value = failureKeyOf(err)
    genericMsg.value = err instanceof BizError ? err.message : ''
  } finally {
    applying.value = false
  }
}

function stopTicker() {
  if (ticker) clearInterval(ticker)
  ticker = null
}

/**
 * Biz codes of the confirmation chain (core RepairDraftConfirmationService,
 * five-refusal spectrum): 40901 names the real cause (script moved — re-check
 * and re-confirm against the new version), 40001 covers missing/used/expired
 * tickets (re-open the door), anything else keeps the server sentence visible
 * through the generic line. Never silent (W-group: name the object and the
 * consequence).
 */
function failureKeyOf(err: unknown): string {
  if (err instanceof BizError) {
    // The conflict line names WHAT moved: a script (repair card) or the case
    // row (case-write edit) — one concept one word on screen.
    if (err.code === '40901') return props.card.objectType === 'CASE_WRITE' ? 'caseConflict' : 'conflict'
    if (err.code === '40001') return 'ticketInvalid'
    if (err.code === '40904') return 'notPending'
    if (err.code === '40401') return 'gone'
  }
  return 'generic'
}

const failureKey = ref<string | null>(null)
/** Server sentence rides through for the generic line (never re-phrased). */
const genericMsg = ref('')
const failureLine = computed(() => {
  const key = failureKey.value
  if (!key) return ''
  if (key === 'generic') return genericMsg.value || t('layout.chatDock.draft.fail.generic')
  return t(`layout.chatDock.draft.fail.${key}`)
})

/**
 * Which refusals are final. The wording decides, not the error class: a ticket that expired asks the
 * user to confirm again, so that door stays open; a draft that is already processed, already gone, or
 * superseded by someone else's edit can only ever answer the same sentence again, so leaving its
 * button clickable offers a control that does nothing. The card already had this honest shape for a
 * voided ledger row - the `landedVoided` branch disables the same button and names the reason - and a
 * terminal refusal belongs in the same posture, with the reason line it already shows staying put.
 */
const TERMINAL_FAILURE_KEYS = ['notPending', 'gone', 'conflict', 'caseConflict']
const terminalFailure = computed(() =>
  failureKey.value !== null && TERMINAL_FAILURE_KEYS.includes(failureKey.value))

/** A clamped line must not hide what the reviewer is approving: the title carries the value whole. */
function fullValueTitle(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'string' ? value : JSON.stringify(value)
}

onBeforeUnmount(stopTicker)
</script>

<style scoped>
.draft-card {
  /* 8px sits on the 4px rhythm grid (vision T429s3c: the old 6px read as an
     off-grid gap against the 4px in-card spacing and the message flow). */
  margin-top: 8px;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--tp-primary-weak, rgba(56, 189, 248, 0.08));
}
.draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.draft-title {
  /* 14px over the 13px summary line: the card title must outrank its body
     text (vision T430s3 after2: title/body were the same size, no hierarchy) */
  font-size: 14px;
  font-weight: 600;
}
.draft-case {
  font-size: 12px;
  color: var(--el-color-primary, #409eff);
  text-decoration: none;
}
.draft-summary {
  margin: 4px 0;
  font-size: 13px;
}
.draft-diff {
  margin: 4px 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
}
.draft-diff-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  padding: 2px 0;
}
.draft-field {
  font-family: var(--tp-font-mono, monospace);
  color: var(--tp-text-secondary, #98a2b3);
  /* label column shares one start line with its value column across rows
     (vision D10 re-score, medium item: 2-char vs 4-char labels pushed the
     value start ~14px apart per row; a 4em minimum holds CJK field names
     level and lets longer Latin names grow naturally) */
  flex: none;
  min-width: 4em;
}
.draft-before,
.draft-after {
  /* narrow-dock overflow (vision T429s3c after-round, high): a collapsed long
     string used to be cut at the dock edge; wrap anywhere instead. */
  min-width: 0;
  overflow-wrap: anywhere;
}
.draft-after {
  /* D10 re-score residual: a multi-line value grew into a full-width item and
     wrapped under its own label (row left edge), so the value column lost its
     shared start line. Growing inside the row instead keeps the two-column
     shape; the row still wraps as a fallback for very narrow docks. */
  flex: 1 1 220px;
}
.draft-before {
  text-decoration: line-through;
  color: var(--el-color-danger, #f56c6c);
}
.draft-after {
  color: var(--el-color-success, #67c23a);
}
.draft-none {
  margin: 4px 0;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.draft-ops {
  display: flex;
  flex-wrap: wrap;
  /* baseline (not center): the countdown/hint text and the small button's
     inner label share one text line — center-align offset them ~2-4px
     (measured in the T429s3c vision pass DOM ledger). */
  align-items: baseline;
  gap: 8px;
  /* 8px fold under the content zone (vision T430s3 after4: ops button sat
     4px under the last step row, read as a fourth step line) */
  margin-top: 8px;
}
.draft-ttl {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.draft-hint {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.draft-receipt {
  font-size: 12px;
  color: var(--el-color-success, #67c23a);
}
.draft-steps {
  /* solid fold on --tp-border-on-tint: --tp-border measured 1.08:1 against
     the card halo in dark theme = invisible separator (vision T430s3 after5,
     pixel-verified) */
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--tp-border-on-tint, #6d86a6);
}
.draft-fields {
  /* same fold line as the step zone: field rows and step rows read as two
     grouped sections, not one continuous list (vision T430s3 dim1) */
  border-top: 1px solid var(--tp-border-on-tint, #6d86a6);
  padding-top: 8px;
}
.draft-assert-tag {
  font-size: 11px;
  color: var(--el-color-warning, #e6a23c);
}
.draft-error {
  width: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
}
</style>
