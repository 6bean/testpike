package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record ReservationCalendarLane(
        long deviceId,
        String name,
        String model,
        List<AppautoReservationView> reservations,
        AppautoLeaseRunView activeLease) {
}
