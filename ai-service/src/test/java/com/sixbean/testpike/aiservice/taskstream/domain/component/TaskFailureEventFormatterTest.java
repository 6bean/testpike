package com.sixbean.testpike.aiservice.taskstream.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the single-source terminal-failure formatter:
 * the closed category decision (model side / validation side / environment
 * side), the user-shaped summary line the drawer must always be able to show
 * as the last event, and the machine detail face (reasonCategory/suggestion/
 * retryEntry) a retry affordance keys on. Every failure branch of every job
 * family consumes this one component — a drift here is a drift everywhere,
 * which is exactly why it is pinned at component level.
 */
class TaskFailureEventFormatterTest {

    @Test
    void 模型侧归类_超时与供应商文案与AI错误码() {
        TaskFailureEvent timeout = TaskFailureEventFormatter.format(
                new ServiceException("模型调用超时，请稍后重试", BizErrorCode.AI_TIMEOUT),
                "任务详情页「重试生成」按钮");
        assertEquals(TaskFailureEventFormatter.CATEGORY_MODEL, timeout.category());
        TaskFailureEvent provider = TaskFailureEventFormatter.format(
                "AI 供应商调用失败", "评测集页「AI 造题」按钮");
        assertEquals(TaskFailureEventFormatter.CATEGORY_MODEL, provider.category());
        TaskFailureEvent coded = TaskFailureEventFormatter.format(
                new ServiceException("transport hiccup", BizErrorCode.AI_PROVIDER_ERROR),
                "入口");
        assertEquals(TaskFailureEventFormatter.CATEGORY_MODEL, coded.category(),
                "无文案签名时业务码兜底归类");
    }

    @Test
    void 校验侧归类_未生成与拒收类文案() {
        TaskFailureEvent empty = TaskFailureEventFormatter.format(
                "文档中未生成可回答的问答对，请确认上传的是含事实内容的文档", "入口");
        assertEquals(TaskFailureEventFormatter.CATEGORY_VALIDATION, empty.category());
        TaskFailureEvent rejected = TaskFailureEventFormatter.format(
                "保存被校验拒绝：字段不合法", "入口");
        assertEquals(TaskFailureEventFormatter.CATEGORY_VALIDATION, rejected.category());
    }

    /**
     * Model-output shape failures (the ring + targeted repair spent, still
     * broken) must never send the user to adjust the input (R63 false road):
     * keyed on the business code, the suggestion names the system action
     * already taken, the honest next click, and the model-switch escape —
     * while the category stays in the closed three-value vocabulary.
     */
    @Test
    void 模型输出格式失败_不指使调整输入_给系统动作与换模型出路() {
        TaskFailureEvent formatError = TaskFailureEventFormatter.format(
                new ServiceException("AI 输出格式校验失败（推理确认环 2 轮残余："
                        + "上次输出格式不合格：生成结果缺少测试目标(title)）",
                        BizErrorCode.AI_FORMAT_ERROR),
                "任务详情页「重试生成」按钮");
        assertEquals(TaskFailureEventFormatter.CATEGORY_VALIDATION, formatError.category());
        assertTrue(formatError.suggestion().contains("无需调整输入内容"), formatError.suggestion());
        assertTrue(formatError.suggestion().contains("系统设置→模型接入"), formatError.suggestion());
        assertTrue(!formatError.suggestion().contains("请按上面点名的事实调整内容"),
                "格式类残余不得沿用让输入背锅的通用校验建议: " + formatError.suggestion());
        assertTrue(formatError.summary().contains("重试入口：任务详情页「重试生成」按钮"),
                formatError.summary());
        assertTrue(formatError.summary().length() <= 512,
                "摘要必须装得进事件门的 512 列宽: " + formatError.summary().length());
        assertTrue(formatError.detailJson().contains("模型输出格式经系统自动重生成与字段补全"),
                formatError.detailJson());
    }

    /** The script stage re-rolls but never field-fills: its wording claims only
     *  what actually ran, and must never inherit the case-stage sentence. */
    @Test
    void 脚本阶段格式失败_建议只述重生成不述字段补全() {
        TaskFailureEvent script = TaskFailureEventFormatter.format(
                new ServiceException("AI 脚本格式校验失败（推理确认环 2 轮残余："
                        + "上次脚本不合格：第 5 步缺少目标定位）",
                        BizErrorCode.AI_FORMAT_ERROR),
                "任务详情页「重试生成」按钮");
        assertEquals(TaskFailureEventFormatter.CATEGORY_VALIDATION, script.category());
        assertTrue(script.suggestion().startsWith("脚本格式经系统自动重生成后仍未通过校验"),
                script.suggestion());
        assertTrue(!script.suggestion().contains("字段补全"), script.suggestion());
        assertTrue(script.suggestion().contains("无需调整输入内容"), script.suggestion());
    }

    @Test
    void 环境侧归类_服务异常与连接类_且未分类默认环境侧() {
        TaskFailureEvent svc = TaskFailureEventFormatter.format(
                "需求点提取服务异常: boom", "入口");
        assertEquals(TaskFailureEventFormatter.CATEGORY_ENVIRONMENT, svc.category());
        TaskFailureEvent fallback = TaskFailureEventFormatter.format(
                "something unexpected happened", "入口");
        assertEquals(TaskFailureEventFormatter.CATEGORY_ENVIRONMENT, fallback.category(),
                "无法归类的异常保守记为环境侧");
    }

    @Test
    void 摘要形态_三段齐备_类别在闭集内() {
        TaskFailureEvent event = TaskFailureEventFormatter.format(
                "模型调用超时", "任务详情页「重新生成」按钮");
        assertTrue(event.summary().startsWith("失败：【模型侧】"), event.summary());
        assertTrue(event.summary().contains("建议："), event.summary());
        assertTrue(event.summary().contains("重试入口：任务详情页「重新生成」按钮"),
                event.summary());
        assertTrue(event.summary().length() <= 512,
                "摘要必须装得进事件门的 512 列宽: " + event.summary().length());
    }

    @Test
    void 空原因不空白_长原因截断_详情机读面字段齐备() {
        TaskFailureEvent blank = TaskFailureEventFormatter.format((String) null, null);
        assertNotNull(blank.summary());
        assertTrue(blank.summary().contains("未知原因"), blank.summary());
        assertTrue(blank.summary().contains("任务详情页的重新发起入口"),
                "缺重试入口时给出朴素默认指路: " + blank.summary());

        StringBuilder longCause = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longCause.append("模型调用失败片段");
        }
        TaskFailureEvent trimmed = TaskFailureEventFormatter.format(longCause.toString(), "入口");
        assertTrue(trimmed.summary().length() <= 512,
                "超长原因必须截断保尾形态: " + trimmed.summary().length());

        TaskFailureEvent event = TaskFailureEventFormatter.format("模型调用超时", "入口");
        assertTrue(event.detailJson().contains("\"reasonCategory\":\"模型侧\""), event.detailJson());
        assertTrue(event.detailJson().contains("\"suggestion\""), event.detailJson());
        assertTrue(event.detailJson().contains("\"retryEntry\""), event.detailJson());
    }
}
