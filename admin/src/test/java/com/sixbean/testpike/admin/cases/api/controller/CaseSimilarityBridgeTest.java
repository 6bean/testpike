package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityCheckRequest;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHintLevel;
import com.sixbean.testpike.admin.cases.api.dto.SimilarityHitView;
import com.sixbean.testpike.admin.cases.app.component.SimilarityHintGrader;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeSearchHit;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeAiClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class CaseSimilarityBridgeTest {

    private final KnowledgeAiClient knowledgeAiClient = mock(KnowledgeAiClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final SimilarityHintGrader grader = new SimilarityHintGrader(
            mock(com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient.class));

    private final CaseSimilarityController controller = new CaseSimilarityController(
            knowledgeAiClient, guard, grader);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    
    @Test
    void blankQueryRejected() {
        login();
        assertThrows(ServiceException.class,
                () -> controller.check(new SimilarityCheckRequest(11L, "  ", null)));
        assertThrows(ServiceException.class,
                () -> controller.check(new SimilarityCheckRequest(null, "文本", null)));
    }

    
    @Test
    void hitsGradedAndMapped() {
        login();
        when(knowledgeAiClient.search(eq(11L), anyString(), eq("SIMILARITY"), isNull()))
                .thenReturn(List.of(
                        hit(501L, 9001L, "登录成功用例", "正文A", 0.93),
                        hit(502L, 9002L, "登录失败用例", "正文B", 0.82),
                        hit(503L, 0L, "规范片段", "正文C", 0.79)));

        Response<List<SimilarityHitView>> resp =
                controller.check(new SimilarityCheckRequest(11L, "登录相关草稿", null));

        List<SimilarityHitView> hits = resp.getData();
        assertEquals(2, hits.size(), "0.79 行=NONE 不进提示（IT-KB-03 四点①端点侧证）");
        assertEquals(9001L, hits.get(0).caseId());
        assertEquals(SimilarityHintLevel.STRONG, hits.get(0).level());
        assertEquals(SimilarityHintLevel.HINT, hits.get(1).level());
    }

    
    @Test
    void excludesSelfOnEdit() {
        login();
        when(knowledgeAiClient.search(anyLong(), anyString(), anyString(), any()))
                .thenReturn(List.of(
                        hit(501L, 9001L, "自己", "正文", 0.95),
                        hit(502L, 9002L, "他人", "正文", 0.85)));

        Response<List<SimilarityHitView>> resp =
                controller.check(new SimilarityCheckRequest(11L, "草稿", 9001L));

        assertEquals(1, resp.getData().size());
        assertEquals(9002L, resp.getData().get(0).caseId());
    }

    
    @Test
    void searchFailureDegradesToEmpty() {
        login();
        when(knowledgeAiClient.search(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        Response<List<SimilarityHitView>> resp =
                controller.check(new SimilarityCheckRequest(11L, "草稿", null));

        assertTrue(resp.getData().isEmpty(), "fail-soft=空列表不抛");
    }

    
    @Test
    void carriesSimilarityPermissionAndViewGuard() throws Exception {
        login();
        when(knowledgeAiClient.search(anyLong(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        controller.check(new SimilarityCheckRequest(11L, "草稿", null));

        org.mockito.Mockito.verify(guard).assertCanView(any(CurrentUser.class), eq(11L));
        org.mockito.Mockito.verify(knowledgeAiClient).search(eq(11L), anyString(), eq("SIMILARITY"), isNull());
        Method check = CaseSimilarityController.class.getMethod("check", SimilarityCheckRequest.class);
        assertEquals("perm:case:similarity-hint",
                check.getAnnotation(com.sixbean.testpike.admin.rbac.app.component.RequirePermission.class).value());
    }

    private static KnowledgeSearchHit hit(Long chunkDocId, Long caseId, String title,
                                          String content, double similarity) {
        return new KnowledgeSearchHit("c" + chunkDocId, chunkDocId, caseId, "TEST_CASE",
                title, content, similarity, similarity);
    }
}
