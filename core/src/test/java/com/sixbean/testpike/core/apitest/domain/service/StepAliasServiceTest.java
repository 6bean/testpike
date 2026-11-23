package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInfo;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class StepAliasServiceTest {

    @Autowired
    private StepAliasService stepAliasService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void sweepStepAliasProjectFixtures() {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
    }

    private long createStepAliasProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                projectIds, "项目" + code, code);
    }

    @Test
    void test_path与step两档登记与列表() {
        long pid = createStepAliasProject("V7A");
        StepAliasInfo pathAlias = stepAliasService.create(1L,
                new StepAliasInput(pid, "订单号", "path", "$.data.orderNo", null));
        assertNotNull(pathAlias.id());
        StepAliasInfo stepAlias = stepAliasService.create(1L,
                new StepAliasInput(pid, "创建订单", "step", "3.orderNo", "req-9"));
        assertEquals(2, stepAliasService.list(pid).size());
        Set<String> names = stepAliasService.aliasNames(pid);
        assertTrue(names.contains("订单号") && names.contains("创建订单"));
        assertNotNull(stepAlias.target());
    }

    @Test
    void test_三冲突_保存期人话拒() {
        long pid = createStepAliasProject("V7B");
        ServiceException digit = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid, "3", "step", "3.orderNo", null)));
        assertTrue(digit.getMessage().contains("纯数字"), "stepNo 形态须人话点名: " + digit.getMessage());
        ServiceException reserved = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid, "global", "path", "x", null)));
        assertTrue(reserved.getMessage().contains("保留"), "保留字须人话点名: " + reserved.getMessage());
        ServiceException dotted = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid, "a.b", "path", "x", null)));
        assertTrue(dotted.getMessage().contains("点号"), "点号劫持须人话点名: " + dotted.getMessage());
        assertEquals(0, stepAliasService.list(pid).size(), "三冲突均不得落库");
    }

    @Test
    void test_同项目重名_冲突拒_跨项目同名放行() {
        long pid1 = createStepAliasProject("V7C");
        long pid2 = createStepAliasProject("V7D");
        stepAliasService.create(1L, new StepAliasInput(pid1, "创建订单", "step", "3.orderNo", null));
        ServiceException dup = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid1, "创建订单", "path", "$.x", null)));
        assertEquals(40904, dup.getCode());
        assertTrue(dup.getMessage().contains("创建订单"), "重名须点名别名: " + dup.getMessage());
        stepAliasService.create(1L, new StepAliasInput(pid2, "创建订单", "step", "5.orderNo", null));
        assertEquals(1, stepAliasService.list(pid2).size());
    }

    @Test
    void test_改指向_stepNo漂移只改一行() {
        long pid = createStepAliasProject("V7E");
        StepAliasInfo alias = stepAliasService.create(1L,
                new StepAliasInput(pid, "创建订单", "step", "3.orderNo", null));
        StepAliasInfo updated = stepAliasService.update(1L, alias.id(), "step", "4.orderNo", null);
        assertEquals("4.orderNo", updated.target());
        assertEquals("创建订单", stepAliasService.list(pid).get(0).aliasName());
    }

    @Test
    void test_删除后引用面收敛_与不存在人话拒() {
        long pid = createStepAliasProject("V7F");
        StepAliasInfo alias = stepAliasService.create(1L,
                new StepAliasInput(pid, "订单号", "path", "$.data.orderNo", null));
        stepAliasService.delete(1L, alias.id());
        assertEquals(Set.of(), stepAliasService.aliasNames(pid));
        ServiceException gone = assertThrows(ServiceException.class, () ->
                stepAliasService.delete(1L, alias.id()));
        assertTrue(gone.getMessage().contains("不存在"), "重复删除须人话点名: " + gone.getMessage());
    }

    @Test
    void test_kind值域与空target_人话拒() {
        long pid = createStepAliasProject("V7G");
        ServiceException badKind = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid, "订单号", "header", "$.x", null)));
        assertTrue(badKind.getMessage().contains("两档"), "kind 值域须人话: " + badKind.getMessage());
        assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(pid, "订单号", "path", " ", null)));
        ServiceException noProject = assertThrows(ServiceException.class, () ->
                stepAliasService.create(1L, new StepAliasInput(999999L, "订单号", "path", "$.x", null)));
        assertTrue(noProject.getMessage().contains("项目不存在"));
    }
}
