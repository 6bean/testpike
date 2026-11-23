package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled expiry sweep for throwaway mock spaces the CI
 * doors of stamp {@code expires_at}, but without this sweeper the
 * stamp would be decoration: expired spaces would squat forever).
 * <p>Heartbeat/decision separation: the cron tick is fixed (default every 10
 * minutes, key {@code testpike.mock.space-expiry-cron}); the real moment is
 * each row's own {@code expires_at}. Same posture as AppautoLeaseSweepScheduler.
 * <p>Per-space disposition by lifecycle state:
 * <ul>
 * <li>published + no blocking references → retired through the state
 * machine's single edge (never a second UPDATE path, version bump included);</li>
 * <li>draft + no blocking references → hard deleted together with its child
 * rows: retiring a draft would land it in a state it could never legally
 * reach (the manual path's own refusal words say so);</li>
 * <li>blocking references present (open cassette boxes today, case
 * bindings later — one gate, MockSpaceReferenceGate) → deferred, nothing
 * is touched, and each project owner gets one plain-word notice per day
 * (dedup window) plus an audit row naming the reasons.</li>
 * </ul>
 * <p>The business entry {@link #sweepExpiredSpaces} is shared with the
 * run-now picker and the tests — the @Scheduled method only adds catch+alert.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MockSpaceExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(MockSpaceExpirySweeper.class);
    private static final DateTimeFormatter DAY_WINDOW = DateTimeFormatter.BASIC_ISO_DATE;

    /** tp_mock_space.status lifecycle values (CHECK: 0 draft / 1 published / 2 retired). */
    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_RETIRED = 2;

    private final MockSpaceService spaceService;
    private final MockSpaceReferenceGate referenceGate;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AuditLogService auditLogService;
    private final JobRunner jobRunner;

    public MockSpaceExpirySweeper(MockSpaceService spaceService,
                                  MockSpaceReferenceGate referenceGate,
                                  NotificationService notificationService,
                                  ProjectOwners projectOwners,
                                  AuditLogService auditLogService,
                                  JobRunner jobRunner) {
        this.spaceService = spaceService;
        this.referenceGate = referenceGate;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.auditLogService = auditLogService;
        this.jobRunner = jobRunner;
    }

    /** Expired-space sweep (default every 10 minutes, testpike.mock.space-expiry-cron key). */
    @SchedulerLock(name = "mockSpaceExpirySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.mock.space-expiry-cron:0 */10 * * * *}")
    public void sweep() {
        try {
            sweepExpiredSpaces();
        } catch (Exception e) {
            log.warn("Mock 空间到期清扫失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("mockSpaceExpirySweep", e);
        }
    }

    /**
     * One sweep round (the business entry shared by the cron catch, the
     * run-now picker and the tests).
     *
     * @return spaces settled this round (retired + deleted; deferred excluded)
     */
    public int sweepExpiredSpaces() {
        int settled = 0;
        for (MockSpaceInfo space : spaceService.listExpiredForSweep()) {
            if (settleOne(space)) {
                settled++;
            }
        }
        if (settled > 0) {
            log.info("Mock 空间到期清扫：本轮处理 {} 个过期空间（退役/删除，顺延另计）", settled);
        }
        return settled;
    }

    /**
     * @return true when the space was settled (retired or deleted), false when
     *         deferred by blocking references (or the row vanished under us)
     */
    private boolean settleOne(MockSpaceInfo space) {
        List<String> reasons = referenceGate.blockingReferences(space.id());
        if (!reasons.isEmpty()) {
            defer(space, reasons);
            return false;
        }
        int status = space.status() == null ? STATUS_DRAFT : space.status();
        if (status == STATUS_PUBLISHED) {
            spaceService.transition(space.id(), STATUS_RETIRED, space.version());
            return true;
        }
        if (status == STATUS_DRAFT) {
            spaceService.deleteDraft(space.id());
            return true;
        }
        return false; // retired rows never carry expiry work; never re-touch.
    }

    /** Defer = touch nothing + one notice per owner per day + audit row naming reasons. */
    private void defer(MockSpaceInfo space, List<String> reasons) {
        String joined = String.join("；", reasons);
        auditLogService.record(new AuditLogInput(null, space.projectId(), "mock_space",
                String.valueOf(space.id()), "mock_space_expiry_deferred",
                Map.of("expiresAt", String.valueOf(space.expiresAt())),
                Map.of("reasons", joined)));
        String dayWindow = LocalDate.now().format(DAY_WINDOW);
        for (Long ownerId : projectOwners.resolve(space.projectId())) {
            String dedupKey = "MOCK_SPACE_EXPIRY_DEFERRED:" + space.id() + ":" + ownerId + ":" + dayWindow;
            if (notificationService.existsByDedupKey(dedupKey)) {
                continue;
            }
            try {
                notificationService.create(new NotificationCreateInput(ownerId, space.projectId(),
                        "MOCK_SPACE_EXPIRY_DEFERRED",
                        "Mock 空间到期退役顺延：" + space.name(),
                        "临时 Mock 空间「" + space.name() + "」已过有效期（到期时间 "
                                + space.expiresAt() + "），但引用你的录制还在跑，退役顺延：" + joined
                                + "。处置完成后（停止录制）下一轮清扫会自动退役，无需手动操作。",
                        dedupKey));
            } catch (Exception e) {
                log.warn("MOCK_SPACE_EXPIRY_DEFERRED 通知失败（spaceId={} user={}）: {}",
                        space.id(), ownerId, e.getMessage());
            }
        }
    }

    // Extension slot note for : case/suite bindings will append blocking
    // reasons inside MockSpaceReferenceGate — this sweeper must not fork the query.
}
