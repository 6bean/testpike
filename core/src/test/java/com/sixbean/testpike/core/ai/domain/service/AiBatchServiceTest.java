package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.api.dto.AiBatchItemInfo;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AiBatchServiceTest {

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigService configService;

    // Fixture-scoped teardown : id watermarks captured before each
    // test; @AfterEach deletes only rows this run created above the floors. The
    // blanket TRUNCATE rewound shared sequences and wiped concurrent suites'
    // task/audit/notification rows on the dev database.
    private long batchFloor;
    private long taskFloor;
    private long notificationFloor;
    private long auditFloor;

    @BeforeEach
    void markWatermarksAndResetConfig() {
        batchFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_ai_batch");
        taskFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_ai_task");
        notificationFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");
        auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
        // Guard against stale config caches from sibling suites: a hot-update
        // test that set the cap to 100 restores the row via raw SQL without
        // touching the cache, so the cap key is always reset through the
        // configService write path (= cache synced to 500; semantic premise of
        // the 501/51/500 boundary cases in this class).
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = 'ai.batch.max-count'");
        configService.create(1L, new ConfigInput(
                "ai.batch.max-count", "500", "系统", "int", 0, "批量上限（测试重置=V78 种子值）"));
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_ai_batch", batchFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_ai_task", taskFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_notification", notificationFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_audit_log", auditFloor);
    }

    private static List<String> reqs(String... texts) {
        return new ArrayList<>(List.of(texts));
    }

    @Test
    void createBatch_3条需求_创建批次且子任务pending关联batchId() {
        AiBatchCreateResult result = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(10L, 5L, reqs("用户登录功能", "订单支付功能", "库存查询功能")));

        assertNotNull(result);
        assertEquals(3, result.taskIds().size());

        AiBatchInfo batch = aiBatchService.get(result.batchId());
        assertNotNull(batch);
        assertEquals(10L, batch.projectId());
        assertEquals(3, batch.total());
        assertEquals(3, batch.pending());
        assertEquals(0, batch.done());
        assertEquals(0, batch.failed());
        assertEquals(3, batch.items().size());
        assertEquals("用户登录功能", batch.items().get(0).requirementText());
        assertEquals(1, batch.items().get(0).status());
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task WHERE batch_id = ?", Long.class, result.batchId());
        assertEquals(3L, cnt);
    }


    // Tri-state door for the batch default-enumeration switch: missing row = shipped
    // default on; explicit 0/false = off; '1' stays compatible with the seed shape.

    private void setAutoEnumerateValue(String value) {
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?",
                AiBatchServiceImpl.KEY_BATCH_AUTO_ENUMERATE);
        configService.create(1L, new ConfigInput(
                AiBatchServiceImpl.KEY_BATCH_AUTO_ENUMERATE, value, "系统", "bool", 0, "批量生成默认自动枚举开关（测试写入）"));
    }

    @Test
    void 自动枚举开关_缺行_回退出厂默认开() {
        // The delete must ride the service channel so the config cache is evicted
        // too — a bare SQL delete would leave a stale cached value behind.
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                (rs, n) -> rs.getLong(1), AiBatchServiceImpl.KEY_BATCH_AUTO_ENUMERATE)
                .stream().findFirst().orElse(null);
        if (id != null) {
            configService.delete(1L, id);
        }
        assertTrue(aiBatchService.currentAutoEnumerate(), "missing row must keep the shipped default of on");
        // Restore the factory row for the shared dev database (seed channel V613).
        setAutoEnumerateValue("true");
    }

    @Test
    void 自动枚举开关_显式关闭_读回关() {
        try {
            setAutoEnumerateValue("false");
            org.junit.jupiter.api.Assertions.assertFalse(aiBatchService.currentAutoEnumerate());
            setAutoEnumerateValue("0");
            org.junit.jupiter.api.Assertions.assertFalse(aiBatchService.currentAutoEnumerate(), "0 must read as off too");
        } finally {
            setAutoEnumerateValue("true");
        }
        assertTrue(aiBatchService.currentAutoEnumerate(), "restore back to the seed value of on");
    }

    @Test
    void 自动枚举开关_数字1兼容形态_读回开() {
        try {
            setAutoEnumerateValue("1");
            assertTrue(aiBatchService.currentAutoEnumerate(), "'1' and 'true' are the same on-state");
        } finally {
            setAutoEnumerateValue("true");
        }
    }

    @Test
    void createBatch_501条_超默认500拒_人话含当前配置值() {
        List<String> fiveOhOne = IntStream.rangeClosed(1, 501)
                .mapToObj(i -> "需求" + i).collect(Collectors.toCollection(ArrayList::new));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(AiBatchCreateInput.fromTexts(10L, 5L, fiveOhOne)));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("500"), "人话应含当前上限值: " + ex.getMessage());
    }

    @Test
    void createBatch_51条_默认500内_允许() {
        List<String> fiftyOne = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "需求" + i).collect(Collectors.toCollection(ArrayList::new));

        AiBatchCreateResult result = aiBatchService.createBatch(AiBatchCreateInput.fromTexts(10L, 5L, fiftyOne));

        assertNotNull(result);
        assertEquals(51, result.taskIds().size());
    }

    @Test
    void createBatch_500条上限边界_允许() {
        List<String> fiveHundred = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "需求" + i).collect(Collectors.toCollection(ArrayList::new));

        AiBatchCreateResult result = aiBatchService.createBatch(AiBatchCreateInput.fromTexts(10L, 5L, fiveHundred));

        assertNotNull(result);
        assertEquals(500, result.taskIds().size());
    }

    @Test
    void createBatch_K8改100_即时生效_101条拒人话含100且100条过() {
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = 'ai.batch.max-count'");
        var created = configService.create(1L, new ConfigInput(
                "ai.batch.max-count", "100", "系统", "int", 0, "批量上限（TC-V8-01 热更）"));
        try {
            List<String> aHundredOne = IntStream.rangeClosed(1, 101)
                    .mapToObj(i -> "需求" + i).collect(Collectors.toCollection(ArrayList::new));
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> aiBatchService.createBatch(AiBatchCreateInput.fromTexts(10L, 5L, aHundredOne)));
            assertEquals(40001, ex.getCode());
            assertTrue(ex.getMessage().contains("100"),
                    "改值后不重启应即时生效（K8/TC-V8-01）: " + ex.getMessage());

            List<String> aHundred = IntStream.rangeClosed(1, 100)
                    .mapToObj(i -> "需求" + i).collect(Collectors.toCollection(ArrayList::new));
            assertEquals(100, aiBatchService.createBatch(
                    AiBatchCreateInput.fromTexts(10L, 5L, aHundred)).taskIds().size(), "改值后 100 边界放行");
        } finally {
            configService.delete(1L, created.id());
        }
    }

    @Test
    void createBatch_空列表_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(AiBatchCreateInput.fromTexts(10L, 5L, new ArrayList<>())));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("至少"), "人话应提示至少 1 条: " + ex.getMessage());
    }

    @Test
    void createBatch_含空白需求_400第N条不能为空() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(
                        AiBatchCreateInput.fromTexts(10L, 5L, reqs("正常需求", "   "))));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("2"), "人话应指明第 2 条: " + ex.getMessage());
    }

    @Test
    void createBatch_某条超5000字_400第N条过长() {
        String tooLong = "长".repeat(5001);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiBatchService.createBatch(
                        AiBatchCreateInput.fromTexts(10L, 5L, reqs("正常需求", tooLong))));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("2"), "人话应指明第 2 条: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("5000"), "人话应含上限: " + ex.getMessage());
    }

    @Test
    void estimate_3条_返回条数与正数成本() {
        AiBatchEstimate est = aiBatchService.estimate(10L, reqs("用户登录功能", "订单支付功能", "库存查询功能"));

        assertNotNull(est);
        assertEquals(3, est.count());
        assertTrue(est.estimatedTokens() > 0, "预估 token 应为正数");
        assertTrue(est.estimatedCostYuan() > 0, "预估成本应为正数");
        AiBatchEstimate short1 = aiBatchService.estimate(10L, reqs("登录"));
        AiBatchEstimate long1 = aiBatchService.estimate(10L, reqs("这是一条非常非常非常长的需求描述用于验证成本预估随字数增长"));
        assertTrue(long1.estimatedTokens() > short1.estimatedTokens(), "同条数更长需求预估 token 应更多");
    }

    @Test
    void estimate_空列表_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiBatchService.estimate(10L, new ArrayList<>()));
        assertEquals(40001, ex.getCode());
    }

    @Test
    void get_部分完成_聚合计数与条目明细正确() {
        AiBatchCreateResult result = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(10L, 5L, reqs("需求A", "需求B", "需求C")));
        long batchId = result.batchId();
        // 1 done + 1 failed + 1 pending
        long taskA = result.taskIds().get(0);
        long taskB = result.taskIds().get(1);
        aiTaskService.markRunning(taskA);
        aiTaskService.markDone(taskA, 888L);
        aiTaskService.markRunning(taskB);
        aiTaskService.markFailed(taskB, "MODEL_TIMEOUT", "模型调用超时，请稍后重试");

        AiBatchInfo batch = aiBatchService.get(batchId);

        assertEquals(3, batch.total());
        assertEquals(1, batch.done());
        assertEquals(1, batch.failed());
        assertEquals(1, batch.pending());
        assertEquals(0, batch.running());
        AiBatchItemInfo done = batch.items().get(0);
        assertEquals(3, done.status());
        assertEquals(888L, done.caseId());
        assertNull(done.errorMsg());
        AiBatchItemInfo failed = batch.items().get(1);
        assertEquals(4, failed.status());
        assertEquals("MODEL_TIMEOUT", failed.errorCode());
        assertEquals("模型调用超时，请稍后重试", failed.errorMsg());
        assertNull(failed.caseId());
        assertEquals(1, batch.items().get(2).status());
    }

    @Test
    void get_全挂_任务保留队列不丢弃_部分失败仅重试失败项() {
        AiBatchCreateResult result = aiBatchService.createBatch(
                AiBatchCreateInput.fromTexts(10L, 5L, reqs("需求A", "需求B", "需求C")));
        long batchId = result.batchId();
        for (long taskId : result.taskIds()) {
            aiTaskService.markRunning(taskId);
            aiTaskService.markFailed(taskId, "SERVICE_ERROR", "AI 生成暂不可用，请稍后重试");
        }

        AiBatchInfo batch = aiBatchService.get(batchId);
        assertEquals(3, batch.failed());
        assertEquals(0, batch.done());
        assertEquals(3, batch.items().size());

        aiTaskService.retry(result.taskIds().get(0));
        aiTaskService.retry(result.taskIds().get(1));

        AiBatchInfo after = aiBatchService.get(batchId);
        assertEquals(2, after.pending(), "仅被重试的失败项回队列");
        assertEquals(1, after.failed(), "未重试的失败项保持失败（不被连带）");
        assertEquals(0, after.done());
    }

    @Test
    void get_批次不存在_返回null() {
        assertNull(aiBatchService.get(999999L));
    }
}
