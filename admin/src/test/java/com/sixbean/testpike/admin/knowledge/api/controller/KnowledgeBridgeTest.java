package com.sixbean.testpike.admin.knowledge.api.controller;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeRebuildResultView;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeAiClient;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeChunkView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocDetailView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeHitView;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class KnowledgeBridgeTest {

    private final KnowledgeCoreClient coreClient = mock(KnowledgeCoreClient.class);
    private final KnowledgeAiClient aiClient = mock(KnowledgeAiClient.class);
    private final KnowledgeController controller = new KnowledgeController(coreClient, aiClient);

    
    @Test
    void listPassesFiltersThroughToCore() {
        when(coreClient.listDocs(eq(11L), eq("TEST_CASE"), isNull(), eq(2), eq(10),
                eq("title"), eq("asc")))
                .thenReturn(new PageView<>(List.of(doc(1L, "c1")), 11L));

        Response<PageView<KnowledgeDocView>> resp =
                controller.list(11L, "TEST_CASE", null, 2, 10, "title", "asc");

        assertEquals("0", resp.getCode());
        assertEquals(11L, resp.getData().total());
        assertEquals("c1", resp.getData().list().get(0).title());
        verify(coreClient).listDocs(eq(11L), eq("TEST_CASE"), isNull(), eq(2), eq(10),
                eq("title"), eq("asc"));
    }

    /** Null sort pair passes through untouched: the bridge invents nothing and
     *  core keeps its historical default order. */
    @Test
    void listForwardsNullSortPairWithoutInvention() {
        when(coreClient.listDocs(isNull(), isNull(), isNull(), eq(1), eq(20), isNull(), isNull()))
                .thenReturn(new PageView<>(List.of(), 0L));

        controller.list(null, null, null, 1, 20, null, null);

        verify(coreClient).listDocs(isNull(), isNull(), isNull(), eq(1), eq(20), isNull(), isNull());
    }

    
    @Test
    void rebuildDelegatesToAiService() {
        when(aiClient.rebuild()).thenReturn(
                new KnowledgeRebuildResultView(List.of(7L), List.of(9L), 12, 13));

        Response<KnowledgeRebuildResultView> resp = controller.rebuild();

        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData());
        assertEquals(List.of(7L), resp.getData().repairedDocIds());
        assertEquals(List.of(9L), resp.getData().orphanDeletedDocIds());
        assertEquals(12, resp.getData().ledgerActiveRows());
        assertEquals(13, resp.getData().vectorRowsAfter());
        verify(aiClient).rebuild();
    }

    
    @Test
    void bothLegsCarryKnowledgeManagePermission() throws Exception {
        Method list = KnowledgeController.class.getMethod("list",
                Long.class, String.class, String.class, int.class, int.class,
                String.class, String.class);
        Method rebuild = KnowledgeController.class.getMethod("rebuild");
        assertEquals("perm:knowledge:mcp-manage",
                list.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:knowledge:mcp-manage",
                rebuild.getAnnotation(RequirePermission.class).value());
    }

    
    @Test
    void detailLegPassesCoreViewThrough() {
        when(coreClient.docDetail(7L)).thenReturn(new KnowledgeDocDetailView(
                7L, 11L, 0L, "TEST_CASE", 501L, "标题", "源全文", "ACTIVE",
                "2026-09-20 10:00:00", "2026-09-20 10:00:00", 3,
                List.of(new KnowledgeChunkView("c-uuid-1", 0, "分块正文",
                        Map.of("doc_id", "7", "lang", "java")))));

        Response<KnowledgeDocDetailView> resp = controller.detail(7L);

        assertEquals("0", resp.getCode());
        assertEquals("源全文", resp.getData().content());
        assertEquals(3, resp.getData().hitCount());
        assertEquals("c-uuid-1", resp.getData().chunks().get(0).chunkId());
        assertEquals("java", resp.getData().chunks().get(0).metadata().get("lang"));
        verify(coreClient).docDetail(7L);
    }

    
    @Test
    void hitsLegPassesLimitThroughToCore() {
        when(coreClient.listDocHits(7L, 20)).thenReturn(List.of(
                new KnowledgeHitView(1L, 99L, "生成任务人话标题", "STYLE_FEW_SHOT",
                        List.of("c-uuid-1"), "2026-09-20 10:01:00")));

        Response<List<KnowledgeHitView>> resp = controller.hits(7L, 20);

        assertEquals("0", resp.getCode());
        assertEquals("生成任务人话标题", resp.getData().get(0).taskTitle());
        assertEquals(List.of("c-uuid-1"), resp.getData().get(0).chunkIds());
        verify(coreClient).listDocHits(7L, 20);
    }

    
    @Test
    void detailAndHitsLegsCarryKnowledgeManagePermission() throws Exception {
        Method detail = KnowledgeController.class.getMethod("detail", long.class);
        Method hits = KnowledgeController.class.getMethod("hits", long.class, int.class);
        assertEquals("perm:knowledge:mcp-manage",
                detail.getAnnotation(RequirePermission.class).value());
        assertEquals("perm:knowledge:mcp-manage",
                hits.getAnnotation(RequirePermission.class).value());
    }

    private static KnowledgeDocView doc(long id, String title) {
        return new KnowledgeDocView(id, 11L, 0L, "TEST_CASE", 501L, title,
                "正文", "ACTIVE", "2026-09-16 12:00:00", "2026-09-16 12:00:00");
    }
}
