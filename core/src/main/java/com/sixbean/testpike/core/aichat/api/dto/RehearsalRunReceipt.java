package com.sixbean.testpike.core.aichat.api.dto;

import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;

import java.util.List;

/**
 * Receipt payload of the rehearsal-run launch door (AO batch ,
 * user-facing shape of the D1 receipt card): the fields are a same-ticket
 * direct transfer of the core {@link BatchStartResult} — the door never
 * recomputes or renames start facts — plus the three gate declaration lines
 * and the progress path the conversation points the user to.
 * <p>The payload travels to the dialog unchanged through the tool_done
 * trace (frame-type red line: no new frame types), so every field here is
 * operational information: where the batch is, when it starts, what was
 * skipped, and which settings let this launch through.
 *
 * @param batchId started batch id
 * @param statusKey machine status key from the start chain (single source)
 * @param statusLabel human status label
 * @param queuePos queue position, null when the queue is empty
 * @param estimatedStartAt estimated start time text
 * @param skipCount cases skipped at start (empty or null list = 0)
 * @param message start-chain human message carried as-is
 * @param gateDeclarations three-facet release declarations (gate single source)
 * @param progressPath batch detail page path for progress polling
 */
public record RehearsalRunReceipt(long batchId, String statusKey, String statusLabel,
                                  Integer queuePos, String estimatedStartAt, int skipCount,
                                  String message, List<String> gateDeclarations,
                                  String progressPath) {

    /** Mirror one core start result into the receipt with its declarations. */
    public static RehearsalRunReceipt of(BatchStartResult started, List<String> gateDeclarations) {
        return new RehearsalRunReceipt(
                started.batchId(),
                started.statusKey(),
                started.statusLabel(),
                started.queuePos(),
                started.estimatedStartAt(),
                started.skipCases() == null ? 0 : started.skipCases().size(),
                started.message(),
                List.copyOf(gateDeclarations),
                "/batches/" + started.batchId());
    }
}
