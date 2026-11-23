package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolCall;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.llm.pipeline.EvidenceAnchorGate;
import com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate;
import com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActFinishReconciler;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal-plan finish door of the live dialog : the
 * production implementation of {@link ReActFinishReconciler} wired into the
 * dialog's {@code FrameLedgerStreamSink}. When the model tries to end a run
 * with a plain-text answer, this door reconciles that finish against the
 * plan the turn itself proposed:
 * <ul>
 * <li>the latest plan.propose call of the transcript (read through the
 * built-in tool's own seams — {@link PlanProposeTool#isPlanCall} and
 * {@link PlanProposeTool#readSteps}, never a re-parsed copy) becomes
 * the session ledger, every step open (pending, evidence blank: a done
 * claim must come from a later segment that carries a real anchor);</li>
 * <li>the ledger is persisted through {@link GoalPlanLedgerStore} — a
 * replayed identical set writes nothing (idempotency discipline);</li>
 * <li>the reminder handed back to the loop is the same resume block a
 * "continue" turn assembles into its system head
 * ({@link GoalPlanLedger#renderSystemSection}), so the model and the
 * resume turn read one single-source view of the open tail.</li>
 * </ul>
 * Posture notes: a turn that never proposed a plan is silent (the door must
 * not chase the user across turns with an old goal — lifecycle judgement of
 * when a ledger is still "the current goal" belongs to ); the per-key
 * budget {@code ai.chat.goal-reconcile-nudges} is read fresh on every
 * consult (K8 hot-update semantics: a setting change lands on the next turn
 * without a restart), 0 honestly closes the nudge and a garbage value falls
 * back to the factory default of 1. A budget spent inside this run answers
 * <p> wires the verification lane of the behaviour kernel
 * ({@link EvidenceAnchorGate}) into the same door: after the goal lane stays
 * quiet, a plain-text finish that carries an assertive conclusion or a
 * figure this run's tool returns cannot back gets the same shared budget —
 * one reminder naming the offending figures and both honest exits. When the
 * budget is spent and such a conclusion still passes, the open steps of
 * rule keeps the note through later replays). Pure text plus the ledger
 * write the goal lane already owns — no new outbound call of any kind.
 */
public final class GoalPlanReconciler implements ReActFinishReconciler {

    /** Hot-update key: how many reminders one run may inject (factory 1; 0 = off). */
    public static final String KEY_NUDGE_BUDGET = "ai.chat.goal-reconcile-nudges";

    /** Reader fallback: the factory default of the seeded key (V369). */
    public static final int DEFAULT_NUDGE_BUDGET = 1;

    /** Steps proposed this turn carry no completion anchor yet — shared JSON writer. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long sessionId;
    private final int planVersion;
    private final GoalPlanLedgerStore store;
    private final CoreGateway gateway;
    /** Reminders already injected this run (per-run scope: the door lives and dies with the turn). */
    private int nudgesUsed;
    /** Ledger this run wrote through the goal lane (the pass marking lands on it, if any). */
    private String runCanonical;
    
    private boolean unverifiedNoted;

    public GoalPlanReconciler(long sessionId, int planVersion, GoalPlanLedgerStore store,
                              CoreGateway gateway) {
        this.sessionId = sessionId;
        this.planVersion = planVersion;
        this.store = store;
        this.gateway = gateway;
    }

    @Override
    public String takeFinishNudge(List<ChatMessage> transcript) {
        int budget = nudgeBudget();
        if (nudgesUsed < budget) {
            String goalNudge = takeGoalPlanNudge(transcript);
            if (goalNudge != null) {
                return goalNudge;
            }
            String evidenceNudge = EvidenceAnchorGate.finishNudgeOrNull(
                    lastPlainTextAnswer(transcript), toolEvidenceTexts(transcript));
            if (evidenceNudge != null) {
                nudgesUsed++;
                return evidenceNudge;
            }
            String echoNudge = goalEchoNudgeOrNull(transcript);
            if (echoNudge != null) {
                nudgesUsed++;
                return echoNudge;
            }
            return null;
        }
        // Budget spent, the finish passes — but an unanchored conclusion must
        // not leave the ledger pretending everything was verified (
        // : the pass lands NOTE_UNVERIFIED on this run's open steps,
        // once per run, and the store's anti-deletion rule keeps it there.
        if (nudgesUsed > 0 && !unverifiedNoted && runCanonical != null
                && EvidenceAnchorGate.finishNudgeOrNull(lastPlainTextAnswer(transcript),
                        toolEvidenceTexts(transcript)) != null) {
            unverifiedNoted = true;
            store.upsertIfChanged(sessionId, planVersion,
                    GoalPlanLedger.withUnverifiedNote(runCanonical));
        }
        return null;
    }

    /**
 * Echo lane of the door ( invariant 3, rides after the evidence lane
 * and shares the same per-run nudge budget): while the factory-on gate is
 * exactly one nudge carrying the format and the clarify exemption. A
 * failing config read is treated as an absent row — the gate stays open,
 * same degradation shape as the directive reads elsewhere.
 */
    private String goalEchoNudgeOrNull(List<ChatMessage> transcript) {
        String raw;
        try {
            raw = gateway.getSysConfig(GoalEchoGate.KEY_ENABLED);
        } catch (RuntimeException doorDown) {
            raw = null;
        }
        if (!GoalEchoGate.enabled(raw)) {
            return null;
        }
        return GoalEchoGate.finishNudgeOrNull(lastPlainTextAnswer(transcript));
    }

    /**
     * Goal-plan lane of the door: the plan proposed by this very turn still
     * has open steps → persist the ledger and hand back the resume block
     * (consumes one unit of the shared nudge budget).
     */
    private String takeGoalPlanNudge(List<ChatMessage> transcript) {
        String canonical = persistProposedLedger(transcript);
        if (canonical == null) {
            return null;
        }
        nudgesUsed++;
        return GoalPlanLedger.renderSystemSection(canonical);
    }

    /**
 * Suspension lane : an accepted clarify card ends the
 * run without ever reaching the finish door, so this is the ONLY path
 * that can carry a plan proposed by the suspended turn into the ledger.
 * Journals — never reminds: there is no next model round to feed and the
 * nudge budget stays intact for the turn that follows the user's answer.
 * The budget key still gates the lane: value 0 means the door is
 * explicitly closed, matching the finish lane's "no injection or ledger
 * write at all" contract.
 */
    @Override
    public void onRunSuspend(List<ChatMessage> transcript) {
        if (nudgeBudget() > 0) {
            persistProposedLedger(transcript);
        }
    }

    /**
     * Single-source persistence seam (R40): latest plan.propose of the
     * transcript -> canonical open ledger -> idempotent store write. Shared
     * by the finish nudge and the suspension lane so the two lanes can
     * never drift apart; null = nothing to journal.
     */
    private String persistProposedLedger(List<ChatMessage> transcript) {
        String argumentsJson = latestProposalArguments(transcript);
        if (argumentsJson == null) {
            return null;
        }
        List<String> steps = PlanProposeTool.readSteps(argumentsJson);
        if (steps.isEmpty()) {
            return null;
        }
        String canonical = GoalPlanLedger.normalize(openStepsJson(steps));
        if (canonical == null || GoalPlanLedger.pendingCount(canonical) == 0) {
            return null;
        }
        store.upsertIfChanged(sessionId, planVersion, canonical);
        runCanonical = canonical;
        return canonical;
    }

    /** Final plain-text answer of the transcript — the conclusion the door judges. */
    private static String lastPlainTextAnswer(List<ChatMessage> transcript) {
        String answer = "";
        for (ChatMessage message : transcript) {
            if ("assistant".equals(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty())) {
                answer = message.textContent();
            }
        }
        return answer;
    }

    /** Tool returns of this run — the evidence pool figures must match against. */
    private static List<String> toolEvidenceTexts(List<ChatMessage> transcript) {
        List<String> texts = new ArrayList<>();
        for (ChatMessage message : transcript) {
            if ("tool".equals(message.role())) {
                String text = message.textContent();
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    /**
     * Fresh per-consult read of the nudge budget (hot-update contract): a
     * core door that cannot answer degrades to the factory default instead
     * of failing the finish; a negative value is treated as garbage.
     */
    private int nudgeBudget() {
        String raw;
        try {
            raw = gateway.getSysConfig(KEY_NUDGE_BUDGET);
        } catch (RuntimeException doorDown) {
            return DEFAULT_NUDGE_BUDGET;
        }
        int value = SysConfigInts.parseInt(raw, DEFAULT_NUDGE_BUDGET);
        return value < 0 ? DEFAULT_NUDGE_BUDGET : value;
    }

    /**
     * Argument body of the last plan.propose call in the transcript — the
     * proposal the model is answering behind (an earlier superseded proposal
     * in the same run must not decide the ledger). Routing check and step
     * parsing stay inside the built-in tool's own seams (single source).
     */
    private static String latestProposalArguments(List<ChatMessage> transcript) {
        String found = null;
        for (ChatMessage message : transcript) {
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (AssistantToolCall call : message.toolCalls()) {
                if (call.function() != null && PlanProposeTool.isPlanCall(call.function().name())) {
                    found = call.function().arguments();
                }
            }
        }
        return found;
    }

    /** Canonical open-ledger shape of a fresh proposal (all steps pending, no anchor yet). */
    private static String openStepsJson(List<String> titles) {
        ArrayNode out = MAPPER.createArrayNode();
        for (String title : titles) {
            ObjectNode step = out.addObject();
            step.put("title", title);
            step.put("state", GoalPlanLedger.STATE_PENDING);
            step.put("evidence", "");
        }
        return out.toString();
    }
}
