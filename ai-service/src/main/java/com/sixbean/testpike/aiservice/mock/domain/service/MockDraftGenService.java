package com.sixbean.testpike.aiservice.mock.domain.service;

import com.sixbean.testpike.common.mq.MockGenMessage;

/**
 * AI mock-drafting orchestration , application point A-1
 * nl-stub and its successors).
 * <p>Flow contract: pull the job row over the core door (identity-only
 * payload — never trust a fat message) → skip anything not 'queued' → seal
 * running → LLM drafting under the {@code MOCK_DRAFT_GEN_JSON} contract
 * marker → parse the draft bundle → upload it through the presigned grant
 * → write back preview with the object key. The running seal doubles as the
 * concurrency guard: a losing redelivery catches the refusal and acks
 * silently. Every other failure seals the job failed in plain words so the
 * UI tells the truth instead of spinning on a stuck 'running' row.
 */
public interface MockDraftGenService {

    /** Run one drafting job to preview or failure (idempotent per job+token upstream). */
    void generate(MockGenMessage message);
}
