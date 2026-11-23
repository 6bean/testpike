package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;
import com.sixbean.testpike.core.tool.domain.component.ExternalCallErrorClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observation-signal aggregation for the self-evolution loop (AF-i, phase 2):
 * the read-only rollup that turns the dialog ledger and the tool invocation
 * ledger into the numbers the proposal generators reason about. No new
 * observation table — every signal recomputes from an existing ledger.
 * <p>What this pins on the real dev database with zero mocks:
 * <ul>
 *   <li>follow-up rate = share of sessions (sealed assistant answer inside
 *       the window) where the user came back with another message — the
 *       "the answer did not land" proxy;</li>
 *   <li>thumbs counts land per window: a 👎/👍 sealed in the asked month is
 *       counted, the same row aged outside the window is not;</li>
 *   <li>sealed error turns are counted separately from thumbs-down (a failed
 *       run is not a rating);</li>
 *   <li>tool failures group by tool name inside the window, heaviest first,
 *       with the newest error text riding along; successes never appear;</li>
 *   <li>a malformed month key is refused fail-closed like every other
 *       window-scoped read model;</li>
 *   <li>project scoping: another project's dialog signals never leak into
 *       this project's window.</li>
 * </ul>
 * Fixtures use random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class EvolutionSignalObservationTest {

    @Autowired
    private EvolutionSignalService signalService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag(String stem) {
        return stem + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long newUserId(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, name, name);
    }

    private long newProjectId(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, name, name);
    }

    private String currentMonth() {
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** A sealed assistant answer inside a fresh owned session. */
    private long sealedAssistant(long projectId, long userId, String title) {
        ChatSessionInfo session = sessionService.create(projectId, userId, title, 1, "{}");
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        return messageId;
    }

    @Test
    void followUpRateCountsSessionsWhereTheUserCameBack() {
        long userId = newUserId(tag("sgu"));
        long projectId = newProjectId(tag("sgp"));
        String month = currentMonth();

        // Session A: a sealed answer, then the user comes back → follow-up.
        ChatSessionInfo talked = sessionService.create(projectId, userId, "追问样本", 1, "{}");
        long answer = frameService.openMessage(talked.id(), "assistant", "回答");
        frameService.closeMessage(answer, "done");
        frameService.closeMessage(frameService.openMessage(talked.id(), "user", "那第二个呢"), "done");

        // Session B: a sealed answer, no return visit.
        sealedAssistant(projectId, userId, "未追问样本");

        EvolutionSignalView view = signalService.observe(month, projectId);
        assertEquals(2, view.sessionsWithSealedAnswer(),
                "both sessions sealed an assistant answer inside the window");
        assertEquals(1, view.sessionsWithFollowUp(),
                "only the session the user returned to counts as a follow-up");
        assertEquals(0.5, view.followUpRate(), 1e-9, "1 of 2 sessions followed up");
    }

    @Test
    void thumbsAndErrorTurnsLandPerWindow() {
        long userId = newUserId(tag("sgu"));
        long projectId = newProjectId(tag("sgp"));
        String month = currentMonth();

        long down = sealedAssistant(projectId, userId, "点踩样本");
        frameService.recordFeedback(down, userId, -1, "答非所问");
        long up = sealedAssistant(projectId, userId, "点赞样本");
        frameService.recordFeedback(up, userId, 1, null);
        long failed = frameService.openMessage(
                sessionService.create(projectId, userId, "失败回合", 1, "{}").id(),
                "assistant", "半截回答");
        frameService.closeMessage(failed, "error");

        EvolutionSignalView inside = signalService.observe(month, projectId);
        assertEquals(1, inside.thumbsDownCount(), "the single 👎 lands in this window");
        assertEquals(1, inside.thumbsUpCount(), "the single 👍 lands in this window");
        assertEquals(1, inside.errorTurns(), "the sealed error turn counts once");

        // Move the 👎 row 14 months back and re-ask the current window:
        // a row that no longer lives in the month must drop out of its count.
        jdbcTemplate.update("UPDATE tp_ai_chat_message SET created_at = ? WHERE id = ?",
                YearMonth.now().minusMonths(14).atDay(15).atStartOfDay(), down);
        EvolutionSignalView afterAging = signalService.observe(month, projectId);
        assertEquals(0, afterAging.thumbsDownCount(), "a row outside the window is not counted");
        assertEquals(1, afterAging.thumbsUpCount(), "the 👍 row still lives in the window");
    }

    @Test
    void toolFailuresGroupByToolNameHeaviestFirst() {
        String month = currentMonth();
        String heavy = tag("取数失败甲");
        String light = tag("取数失败乙");
        for (int i = 0; i < 3; i++) {
            insertInvocation(heavy, 0, "上游超时 " + i);
        }
        insertInvocation(heavy, 1, null);
        insertInvocation(light, 0, "参数不合法");

        EvolutionSignalView view = signalService.observe(month, null);
        EvolutionSignalView.ToolFailureRow heavyRow = view.toolFailures().stream()
                .filter(r -> r.toolName().equals(heavy)).findFirst().orElseThrow();
        assertEquals(3, heavyRow.failureCount(), "only failures are counted, the success is invisible");
        assertEquals("上游超时 2", heavyRow.lastError(), "the newest error text rides along");

        int heavyIndex = indexOfTool(view, heavy);
        int lightIndex = indexOfTool(view, light);
        assertTrue(heavyIndex < lightIndex, "heaviest failure group reads first");
    }

    private int indexOfTool(EvolutionSignalView view, String toolName) {
        for (int i = 0; i < view.toolFailures().size(); i++) {
            if (view.toolFailures().get(i).toolName().equals(toolName)) {
                return i;
            }
        }
        throw new AssertionError("tool group missing from the failure ledger: " + toolName);
    }

    private void insertInvocation(String toolName, int success, String error) {
        jdbcTemplate.update("INSERT INTO tp_tool_invocation (caller, tool_name, success, error, created_at) "
                        + "VALUES ('ai', ?, ?, ?, now())",
                toolName, success, error);
    }

    @Test
    void malformedMonthIsRefusedFailClosed() {
        assertThrows(ServiceException.class, () -> signalService.observe("2026/10", null));
        assertThrows(ServiceException.class, () -> signalService.observe("2026-13", null));
    }

    @Test
    void anotherProjectCannotSeeThisProjectsDialogSignals() {
        long userId = newUserId(tag("sgu"));
        long ownProject = newProjectId(tag("sgp"));
        long otherProject = newProjectId(tag("sgq"));
        String month = currentMonth();

        long rated = sealedAssistant(ownProject, userId, "隔离样本");
        frameService.recordFeedback(rated, userId, -1, "不准");

        EvolutionSignalView other = signalService.observe(month, otherProject);
        assertEquals(0, other.sessionsWithSealedAnswer(),
                "the neighbour project's dialog rows never leak into this slice");
        assertEquals(0, other.thumbsDownCount(), "thumbs counts stay project-scoped too");
    }

    /**
 * GA5 : failed external /mcp calls are bucketed by error class on
 * the read side of the existing ledger — refused / timeout /
 * idempotent-conflict / forbidden / unclassified — with no new table and
 * no writer change. Deltas pin three contracts at once: the class split
 * recomputes from the recorded error text, successes and non-mcp callers
 * carrying the same wording never move a bucket (caller scoping), and a
 * text outside the vocabulary honestly lands in UNCLASSIFIED instead of
 * being guessed into a real bucket.
 */
    @Test
    void externalCallFailuresAreCountedByErrorClassWithinTheWindow() {
        String month = currentMonth();
        String toolTag = tag("ga5tool");

        // negatives planted before the delta baseline: success row and an
        // ai-caller row with the very same timeout wording
        insertExternalCall(toolTag, "ai", 1, "Read timed out 超时");
        insertExternalCall(toolTag, "ai", 0, "网关转发超时，forward-read 已用尽");
        EvolutionSignalView before = signalService.observe(month, null);

        insertExternalCall(toolTag, "mcp", 0, "重复请求：相同 Idempotency-Key 的请求正在处理中，请勿重复提交");
        insertExternalCall(toolTag, "mcp", 0, "I/O error on POST request: Read timed out");
        insertExternalCall(toolTag, "mcp", 0, "Tool not found（工具不存在或当前 Token 无权限）");
        insertExternalCall(toolTag, "mcp", 0, "该 Token 当日用量已达上限，明日自动恢复");
        insertExternalCall(toolTag, "mcp", 0, "字段 case_id 必须为正整数 " + toolTag);

        EvolutionSignalView after = signalService.observe(month, null);
        assertEquals(1, externalDelta(before, after, ExternalCallErrorClass.IDEMPOTENT_CONFLICT));
        assertEquals(1, externalDelta(before, after, ExternalCallErrorClass.TIMEOUT));
        assertEquals(1, externalDelta(before, after, ExternalCallErrorClass.FORBIDDEN));
        assertEquals(1, externalDelta(before, after, ExternalCallErrorClass.REFUSED));
        assertEquals(1, externalDelta(before, after, ExternalCallErrorClass.UNCLASSIFIED));
    }

    private void insertExternalCall(String toolName, String caller, int success, String error) {
        jdbcTemplate.update("INSERT INTO tp_tool_invocation (caller, tool_name, success, error, created_at) "
                        + "VALUES (?, ?, ?, ?, now())",
                caller, toolName, success, error);
    }

    private static long externalCount(EvolutionSignalView view, ExternalCallErrorClass errorClass) {
        return view.externalCallErrors().stream()
                .filter(row -> row.errorClass().equals(errorClass))
                .mapToLong(EvolutionSignalView.ExternalCallErrorRow::count)
                .sum();
    }

    private static long externalDelta(EvolutionSignalView before, EvolutionSignalView after,
                                      ExternalCallErrorClass errorClass) {
        return externalCount(after, errorClass) - externalCount(before, errorClass);
    }

}
