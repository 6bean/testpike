package com.sixbean.testpike.admin.aichat.app.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-shape contract of the SSE sink : the ledger's
 * machine-readable {@code meta_json} must reach the browser through the
 * same-block {@code :meta } comment channel — an SSE comment is ignored by any
 * spec-compliant client, so riding meta on the comment line is wire-compatible
 * with every older consumer while the dock decoder can attach it to its frame.
 * <p>A meta value containing raw newlines would split the block on the wire:
 * the writer must drop the side-channel (never the frame) in that case.
 */
class SseFrameWriterMetaChannelTest {

    @Test
    void writeCarriesMetaAsCommentLineInsideTheSameBlock() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("cite", 5L, "[C1] 接口测试指南", "{\"index\":1,\"kind\":\"help\"}");

        assertEqualsOneBlock(emitter);
        String block = emitter.singleBlock();
        assertTrue(block.contains(":meta {\"index\":1,\"kind\":\"help\"}"), block);
        // The meta comment lands in the SAME block as the frame data (before it
        // seals), attached to event/id of the frame it describes.
        assertTrue(block.indexOf(":meta") < block.indexOf("[C1]"),
                "meta comment must precede the data line of its own frame: " + block);
        assertTrue(block.contains("event:cite") && block.contains("id:5"), block);
    }

    @Test
    void writeWithoutMetaStaysMetaFreeAndHeartbeatStaysPlainComment() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("delta", 6L, "答案片段", null);
        writer.write("state", 7L, "已收到", "  ");
        writer.writeHeartbeat();

        assertEquals(3, emitter.blocks().size());
        assertFalse(emitter.blocks().get(0).contains(":meta"), emitter.blocks().get(0));
        assertFalse(emitter.blocks().get(1).contains(":meta"), emitter.blocks().get(1));
        assertTrue(emitter.blocks().get(2).startsWith(":hb"), emitter.blocks().get(2));
    }

    @Test
    void multilineMetaIsDroppedRatherThanBreakingTheFrame() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("cite", 8L, "[C1] 标题", "{\"excerpt\":\"line1\nline2\"}");

        assertEqualsOneBlock(emitter);
        String block = emitter.singleBlock();
        assertFalse(block.contains(":meta"), "raw-newline meta must not hit the wire: " + block);
        assertTrue(block.contains("[C1] 标题"), "the frame itself always survives: " + block);
    }

    private static void assertEqualsOneBlock(CapturingSseEmitter emitter) {
        org.junit.jupiter.api.Assertions.assertEquals(1, emitter.blocks().size(), "one send per written frame");
    }
}
