package com.sixbean.testpike.core.appauto.domain.service.impl;

import java.time.LocalDateTime;


public class AppautoReservationConflictRow {

    private final long id;
    private final String holderName;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final String reason;
    private final LocalDateTime activeLeaseExpires;

    public AppautoReservationConflictRow(long id, String holderName, LocalDateTime startsAt,
                                         LocalDateTime endsAt, String reason,
                                         LocalDateTime activeLeaseExpires) {
        this.id = id;
        this.holderName = holderName;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
        this.activeLeaseExpires = activeLeaseExpires;
    }

    public long id() {
        return id;
    }

    public String holderName() {
        return holderName;
    }

    public LocalDateTime startsAt() {
        return startsAt;
    }

    public LocalDateTime endsAt() {
        return endsAt;
    }

    public String reason() {
        return reason;
    }

    public LocalDateTime activeLeaseExpires() {
        return activeLeaseExpires;
    }
}
