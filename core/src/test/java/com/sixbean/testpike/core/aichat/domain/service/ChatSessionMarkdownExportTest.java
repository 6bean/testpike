package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session export as Markdown (E5 sedimentation, AO batch phase 2): the
 * reader gets the conversation a human watched — question and answer turns in
 * order, streamed answers rebuilt from the ledger — with credentials and PII
 * masked before anything leaves the door (the U-10 two-facet posture extended
 * to free text: Bearer/token-shaped secrets and phone/email never ride out in
 * cleartext). Internal ledger ids stay inside the ledger: the export numbers
 * turns, it does not dump message or session keys.
 * <p>Real dev database, zero mocks (same posture as {@link
 * ChatSessionUsageAccumulationTest}).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatSessionMarkdownExportTest {

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionExportService exportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newUserId(String tag) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
    }

    private long newProjectId(String tag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    private ChatSessionInfo freshSession(String title) {
        String tag = "t435e" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return sessionService.create(newProjectId(tag), newUserId(tag), title, 1, "{}");
    }

    private void sealTurn(long sessionId, String role, String content) {
        long messageId = frameService.openMessage(sessionId, role, content);
        frameService.closeMessage(messageId, "done");
    }

    @Test
    void exportRendersQaTurnsAndMasksSensitiveText() {
        ChatSessionInfo session = freshSession("导出试验");
        long sessionId = session.id();

        sealTurn(sessionId, "user", "查一下 13812345678 和 Bearer sk-abcdef123456 的账号");
        long answer = frameService.openMessage(sessionId, "assistant", null);
        frameService.appendFrame(sessionId, answer, ChatFrameType.DELTA, "答案是 138****5678，", "{}");
        frameService.appendFrame(sessionId, answer, ChatFrameType.DELTA, "令牌已登记。", "{}");
        frameService.closeMessage(answer, "done");
        // A still-streaming turn belongs to the live pump, never to the export.
        frameService.openMessage(sessionId, "assistant", "未封存的回合");

        ChatExportView view = exportService.renderMarkdown(session.userId(), sessionId);

        assertTrue(view.markdown().startsWith("# 导出试验"),
                "export opens with the session title, got: " + view.markdown());
        assertTrue(view.markdown().contains("问"), "question turns are labelled");
        assertTrue(view.markdown().contains("答案是 138****5678，令牌已登记。"),
                "streamed deltas rebuild the answer");
        assertFalse(view.markdown().contains("13812345678"), "raw phone never exports");
        assertFalse(view.markdown().contains("sk-abcdef123456"), "raw bearer token never exports");
        assertTrue(view.markdown().contains("Bearer ***"), "secret shape keeps its prefix, value masked");
        assertFalse(view.markdown().contains("未封存的回合"), "streaming turn stays out of the export");
        assertEquals(1, view.turnCount(), "one sealed question/answer pair on the page");
        assertTrue(view.filename().endsWith(".md"), "download name is a markdown file");
        // The stamped line is on-screen copy (the share snapshot reuses this
        // render): an undelimited "20261030-1404" is unreadable and the word
        // word across the two faces.
        assertTrue(view.markdown().contains("生成于 20"),
                "generation line reads 生成于 YYYY-MM-DD ..., got: " + view.markdown());
        assertFalse(view.markdown().contains("导出时间："),
                "no unreadable undelimited stamp on the rendered copy");
    }

    @Test
    void exportSkipsSystemPlumbingTurns() {
        ChatSessionInfo session = freshSession("系统行不外泄");
        sealTurn(session.id(), "system", "内部路由备注不该出现在导出里");
        sealTurn(session.id(), "user", "你好");
        ChatExportView view = exportService.renderMarkdown(session.userId(), session.id());
        assertFalse(view.markdown().contains("内部路由备注"), "system rows are plumbing, not dialog");
        assertTrue(view.markdown().contains("你好"));
    }

    @Test
    void exportRefusesForeignReader() {
        ChatSessionInfo session = freshSession("别人的会话");
        sealTurn(session.id(), "user", "私密问题");
        long strangerId = newUserId("t435e-stranger-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8));
        assertThrows(ServiceException.class,
                () -> exportService.renderMarkdown(strangerId, session.id()),
                "a foreign reader cannot export another user's conversation");
    }
}
