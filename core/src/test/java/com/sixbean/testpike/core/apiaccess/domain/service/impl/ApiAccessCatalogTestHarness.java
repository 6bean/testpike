package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessGovernanceRepository;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessMcpToolProjector;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessPermissionGate;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessPolicy;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessQuotaLedger;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessRequestFactory;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessRowOwnership;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiEndpointCatalog;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Shared stand-in wiring for the api-access read-door suites (R40 single source).
 *
 * <p>Why this file exists: the catalog browse/search/guidance/relevance suites and the project
 * segment door suite all needed the same nine peripheral collaborator stand-ins plus the same door
 * assembly; every suite repeated the block verbatim, so a constructor signature change had six
 * copies to chase and the duplication gate counted the drift. Only the stand-ins live here —
 * policy, request factory, config reader and the door itself stay the production classes, and
 * each suite keeps stubbing behaviour through the fields instead of a second implementation.
 */
final class ApiAccessCatalogTestHarness {

    /** Loopback target for the pure-logic suites; nothing in those tests ever dials it. */
    static final String LOOPBACK_BASE = "http://127.0.0.1:8083";

    final ApiEndpointCatalog catalog = mock(ApiEndpointCatalog.class);
    final ApiAccessGovernanceRepository governance = mock(ApiAccessGovernanceRepository.class);
    final ApiAccessQuotaLedger quota = mock(ApiAccessQuotaLedger.class);
    final ApiAccessRowOwnership rowOwnership = mock(ApiAccessRowOwnership.class);
    final ProjectRoleResolver roleResolver = mock(ProjectRoleResolver.class);
    final AdminAlertRecipients admins = mock(AdminAlertRecipients.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final ApiAccessMcpToolProjector projector = mock(ApiAccessMcpToolProjector.class);
    final ApiAccessPermissionGate permissionGate = mock(ApiAccessPermissionGate.class);

    /** Registry spec stand-in: a read entry unless the verb is non-GET. */
    static ApiEndpointSpec spec(String method, String path) {
        return new ApiEndpointSpec(method + " " + path, method, path, List.of(),
                !"GET".equals(method), null, null, List.of());
    }

    ConfigCacheHolder configCache;
    ApiAccessServiceImpl door;

    /** Read-only door over the mocked permission gate — the shape the catalogue suites pin. */
    void wireDoor() {
        wireDoor(LOOPBACK_BASE, false, permissionGate);
    }

    /**
     * Assembles the production door over the stand-in fields above.
     *
     * @param baseUrl dial target handed to the request factory
     * @param writeEnabled also raises the write switch key for suites exercising write paths
     * @param gate permission gate to wire in (the mock field or a role-backed instance)
     */
    void wireDoor(String baseUrl, boolean writeEnabled, ApiAccessPermissionGate gate) {
        configCache = new ConfigCacheHolder();
        configCache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        if (writeEnabled) {
            configCache.put(ApiAccessPolicy.KEY_WRITE_ENABLED, "1");
        }
        SystemConfigReader reader = new SystemConfigReader(configCache);
        ObjectMapper mapper = new ObjectMapper();
        door = new ApiAccessServiceImpl(catalog, new ApiAccessPolicy(reader), governance,
                new ApiAccessRequestFactory(mapper), quota, roleResolver, admins, rowOwnership,
                gate, audit, reader, projector, mapper, baseUrl);
    }
}
