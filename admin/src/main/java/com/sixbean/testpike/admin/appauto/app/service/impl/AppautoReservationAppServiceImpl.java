package com.sixbean.testpike.admin.appauto.app.service.impl;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarLane;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarView;
import com.sixbean.testpike.admin.appauto.app.service.AppautoReservationAppService;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class AppautoReservationAppServiceImpl implements AppautoReservationAppService {

    private final CoreAppautoClient coreAppautoClient;

    public AppautoReservationAppServiceImpl(CoreAppautoClient coreAppautoClient) {
        this.coreAppautoClient = coreAppautoClient;
    }

    @Override
    public ReservationCalendarView calendar(long projectId, LocalDateTime from,
                                            LocalDateTime to) {
        List<AppautoDeviceView> devices = coreAppautoClient.listDevices(projectId, null);
        List<AppautoReservationView> reservations =
                coreAppautoClient.listReservations(null, projectId, null, false);
        Map<Long, List<AppautoReservationView>> byDevice = new HashMap<>();
        int pendingApprovalCount = 0;
        for (AppautoReservationView r : reservations) {
            if ("PENDING_APPROVAL".equals(r.state())) {
                pendingApprovalCount++;
            }
            if (overlaps(from, to, r)) {
                byDevice.computeIfAbsent(r.deviceId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<ReservationCalendarLane> lanes = new ArrayList<>(devices.size());
        for (AppautoDeviceView device : devices) {
            long deviceId = device.id();
            List<AppautoReservationView> laneReservations =
                    byDevice.getOrDefault(deviceId, List.of());
            lanes.add(new ReservationCalendarLane(deviceId, device.name(), device.model(),
                    laneReservations, findActiveLease(deviceId)));
        }
        return new ReservationCalendarView(lanes, pendingApprovalCount, from, to);
    }

    
    private static boolean overlaps(LocalDateTime from, LocalDateTime to,
                                    AppautoReservationView r) {
        return r.startsAt() != null && r.endsAt() != null
                && r.startsAt().isBefore(to) && from.isBefore(r.endsAt());
    }

    
    private AppautoLeaseRunView findActiveLease(long deviceId) {
        List<AppautoLeaseRunView> runs = coreAppautoClient.listDeviceLeaseRuns(deviceId);
        return runs.stream()
                .filter(run -> "ACTIVE".equals(run.state()))
                .max(Comparator.comparing(AppautoLeaseRunView::acquiredAt))
                .orElse(null);
    }
}
