package com.sixbean.testpike.common.mq;


public record JudgeSampleMessage(long runId, long sampleId, long llmConfigId,
                                 String evalsetDigest) {
}
