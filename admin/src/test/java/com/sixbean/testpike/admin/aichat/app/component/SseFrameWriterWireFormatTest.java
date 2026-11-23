package com.sixbean.testpike.admin.aichat.app.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format contract between the dialog pump and the browser's SSE reader
 * (AO batch, eighth-round frame-ledger attribution): every spec-compliant
 * reader drops exactly one leading U+0020 after {@code data:}, while Spring's
 * event builder writes the payload right after the colon with no separator of
 * its own. LLM reason/delta frames are split mid-sentence, so a payload whose
 * first byte is its own inter-word space loses that space on the wire — the
 * browser then stitches "In" + "eed to" into "Ineed to". The writer must pad
 * one extra space in front of space-leading payloads so the reader's mandated
 * drop leaves the payload intact; payloads not starting with a space must
 * stay byte-for-byte unchanged (no double-stuffing).
 */
class SseFrameWriterWireFormatTest {

    @Test
    void spaceLeadingPayloadGainsOnePaddingSpaceForTheReaderMandatedDrop() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 1L, " need to look into", null);

        String block = emitter.singleBlock();
        assertTrue(block.contains("data:  need to look into"),
                "a payload starting with its own space must be padded so the "
                        + "browser keeps it after the spec-mandated single-space drop: " + block);
    }

    @Test
    void payloadWithoutLeadingSpaceIsWrittenByteForByte() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 2L, "token", null);

        String block = emitter.singleBlock();
        assertTrue(block.contains("data:token"),
                "only space-leading payloads get padded, never normal ones: " + block);
        assertFalse(block.contains("data: token"),
                "padding a non-space payload would inject a phantom space: " + block);
    }

    @Test
    void everySpaceOfAnAlreadyIndentedPayloadSurvivesTheDrop() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 3L, "   indented continuation", null);

        String block = emitter.singleBlock();
        assertTrue(block.contains("data:    indented continuation"),
                "padding is exactly one byte; the payload's own indentation must "
                        + "survive after the reader drops one space: " + block);
    }

    @Test
    void newlineLeadingPayloadIsNotPaddedBecauseTheDropOnlyTargetsSpace() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 4L, "\nnext line", null);

        String block = emitter.singleBlock();
        assertFalse(block.contains("data: \n"),
                "the spec drop applies to a single leading space only — padding a "
                        + "newline-leading payload would add a space the reader keeps: " + block);
    }
}
