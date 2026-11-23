package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiCollectionServiceTest {

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Row-scoped teardown (R37②): never truncate shipped tables.
        ApiCollectionFixtureSupport.cleanFixtureRows(jdbcTemplate);
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    @Test
    void test_list_carries_per_collection_request_count() {
        long pid = createProject("W1CNT");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "订单域", null));
        ApiCollectionInfo child = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, root.id(), "退款", null));
        apiRequestService.create(1L, new RequestSaveInput(child.id(), "退款查询", "GET",
                "/api/v1/refunds", "[]", "", "{}", "[]"));
        apiRequestService.create(1L, new RequestSaveInput(child.id(), "退款提交", "POST",
                "/api/v1/refunds", "[]", "", "{}", "[]"));
        apiRequestService.create(1L, new RequestSaveInput(root.id(), "订单列表", "GET",
                "/api/v1/orders", "[]", "", "{}", "[]"));

        List<ApiCollectionInfo> tree = apiCollectionService.list(pid);
        ApiCollectionInfo listedRoot = tree.stream()
                .filter(c -> c.id() == root.id()).findFirst().orElseThrow();
        // Root badge counts its OWN requests only (children carry their own count;
        // a summed rollup would double-count when both levels are visible at once).
        assertEquals(1L, listedRoot.requestCount());
        ApiCollectionInfo listedChild = listedRoot.children().get(0);
        assertEquals(2L, listedChild.requestCount());
        // get() single fetch keeps the same column honest.
        assertEquals(2L, apiCollectionService.get(child.id()).requestCount());
    }

    @Test
    void test_collection_tree_two_level_and_depth_reject() {
        long pid = createProject("W1A");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "支付域", "支付,核心"));
        assertNotNull(root.id());
        assertEquals(0L, root.parentId());

        ApiCollectionInfo child = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, root.id(), "退款"));
        assertEquals(root.id(), child.parentId());

        ServiceException deep = assertThrows(ServiceException.class, () ->
                apiCollectionService.create(1L, new CollectionSaveInput(pid, child.id(), "三级目录")));
        assertTrue(deep.getMessage().contains("两级"));
    }

    @Test
    void test_same_level_name_conflict_and_rename() {
        long pid = createProject("W1B");
        long otherPid = createProject("W1B2");
        apiCollectionService.create(1L, new CollectionSaveInput(pid, 0L, "订单", null));

        ServiceException dup = assertThrows(ServiceException.class, () ->
                apiCollectionService.create(1L, new CollectionSaveInput(pid, 0L, "订单", null)));
        assertTrue(dup.getMessage().contains("订单"));

        ApiCollectionInfo root2 = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "结算", null));
        assertNotNull(apiCollectionService.create(1L,
                new CollectionSaveInput(pid, root2.id(), "订单", null)));
        assertNotNull(apiCollectionService.create(1L,
                new CollectionSaveInput(otherPid, 0L, "订单", null)));

        long orderId = apiCollectionService.list(pid).stream()
                .filter(c -> c.name().equals("订单")).findFirst().orElseThrow().id();
        ServiceException re = assertThrows(ServiceException.class, () ->
                apiCollectionService.rename(1L, root2.id(), "订单", null));
        assertTrue(re.getMessage().contains("订单"));
        ApiCollectionInfo idempotent = apiCollectionService.rename(1L, orderId, "订单", "交易");
        assertEquals("订单", idempotent.name());
        assertEquals("交易", idempotent.tags());
    }

    @Test
    void test_delete_children_guard_and_leaf_ok() {
        long pid = createProject("W1C");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "会员", null));
        apiCollectionService.create(1L, new CollectionSaveInput(pid, root.id(), "积分"));

        ServiceException block = assertThrows(ServiceException.class, () ->
                apiCollectionService.delete(1L, root.id()));
        assertTrue(block.getMessage().contains("子目录"));

        long childId = apiCollectionService.list(pid).get(0).children().get(0).id();
        apiCollectionService.delete(1L, childId);
        List<ApiCollectionInfo> tree = apiCollectionService.list(pid);
        assertEquals(1, tree.size());
        assertTrue(tree.get(0).children().isEmpty());
    }

    @Test
    void test_list_tree_shape_and_isolation() {
        long pid = createProject("W1D");
        long pidB = createProject("W1D2");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "营销中心", null));
        apiCollectionService.create(1L, new CollectionSaveInput(pid, root.id(), "优惠券"));
        apiCollectionService.create(1L, new CollectionSaveInput(pidB, 0L, "别的项目", null));

        List<ApiCollectionInfo> tree = apiCollectionService.list(pid);
        assertEquals(1, tree.size());
        assertEquals("营销中心", tree.get(0).name());
        assertEquals(1, tree.get(0).children().size());
        assertEquals("优惠券", tree.get(0).children().get(0).name());

        ApiCollectionInfo got = apiCollectionService.get(root.id());
        assertEquals("营销中心", got.name());
        assertThrows(ServiceException.class, () -> apiCollectionService.get(999999999L));
    }
}
