package com.sixbean.testpike.core.judge.api.controller;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewView;
import com.sixbean.testpike.core.judge.api.dto.ReviewQueueView;
import com.sixbean.testpike.core.judge.domain.service.JudgeCalibrationProfileService;
import com.sixbean.testpike.core.judge.domain.service.JudgeReviewService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JudgeReviewInternalControllerTest {

    private final JudgeReviewService reviewService = mock(JudgeReviewService.class);
    private final JudgeCalibrationProfileService profileService = mock(JudgeCalibrationProfileService.class);
    private final JudgeReviewInternalController controller =
            new JudgeReviewInternalController(reviewService, profileService);

    private static JudgeReviewView review(long id) {
        return new JudgeReviewView(id, 7L, 8L, 9102L, null, 85, 60, "人工核过缺分支覆盖",
                LocalDateTime.of(2026, 10, 9, 16, 0, 0));
    }

    private static ReviewQueueView queueItem(long recordId) {
        return new ReviewQueueView(recordId, 7L, 8L, 100, 40,
                List.of("相关性", "完整性"), "判分无据（两次裁判均孤儿分）",
                LocalDateTime.of(2026, 10, 9, 16, 0, 0));
    }

    private static CalibrationGroupView group(String name) {
        return new CalibrationGroupView(name, "用例生成", 41L, 220, 0.72, 0.81,
                true, "达标：κ 0.72 ≥ 0.6 且配对 220 ≥ 基线 200");
    }

    @Test
    void 改判流水口_入参逐字段原样落服务_不吞缺recordId人话拒() {
        when(reviewService.recordReview(new JudgeReviewInput(8L, 9102L, null, 60, "缺覆盖")))
                .thenReturn(review(1L));

        JudgeReviewView got = controller.review(new JudgeReviewInput(8L, 9102L, null, 60, "缺覆盖"))
                .getData();
        assertEquals(85, got.orgScore(), "原分快照=service 派生值原样回显，本口禁重算");
        assertEquals(60, got.newScore());
        verify(reviewService).recordReview(new JudgeReviewInput(8L, 9102L, null, 60, "缺覆盖"));

        assertThrows(ValidationException.class, () -> controller.review(null),
                "空请求体口上直接人话拒（service 同口径，禁 NPE 落 50000）");
    }

    @Test
    void 改判流水回读口_recordId原样透传_倒序视图零加工() {
        when(reviewService.listReviews(8L)).thenReturn(List.of(review(2L), review(1L)));

        List<JudgeReviewView> got = controller.reviews(8L).getData();
        assertEquals(2, got.size(), "流水 append-only 全条数回显，本口禁去重禁截断");
        assertEquals(2L, got.get(0).id(), "最新在前顺序=service 口径，本口禁重排");
        verify(reviewService).listReviews(8L);
    }

    @Test
    void 人审队列口_run过滤与分页参数原样透传_空值语义不补默认() {
        when(reviewService.reviewQueue(isNull(), eq(0), eq(20)))
                .thenReturn(List.of(queueItem(9L)));

        List<ReviewQueueView> got = controller.reviewQueue(null, 0, 20).getData();
        assertEquals("判分无据（两次裁判均孤儿分）", got.get(0).queueReason(),
                "队列理由文案=service 单点人话，本口禁改写");
        verify(reviewService).reviewQueue(null, 0, 20);
    }

    @Test
    void 人审队列口_runId逐字段透传_归属闸在service() {
        when(reviewService.reviewQueue(eq(7L), eq(1), eq(50))).thenReturn(List.of());

        assertEquals(0, controller.reviewQueue(7L, 1, 50).getData().size());
        ArgumentCaptor<Long> runCap = ArgumentCaptor.forClass(Long.class);
        verify(reviewService).reviewQueue(runCap.capture(), eq(1), eq(50));
        assertEquals(7L, runCap.getValue(), "run 过滤值原样透传（null=全局的语义由 service 判）");
    }

    @Test
    void 校准档案口_三过滤参数原样落服务_组视图零二次统计() {
        when(profileService.profile("用例生成标准", "用例生成", 41L)).thenReturn(List.of(group("a")));

        List<CalibrationGroupView> got =
                controller.calibrationProfile("用例生成标准", "用例生成", 41L).getData();
        assertEquals(0.72, got.get(0).kappaPass(), "κ=JudgeCalibrationStats 单点产出，本口禁重算");
        verify(profileService).profile("用例生成标准", "用例生成", 41L);
    }

    @Test
    void 校准档案口_全空过滤即全组档案_禁本口补默认过滤() {
        when(profileService.profile(null, null, null)).thenReturn(List.of(group("a"), group("b")));

        assertEquals(2, controller.calibrationProfile(null, null, null).getData().size());
        verify(profileService).profile(null, null, null);
    }
}
