package com.sixbean.testpike.core.spec.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SpecDraftGroundingTest {

    private static final String CHUNK_A = "用户凭账号密码登录，接口 POST /auth/login 返回访问令牌。";
    private static final String CHUNK_B = "令牌失效后需重新登录，GET /auth/session 用于会话探测。";

    private final SpecDraftGrounding grounding = new SpecDraftGrounding();

    @Test
    void anchorHittingSourceTextIsHigh() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", "凭账号密码登录")),
                List.of(CHUNK_A, CHUNK_B));
        assertEquals(1, out.size());
        assertEquals("high", out.get(0).confidence());
    }

    @Test
    void missingAnchorIsLow() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", null)),
                List.of(CHUNK_A));
        assertEquals("low", out.get(0).confidence());
    }

    @Test
    void blankAnchorIsLow() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", "   ")),
                List.of(CHUNK_A));
        assertEquals("low", out.get(0).confidence());
    }

    @Test
    void fabricatedAnchorIsLow() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", "文档外的发明接口")),
                List.of(CHUNK_A, CHUNK_B));
        assertEquals("low", out.get(0).confidence());
    }

    @Test
    void anchorMatchesAcrossWhitespaceReflow() {
        String anchored = "凭账号密码\n登录， 接口 POST /auth/login";
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", anchored)),
                List.of(CHUNK_A));
        assertEquals("high", out.get(0).confidence());
    }

    @Test
    void sameEndpointAcrossChunksKeepsHighConfidence() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(
                        raw("POST /auth/login", "POST", "/auth/login", "与正文无关的幻觉锚"),
                        raw("POST /auth/login", "POST", "/auth/login", "会话探测")),
                List.of(CHUNK_A, CHUNK_B));
        assertEquals(1, out.size());
        assertEquals("high", out.get(0).confidence());
    }

    @Test
    void mergeFallsBackToNormalizedMethodPathWhenKeyMissing() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(
                        raw(null, "get", "/auth/session", "会话探测"),
                        raw("", "GET", "/auth/session ", "无锚点")),
                List.of(CHUNK_A, CHUNK_B));
        assertEquals(1, out.size());
        assertEquals("high", out.get(0).confidence());
    }

    @Test
    void channelStampedToB() {
        List<ApiSpecDraftItem> out = grounding.apply(
                List.of(raw("POST /auth/login", "POST", "/auth/login", "凭账号密码登录")),
                List.of(CHUNK_A));
        assertEquals("B", out.get(0).channel());
        assertTrue(out.get(0).endpointKey().startsWith("POST"));
    }

    @Test
    void emptyInputYieldsEmptyResult() {
        assertTrue(grounding.apply(List.of(), List.of(CHUNK_A)).isEmpty());
    }

    private static ApiSpecDraftItem raw(String endpointKey, String method, String path,
                                        String anchor) {
        return new ApiSpecDraftItem(endpointKey, method, path, "标题", "描述", List.of("认证"),
                "[]", "application/json", "{}", "[]", "[]", false, List.of(), null,
                "B", "low", anchor);
    }
}
