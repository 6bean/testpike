package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.ChunkContextView;
import com.sixbean.testpike.admin.ai.api.dto.ChunkNeighborView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractRequest;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WR batch segment C read bridge: GET /api/v1/ai/documents/{id}/chunks/{chunkId}
 * now serves the context door (requested chunk + preceding neighbours + position
 * marker) instead of streaming the whole chunk list and filtering it locally.
 * Asserted here (L2, remote client faked at the integration boundary):
 * 1) the permission source is the document's owning project (fetch doc first, then
 * assertCanView — same order as the detail endpoint, never the client's word);
 * 2) core's honest refusal for a foreign/stale chunk id passes through verbatim —
 * the admin side never re-implements ownership probing (no chunks fan-out);
 * 3) context fields pass through without silent loss (chunkId/chapter/seq/content/
 * contentChars/positionIndex/preceding[]);
 * 4) the directed-supplement whitelist (chunkIds) travels the extract bridge intact.
 * The real admin→core→DB chain is covered by the batch-close L3 suite (layering rule).
 */
class DocumentChunkReadBridgeTest {

    private final StreamDoorTestAssembly wiring = new StreamDoorTestAssembly();

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    private static DocumentInfoView docFixture(long docId, long projectId) {
        return new DocumentInfoView(docId, projectId, "PRD-v2.md", "markdown",
                "hash", 2048L, "k", 4, 12, null, null, true, true, true, 0, null, null, null);
    }

    private static ChunkContextView contextFixture() {
        return new ChunkContextView(2L, 1, "3.1 登录", "原文分块正文 1", 8, 2,
                List.of(new ChunkNeighborView(1L, "3.1 登录", 0, "上一块原文")), true);
    }

    @Test
    void 上下文门直通_邻块与位置标记原样透传_先取文档后验权() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L));
        when(wiring.coreDocumentClient.chunkContext(12L, 2L)).thenReturn(contextFixture());

        Response<ChunkContextView> resp = wiring.documentController.chunk(12L, 2L);

        assertEquals("0", resp.getCode());
        assertEquals(2L, resp.getData().chunkId(), "命中分块的行 ID 必须原样透传");
        assertEquals("3.1 登录", resp.getData().chapter());
        assertEquals(1, resp.getData().seq());
        assertEquals("原文分块正文 1", resp.getData().content());
        assertEquals(8, resp.getData().contentChars(), "薄块字数解释值不得静默丢失");
        assertEquals(2, resp.getData().positionIndex(), "第 N 块位置标记不得静默丢失");
        assertEquals(1, resp.getData().preceding().size(), "邻块上下文必须原样到达");
        assertEquals(1L, resp.getData().preceding().get(0).id());
        assertEquals("上一块原文", resp.getData().preceding().get(0).content());
        verify(wiring.guard).assertCanView(org.mockito.ArgumentMatchers.any(CurrentUser.class), eq(42L));
        verify(wiring.coreDocumentClient, never()).chunks(anyLong());
    }

    @Test
    void 外来或过期分块_核心回found零命中不携带正文_不再本地全量过滤() {
        // fix: a stale chunk is an expected fallback state, answered as a
        // 200 with found=false and no text, never as a 4xx on the wire.
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L));
        when(wiring.coreDocumentClient.chunkContext(12L, 99L)).thenReturn(
                new ChunkContextView(99L, 0, null, null, 0, 0, List.of(), false));

        Response<ChunkContextView> resp = wiring.documentController.chunk(12L, 99L);

        assertEquals("0", resp.getCode());
        assertFalse(resp.getData().found(), "过期块必须以 found=false 回答");
        assertNull(resp.getData().content(), "未命中分块不得携带任何正文");
        verify(wiring.coreDocumentClient, never()).chunks(anyLong());
    }

    @Test
    void 文档不存在_验权前404透传_不触碰上下文门() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(99L)).thenThrow(new ServiceException("文档不存在", 404));

        assertThrows(ServiceException.class, () -> wiring.documentController.chunk(99L, 1L));
        verify(wiring.coreDocumentClient, never()).chunkContext(anyLong(), anyLong());
    }

    @Test
    void 定向补提白名单_经提取桥原样转发_旧构造缺省空() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L));

        wiring.documentController.extract(12L, new ExtractRequest(null, null, false, List.of(148477L)));

        verify(wiring.coreDocumentClient).extract(eq(12L),
                argThat(request -> request.chunkIds().equals(List.of(148477L))));
        assertTrue(new ExtractRequest("hint").chunkIds().isEmpty(), "两遍迁移旧构造缺省空集");
        assertTrue(new ExtractRequest("hint", "dim").chunkIds().isEmpty(), "三参前构造缺省空集");
    }
}
