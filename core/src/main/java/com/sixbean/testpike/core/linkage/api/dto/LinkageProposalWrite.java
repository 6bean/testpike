package com.sixbean.testpike.core.linkage.api.dto;

/**
 * Result of one proposal snapshot write: the stored row plus whether THIS
 * call inserted it (created=true) or found it already in place (a replay, or
 * a frozen consumed row). The account flag keeps "did the write change the
 * ledger" observable without a second read.
 */
public record LinkageProposalWrite(LinkageProposalRecord record, boolean created) {

    public Long id() {
        return record.id();
    }

    public Integer status() {
        return record.status();
    }
}
