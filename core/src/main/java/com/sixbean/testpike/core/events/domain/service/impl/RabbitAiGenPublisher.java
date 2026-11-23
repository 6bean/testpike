package com.sixbean.testpike.core.events.domain.service.impl;

import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;


@Component
public class RabbitAiGenPublisher implements AiGenPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitAiGenPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitAiGenPublisher(RabbitTemplate rabbitTemplate,
                                AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publish(AiGenMessage message) {
        publish(message, false);
    }

    @Override
    public void publish(AiGenMessage message, boolean windowFeed) {
        String routingKey = windowFeed
                ? (message.taskType() == 2 ? ROUTING_SCRIPT_GENERATE_WINDOW : ROUTING_CASE_GENERATE_WINDOW)
                : (message.taskType() == 2 ? ROUTING_SCRIPT_GENERATE : ROUTING_CASE_GENERATE);
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, routingKey, message);
        log.info("AI 生成任务已入队 taskId={} projectId={} taskType={} routingKey={}",
                message.taskId(), message.projectId(), message.taskType(), routingKey);
    }

    @Override
    public void publishExtract(AiDocExtractMessage message) {
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_DOC_EXTRACT, message);
        log.info("F2 提取任务已入队 documentId={} projectId={} routingKey={}",
                message.documentId(), message.projectId(), ROUTING_DOC_EXTRACT);
    }

    @Override
    public void publishExploreRun(ExploreRunMessage message) {
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_EXPLORE_RUN, message);
        log.info("B9 探索任务已入队 sessionId={} projectId={} routingKey={}",
                message.sessionId(), message.projectId(), ROUTING_EXPLORE_RUN);
    }

    @Override
    public void publishSpecExtract(com.sixbean.testpike.common.mq.AiSpecExtractMessage message) {
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_SPEC_EXTRACT, message);
        log.info("通道 B 抽取任务已入队 documentId={} projectId={} routingKey={}",
                message.documentId(), message.projectId(), ROUTING_SPEC_EXTRACT);
    }

    @Override
    public void publishJudgeSample(JudgeSampleMessage message) {
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_JUDGE_SAMPLE, message);
        log.info("LLM 裁判任务已入队 runId={} sampleId={} routingKey={}",
                message.runId(), message.sampleId(), ROUTING_JUDGE_SAMPLE);
    }

    @Override
    public void publishMockGen(MockGenMessage message) {
        // Plain direct send (no afterCommit): the caller is the controller
        // publish point running outside the job-insert transaction, mirroring
        // publishSpecExtract exactly. A publish failure propagates as-is — the
        // job row is already committed and the DLQ/reconcile story is 's.
        rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_MOCK_GEN, message);
        log.info("AI mock draft job enqueued jobId={} spaceId={} routingKey={}",
                message.jobId(), message.spaceId(), ROUTING_MOCK_GEN);
    }

    @Override
    public void publishKbIngest(AiKbIngestMessage message) {
        afterCommitExecutor.execute(() -> {
            try {
                rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE, ROUTING_KB_INGEST, message);
                log.info("G1 知识库入库任务已入队 documentId={} projectId={} routingKey={}",
                        message.documentId(), message.projectId(), ROUTING_KB_INGEST);
            } catch (RuntimeException e) {
                log.error("G1 知识库入库任务发布失败 documentId={} projectId={}（已记录，对账兜底）",
                        message.documentId(), message.projectId(), e);
            }
        });
    }
}
