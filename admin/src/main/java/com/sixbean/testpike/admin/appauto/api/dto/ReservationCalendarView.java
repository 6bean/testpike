package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record ReservationCalendarView(
        List<ReservationCalendarLane> lanes,
        int pendingApprovalCount,
        LocalDateTime from,
        LocalDateTime to) {
}
