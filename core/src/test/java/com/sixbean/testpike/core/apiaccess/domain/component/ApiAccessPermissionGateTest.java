package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contracts of the door's third ownership face: the functional permission a page enforces
 * (same aggregation the admin interceptor runs per request — no second implementation here).
 */
class ApiAccessPermissionGateTest {

    private static final String PERM = "perm:system:dead-letter-read";

    private final RoleService roleService = mock(RoleService.class);
    private final ApiAccessPermissionGate gate = new ApiAccessPermissionGate(roleService);

    private static ApiAccessGovernanceView gated(String permission) {
        return new ApiAccessGovernanceView("GET /internal/x", true, true, null, null, null, null, permission);
    }

    @Test
    void rowsWithoutAPermissionGateNeverFireIt() {
        assertNull(gate.problem(null, 1L));
        assertNull(gate.problem(new ApiAccessGovernanceView("k", true, true, null, null), 1L));
        assertNull(gate.problem(gated("  "), 1L));
    }

    @Test
    void holdersPassAndOthersAreRefusedWithThePointAndWhereToGetIt() {
        when(roleService.permsOfUser(7L)).thenReturn(Set.of(PERM));
        assertNull(gate.problem(gated(PERM), 7L));
        when(roleService.permsOfUser(8L)).thenReturn(Set.of("perm:other"));
        String refusal = gate.problem(gated(PERM), 8L);
        assertNotNull(refusal);
        assertTrue(refusal.contains(PERM), refusal);
        assertTrue(refusal.contains("/roles"), "refusal must name where the permission is granted");
    }

    @Test
    void unknownOperatorFailsClosedOnGatedRows() {
        String refusal = gate.problem(gated(PERM), null);
        assertNotNull(refusal);
        assertTrue(refusal.contains(PERM), refusal);
        assertTrue(refusal.contains("没有可判定的操作人"), refusal);
    }

    @Test
    void resolverFaultsRefuseTooInsteadOfPassing() {
        when(roleService.permsOfUser(anyLong())).thenThrow(new IllegalStateException("db down"));
        String refusal = gate.problem(gated(PERM), 9L);
        assertNotNull(refusal);
        assertTrue(refusal.contains("暂不可用"), refusal);
        assertTrue(refusal.contains(PERM), refusal);
    }

    @Test
    void registeredCatalogKeysAreNamedWithTheirPlainLabel() {
        // Reuses the same catalog the page interceptor uses: dead-letter read has a Chinese label.
        String described = ApiAccessPermissionGate.describe(PERM);
        assertEquals("死信台账查看（" + PERM + "）", described);
        assertEquals("perm:not:in-catalog", ApiAccessPermissionGate.describe("perm:not:in-catalog"));
    }
}
