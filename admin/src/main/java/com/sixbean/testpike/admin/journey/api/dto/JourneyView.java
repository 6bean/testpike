package com.sixbean.testpike.admin.journey.api.dto;

import java.util.List;

/**
 * Journey view returned to the UI: header plus all ordered segments.
 * Field names mirror core {@code JourneyInfo} one-to-one (bridge matches by name).
 */
public record JourneyView(long id, long programId, String name, long ownerUserId,
                          int status, List<JourneySegmentView> segments) {
}
