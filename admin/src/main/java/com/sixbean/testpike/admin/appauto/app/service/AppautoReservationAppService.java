package com.sixbean.testpike.admin.appauto.app.service;

import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarView;

import java.time.LocalDateTime;


public interface AppautoReservationAppService {

    
    ReservationCalendarView calendar(long projectId, LocalDateTime from, LocalDateTime to);
}
