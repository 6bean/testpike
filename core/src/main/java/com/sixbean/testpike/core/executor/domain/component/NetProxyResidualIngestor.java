package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;


@Component
public class NetProxyResidualIngestor {

    private static final Logger log = LoggerFactory.getLogger(NetProxyResidualIngestor.class);
    private static final DateTimeFormatter DAY_WINDOW = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;

    public NetProxyResidualIngestor(NotificationService notificationService,
                                    AdminAlertRecipients adminAlertRecipients) {
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingest(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> admins = adminAlertRecipients.adminUserIds();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String serial = text(map.get("serial"));
            if (serial.isBlank()) {
                continue;
            }
            String proxy = text(map.get("proxy"));
            String action = text(map.get("action"));
            String detail = text(map.get("detail"));
            boolean cleared = "cleared".equals(action);
            String title = cleared ? "弱网代理残留已自动清除" : "弱网代理残留清除未确认";
            String content = cleared
                    ? "设备 " + serial + " 存在弱网会话残留的全局代理（" + proxy
                            + "），巡检已自动清除，无需处置。"
                    : "设备 " + serial + " 弱网代理残留（" + proxy + "）自动清除未确认"
                            + "（" + (detail.isBlank() ? "原因未留痕" : detail) + "），请人工处置。";
            String dedup = "NET_PROXY_RESIDUAL:" + serial + ":"
                    + LocalDate.now().format(DAY_WINDOW);
            for (Long adminId : admins) {
                try {
                    notificationService.create(new NotificationCreateInput(
                            adminId, null, "NET_PROXY_RESIDUAL", title, content,
                            dedup + ":" + adminId));
                } catch (Exception e) {
                    log.warn("NET_PROXY_RESIDUAL 告警发送失败（接收人 id={}）: {}",
                            adminId, e.getMessage());
                }
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
