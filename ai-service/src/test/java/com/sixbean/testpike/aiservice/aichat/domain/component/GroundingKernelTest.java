package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grounding assembly contract : the
 * retrieval-first assembler ranks flattened candidates lexically against the
 * question, dedupes by source row, truncates to the citation budget, cuts
 * length-bounded excerpts around the matched term, builds the {@code [C<n>]}
 * marked prompt-side context block — and, the honesty half of the contract,
 * returns the zero-hit shape when nothing clears the relevance floor so the
 * answer says "not grounded" instead of improvising platform facts.
 * <p>L2 pure-function posture: help/knowledge adapters are pinned elsewhere
 * (their own service tests + the L3 chain in the closing knife); nothing
 * mocked here is re-proved at this layer.
 */
class GroundingKernelTest {

    private static final int MAX_CITATIONS = 3;
    private static final int EXCERPT_CHARS = 80;
    private static final double MIN_SCORE = 0.05;

    private static GroundingHit help(long id, String domain, String title, String body) {
        return new GroundingHit(GroundingKernel.KIND_HELP, id, domain, title, body, 0.0);
    }

    private static GroundingHit knowledge(long docId, String title, String content, double score) {
        return new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, docId, null, title, content, score);
    }

    /**
 * S4 identity channel : a candidate carrying responsibility
 * facts (who owns the fix, why it was closed) rides as manager-only — the
 * kernel is the single enforcement point of the trim map, so no source
 * adapter can leak it past the role verdict by mistake.
 */
    private static GroundingHit managerOnlyKnowledge(long docId, String title,
                                                     String content, double score) {
        return new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, docId, null, title,
                content, score, null, true);
    }

    @Test
    void managerOnlyCandidateSurvivesOnlyForSystemManagers() {
        var pool = List.of(managerOnlyKnowledge(88, "缺陷责任与关闭归因",
                "缺陷单 3021 的修复负责人是张三，关闭原因是重复缺陷关闭。", 0.9));

        var asManager = GroundingKernel.assemble("缺陷升级责任归因 负责人 关闭原因", pool,
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE, true);
        assertFalse(asManager.zeroHit(), "a system manager sees the responsibility facts");
        assertEquals(1, asManager.citations().size());

        var asMember = GroundingKernel.assemble("缺陷升级责任归因 负责人 关闭原因", pool,
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE, false);
        assertTrue(asMember.zeroHit(),
                "the member view is the honest zero-hit shape, never a half-trimmed row");
        assertTrue(asMember.citations().isEmpty());
        assertEquals("", asMember.contextBlock(),
                "manager-only text must not reach the prompt block of a member run");
    }

    @Test
    void legacyAssembleKeepsManagerOnlyCandidatesForUnmigratedCallers() {
        // The pre-identity overload has no role verdict at all — its behaviour
        // must stay byte-for-byte what it was before (zero drift).
        var outcome = GroundingKernel.assemble("缺陷升级责任归因 负责人 关闭原因",
                List.of(managerOnlyKnowledge(88, "缺陷责任与关闭归因",
                        "关闭原因是重复缺陷关闭。", 0.9)),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);
        assertEquals(1, outcome.citations().size());
    }

    /**
 * S4 defect corpus : one defect row is published as two
 * shapes of the SAME source row — the plain history view every project
 * member may see, and the attribution view (who fixes it, why it was
 * closed) that rides manager-only. Because dedup keys on kind + refId and
 * keeps the best-ranked shape, each viewer gets exactly one card for that
 * defect carrying the strongest shape they are allowed to see; nobody
 * gets a half-trimmed row and nobody leaks the responsibility facts.
 */
    @Test
    void defectRowCollapsesToTheStrongestShapeItsViewerMaySee() {
        var pool = List.of(
                new GroundingHit(GroundingKernel.KIND_DEFECT, 3021L, null,
                        "登录页在并发下超时",
                        "严重级别：P1；状态：已关闭。登录接口在 200 并发下平均响应超过 8 秒。", 0.0),
                new GroundingHit(GroundingKernel.KIND_DEFECT, 3021L, null,
                        "登录页在并发下超时",
                        "严重级别：P1；状态：已关闭。修复人：张三；关闭原因：重复缺陷关闭。",
                        0.0, null, true));

        var asManager = GroundingKernel.assemble("登录页超时 修复人 关闭原因", pool,
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE, true);
        assertEquals(1, asManager.citations().size(), "one defect row, one citation");
        assertTrue(asManager.citations().get(0).excerpt().contains("张三"),
                "a system manager keeps the attribution shape of the row");

        var asMember = GroundingKernel.assemble("登录页超时 修复人 关闭原因", pool,
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE, false);
        assertEquals(1, asMember.citations().size(),
                "the history of the row stays citable for a plain member");
        assertFalse(asMember.citations().get(0).excerpt().contains("张三"),
                "responsibility facts never reach the member view");
        assertFalse(asMember.contextBlock().contains("关闭原因"),
                "nor reach the prompt context block of a member run");
    }

    @Test
    void relevantHelpChapterBecomesNumberedCitationWithContextBlock() {
        var outcome = GroundingKernel.assemble("批次执行失败原因在哪里查看",
                List.of(help(11, "E", "批次执行状态说明",
                        "批次执行失败时，每条用例的失败原因在执行记录页查看，含错误截图与日志。")),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        assertFalse(outcome.zeroHit(), "a clearly relevant chapter must ground the answer");
        assertEquals(1, outcome.citations().size());
        GroundingCitation cit = outcome.citations().get(0);
        assertEquals(1, cit.index());
        assertEquals(GroundingKernel.KIND_HELP, cit.kind());
        assertEquals(11, cit.refId());
        assertEquals("E", cit.domainCode(), "domain travels into the citation for anchor jumps");
        assertEquals("批次执行状态说明", cit.title());
        assertTrue(outcome.contextBlock().contains("[C1]"),
                "context block must mark the citation ordinal for inline references");
        assertTrue(outcome.contextBlock().contains("批次执行状态说明"));
    }

    @Test
    void zeroHitYieldsTheHonestEmptyShape() {
        // The negative assertion of TC-AO-07: retrieval found nothing to stand
        // on — citations and context block must both be empty, zeroHit true,
        // so the orchestrator can switch to the honest no-improvisation posture.
        // Fixtures mirror a real zero-hit run: the vector side may return
        // sub-floor noise (here 0.02 similarity), help chapters index-wide
        // share no term with the question — the floor must reject them all.
        var outcome = GroundingKernel.assemble("如何配置量子纠缠测试矩阵",
                List.of(help(11, "E", "批次执行状态说明", "批次状态机九态与转换表。"),
                        knowledge(21, "登录用例范例", "用户名密码登录的成功用例。", 0.02)),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        assertTrue(outcome.zeroHit());
        assertEquals(0, outcome.citations().size());
        assertEquals("", outcome.contextBlock());
    }

    @Test
    void citationsRankByScoreAndTruncateToBudget() {
        var outcome = GroundingKernel.assemble("用例评审驳回后如何重新提交",
                List.of(
                        help(1, "A", "用例状态机", "评审驳回的用例回到待修改状态，修改后可重新提交评审。"),
                        help(2, "B", "评审工作台", "驳回意见逐条列出，重新提交按钮在评审详情页右上角。"),
                        help(3, "C", "无关章节", "本页介绍平台通知订阅的设置方式与渠道选择。"),
                        help(4, "D", "重新提交操作指引", "评审驳回后在列表勾选用例点击重新提交即可再次进入评审。")),
                2, EXCERPT_CHARS, MIN_SCORE);

        assertFalse(outcome.zeroHit());
        assertEquals(2, outcome.citations().size(), "budget of 2 citations must truncate");
        double first = outcome.citations().get(0).score();
        double second = outcome.citations().get(1).score();
        assertTrue(first >= second, "best-first ordering");
        List<Long> ids = outcome.citations().stream().map(GroundingCitation::refId).toList();
        assertTrue(ids.contains(2L) || ids.contains(4L), "top hits are the review-resubmit chapters");
        assertFalse(ids.contains(3L), "the unrelated chapter must not occupy a slot");
    }

    @Test
    void sameSourceRowDedupesKeepingItsBestHit() {
        var outcome = GroundingKernel.assemble("批次执行失败原因在哪里查看",
                List.of(
                        help(7, "E", "批次执行状态说明", "失败原因在执行记录页逐条查看。"),
                        help(7, "E", "批次执行状态说明", "批次状态与用例状态相互独立。"),
                        knowledge(9, "失败归因", "执行失败常见原因为环境不可达与定位器过期。", 0.66)),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        long helpRows = outcome.citations().stream()
                .filter(c -> GroundingKernel.KIND_HELP.equals(c.kind())).count();
        assertEquals(1, helpRows, "the same help chapter (id 7) may be cited once");
    }

    @Test
    void excerptIsWindowedAroundTheMatchAndLengthCapped() {
        String longBody = "前言背景介绍".repeat(30) + "失败原因在执行记录页查看错误截图"
                + "尾部补充说明".repeat(30);
        var outcome = GroundingKernel.assemble("失败原因在哪里查看",
                List.of(help(5, "E", "执行记录说明", longBody)),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        assertFalse(outcome.zeroHit());
        String excerpt = outcome.citations().get(0).excerpt();
        assertTrue(excerpt.length() <= EXCERPT_CHARS,
                "excerpt " + excerpt.length() + " must not exceed the " + EXCERPT_CHARS + " budget");
        assertTrue(excerpt.contains("失败原因"), "excerpt must center on the matched term");
    }

    @Test
    void latinQueryMatchesWordsCaseInsensitively() {
        var outcome = GroundingKernel.assemble("how to retry a failed batch",
                List.of(help(8, "E", "Batch retry", "A failed batch can be retried from the execution record.")),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        assertFalse(outcome.zeroHit(), "latin word overlap grounds the answer");
        assertEquals(GroundingKernel.KIND_HELP, outcome.citations().get(0).kind());
    }

    /**
 * Revision pass-through : the kernel must move the hit's
 * ledger revision into the citation untouched, and help chapters — which
 * have no ledger row — serialize a null revision.
 */
    @Test
    void knowledgeRevisionPassesThroughToTheCitationAndHelpCarriesNull() {
        GroundingHit hit = new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, 33L, null,
                "回滚手册", "回滚按反序执行波次。", 0.6, 7);
        var outcome = GroundingKernel.assemble("回滚怎么执行",
                List.of(hit, help(11, "E", "回滚窗口说明", "回滚窗口在发布完成后开启。")),
                MAX_CITATIONS, EXCERPT_CHARS, MIN_SCORE);

        assertFalse(outcome.zeroHit());
        var knowledgeCit = outcome.citations().stream()
                .filter(c -> GroundingKernel.KIND_KNOWLEDGE.equals(c.kind()))
                .findFirst().orElseThrow();
        assertEquals(7, knowledgeCit.revision(), "revision must ride hit -> citation unchanged");
        var helpCit = outcome.citations().stream()
                .filter(c -> GroundingKernel.KIND_HELP.equals(c.kind()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(helpCit.revision(),
                "help chapters carry no ledger revision");
    }
}
