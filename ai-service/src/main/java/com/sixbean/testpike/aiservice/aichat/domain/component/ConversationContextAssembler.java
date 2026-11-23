package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * AP-1 ② rolling-summary assembly order : the dialog request is
 * rebuilt every turn as frozen system head + pinned-fact section + summary of
 * the older turns + raw text of the newest N turns + the current prompt.
 *
 * <p>Placement rulings, recorded here so the shape is not re-debated per page:
 * <ul>
 * <li>everything context-shaped lands in the single leading system message —
 * one system message keeps the provider request shape byte-compatible with
 * the existing single-turn chain (no mid-history system surprise);</li>
 * <li>the pinned section is rendered BEFORE the summary section and is never
 * an input to it: confirmed facts survive the lossy layer by construction;</li>
 * <li>a turn is counted from its user message (user + following assistant);
 * assistant rows without content (streaming placeholder / empty abort)
 * never reach the provider;</li>
 * <li>blank summary text renders no section — an honest "no older context"
 * beats a fabricated divider.</li>
 * </ul>
 *
 * <p>Pure function on purpose (L2-testable without gateway or LLM): the
 * orchestrator feeds it history from ChatMessageRemote/core listMessages and
 * the summariser from the document-chain reduce contract.
 */
public final class ConversationContextAssembler {

    /** Section headers are operator-visible prompt text — single-sourced here. */
    static final String PINNED_HEADER = "\n\n—— 已确认事实 ——\n";
    static final String SUMMARY_HEADER_PREFIX = "\n\n—— 更早对话摘要（前 ";
    static final String SUMMARY_HEADER_SUFFIX = " 轮）——\n";

    private ConversationContextAssembler() {
    }

    /** Build the ordered message list for one dialog turn. */
    public static List<ChatMessage> assemble(ConversationAssemblyRequest request,
                                             ConversationSummarySource summarySource) {
        int keepTurns = Math.max(1, request.keepRecentTurns());
        List<ChatMessageRemote> history = request.history();
        int rawFrom = rawWindowStart(history, keepTurns);
        List<ChatMessageRemote> older = history.subList(0, rawFrom);
        List<ChatMessageRemote> recent = history.subList(rawFrom, history.size());

        StringBuilder system = new StringBuilder(request.systemHead() == null
                ? "" : request.systemHead());
        appendPinnedFacts(system, request.pinnedFacts());
        int olderTurns = countTurns(older);
        if (olderTurns > 0 && summarySource != null) {
            String summary = summarySource.summarize(older);
            if (summary != null && !summary.isBlank()) {
                system.append(SUMMARY_HEADER_PREFIX).append(olderTurns)
                        .append(SUMMARY_HEADER_SUFFIX).append(summary.strip());
            }
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", system.toString()));
        for (ChatMessageRemote turn : recent) {
            String content = turn.content() == null ? "" : turn.content();
            if ("assistant".equals(turn.role()) && content.isBlank()) {
                continue;
            }
            if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
                continue;
            }
            messages.add(new ChatMessage(turn.role(), content));
        }
        messages.add(new ChatMessage("user",
                request.currentPrompt() == null ? "" : request.currentPrompt()));
        return List.copyOf(messages);
    }

    /**
     * Index of the first history row kept raw = the position of the keepTurns-th
     * user message counted from the tail (or 0 when the history is shorter).
     */
    private static int rawWindowStart(List<ChatMessageRemote> history, int keepTurns) {
        int seen = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                seen++;
                if (seen == keepTurns) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static int countTurns(List<ChatMessageRemote> messages) {
        int turns = 0;
        for (ChatMessageRemote m : messages) {
            if ("user".equals(m.role())) {
                turns++;
            }
        }
        return turns;
    }

    private static void appendPinnedFacts(StringBuilder system, List<String> pinnedFacts) {
        List<String> visible = pinnedFacts.stream()
                .filter(f -> f != null && !f.isBlank())
                .toList();
        if (visible.isEmpty()) {
            return;
        }
        system.append(PINNED_HEADER);
        for (String fact : visible) {
            system.append("- ").append(fact.strip()).append('\n');
        }
    }
}
