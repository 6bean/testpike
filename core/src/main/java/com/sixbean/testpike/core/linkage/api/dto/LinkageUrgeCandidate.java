package com.sixbean.testpike.core.linkage.api.dto;

import java.time.LocalDateTime;

/**
 * A pending impact ledger row the urge scan is evaluating .
 * Carries exactly what the notification copy needs: which upstream change
 * waits for whom, and for how long.
 */
public record LinkageUrgeCandidate(long impactId,
                                   String upstreamType,
                                   long upstreamId,
                                   String downstreamType,
                                   long downstreamId,
                                   LocalDateTime annotatedAt) {
}
