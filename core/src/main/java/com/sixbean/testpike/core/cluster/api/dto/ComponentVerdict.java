package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Component support-state determination conclusion (three colors plus gray and a plain-language reason; the determination spec is produced from a single source and jointly consumed by the ledger and the dispatch gate).
 */
public record ComponentVerdict(String supportState, String stateReason) {

    public static final String GREEN = "GREEN";
    public static final String YELLOW = "YELLOW";
    public static final String RED = "RED";
    public static final String UNKNOWN = "UNKNOWN";
}
