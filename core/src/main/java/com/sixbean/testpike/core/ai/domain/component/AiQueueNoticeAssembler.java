package com.sixbean.testpike.core.ai.domain.component;

/**
 * Assembles the queue-waiting notice shown on batch progress while the
 * generation backlog is over the configured threshold. The sentence always
 * carries three parts — the reason, the current backlog count, and a
 * recovery estimate — and degrades honestly (estimate replaced by a note
 * that it will update itself) when no completed task exists in the
 * sampling window to derive an average duration from. Pure function: the
 * service owns reading the hot-update keys and the counts.
 */
public final class AiQueueNoticeAssembler {

    private AiQueueNoticeAssembler() {
    }

    /**
     * @param batchWaiting waiting item count of the queried batch (0 silences the notice)
     * @param backlog      global pending+running generation tasks
     * @param avgSeconds   average duration of recently done tasks, null when no sample
     * @param concurrency  generation consumer concurrency used for the estimate
     * @param threshold    backlog count at which the notice starts showing
     * @param sampleHours  sampling window behind avgSeconds, named in the text
     * @return the three-part notice, or null when nothing is worth telling
     */
    public static String build(int batchWaiting, int backlog, Long avgSeconds,
                               int concurrency, int threshold, int sampleHours) {
        if (batchWaiting <= 0 || backlog < threshold) {
            return null;
        }
        String reason = "生成队列当前积压 " + backlog + " 条待处理任务，本批次 "
                + batchWaiting + " 条任务正在排队；";
        if (avgSeconds == null || avgSeconds <= 0 || concurrency <= 0) {
            return reason + "最近 " + sampleHours
                    + " 小时内暂无已完成任务可作耗时样本，恢复预计将在有样本后自动更新。";
        }
        long minutes = Math.max(1L, (long) Math.ceil(
                backlog * (double) avgSeconds / (60.0 * concurrency)));
        return reason + "按最近 " + sampleHours + " 小时平均耗时 " + avgSeconds
                + " 秒/条、" + concurrency + " 路并发估算，预计约 " + minutes
                + " 分钟后完成当前排队。";
    }
}
