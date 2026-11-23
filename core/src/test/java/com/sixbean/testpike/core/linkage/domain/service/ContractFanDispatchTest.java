package com.sixbean.testpike.core.linkage.domain.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.impl.ChangeLinkageDispatchServiceImpl;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level pin for the bean-detector (kind 1) edges of the linkage bus:
 * an api_spec save must fan through ContractRequestFanDetector and
 * ContractMockFanDetector into real tp_linkage_impact rows. The ledger-shape
 * test proves the rows resolve, but only this dispatch proves the runtime
 * path arms impacts without falling into the "unsupported detector kind"
 * skip — the vacuity signature that surfaced as x15 WARNs while a stale jar
 * ran before the kind-1 branch landed. A future regression that drops or
 * reroutes the branch fails here on live data instead of rotting in the log.
 */
@SpringBootTest(classes = CoreApplication.class)
class ContractFanDispatchTest {

    private static final String EDGE_REQUEST = "api_spec->apitest_request";
    private static final String EDGE_STUB = "api_spec->mock_stub";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChangeLinkageDispatchService dispatchService;

    @Autowired
    private ContractMatchKeyRules matchKeyRules;

    private long projectId;
    private long specId;
    private long spaceId;
    private long stubId;
    private long collectionId;
    private long requestId;
    private String path;
    private ListAppender<ILoggingEvent> skipAppender;

    @BeforeEach
    void seedContractFanFixture() {
        String token = Long.toHexString(System.nanoTime());
        projectId = LinkageCaseFixture.insertProject(jdbc,
                "契约扇出谱" + token, ("lnkf" + token).substring(0, 16));
        // A static path keeps every fold form identical: the spec endpointKey,
        // the imported request's stored key and the derived stub's match key
        // all pass through the same ContractMatchKeyRules single source, so
        // the fixture intersects by construction and the assertion below
        // doubles as a guard that the two fold entries never drift apart.
        path = "/lnkf-" + token;
        String folded = matchKeyRules.matchKey("GET", path);
        assertEquals(matchKeyRules.matchKeyFromLoose("GET " + path), folded,
                "loose-key and method+path folds must stay one key form");
        spaceId = jdbc.queryForObject(
                "INSERT INTO tp_mock_space (project_id, name, space_key)"
                        + " VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, "扇出空间" + token, "lnkfs" + token);
        stubId = jdbc.queryForObject(
                "INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source)"
                        + " VALUES (?, ?, 'GET', ?, 'openapi') RETURNING id",
                Long.class, spaceId, "扇出派生桩" + token, path);
        collectionId = jdbc.queryForObject(
                "INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags,"
                        + " headers_json) VALUES (?, 0, ?, '', '[]'::jsonb) RETURNING id",
                Long.class, projectId, "扇出接口集" + token);
        requestId = jdbc.queryForObject(
                "INSERT INTO tp_apitest_request (collection_id, name, method, url_path,"
                        + " contract_endpoint_key) VALUES (?, ?, 'GET', ?, ?) RETURNING id",
                Long.class, collectionId, "扇出引入请求" + token, path, folded);
        String hex = UUID.randomUUID().toString().replace("-", "");
        specId = jdbc.queryForObject(
                "INSERT INTO tp_api_spec (project_id, name, spec_format, spec_text,"
                        + " content_sha256, spec_version, endpoints) VALUES (?, ?,"
                        + " 'openapi3-json', '{}', ?, 1, ?::jsonb) RETURNING id",
                Long.class, projectId, "扇出契约" + token, hex + hex,
                "[{\"method\":\"GET\",\"path\":\"" + path
                        + "\",\"endpointKey\":\"GET " + path + "\"}]");
        Logger dispatcherLog = (Logger) LoggerFactory
                .getLogger(ChangeLinkageDispatchServiceImpl.class);
        skipAppender = new ListAppender<>();
        skipAppender.start();
        dispatcherLog.addAppender(skipAppender);
    }

    @AfterEach
    void releaseAppenderAndPurgeFixture() {
        ((Logger) LoggerFactory.getLogger(ChangeLinkageDispatchServiceImpl.class))
                .detachAppender(skipAppender);
        LinkageCaseFixture.purgeUpstreamChain(jdbc, "api_spec", specId);
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_apitest_request WHERE id = ?", requestId);
        jdbc.update("DELETE FROM tp_apitest_collection WHERE id = ?", collectionId);
        jdbc.update("DELETE FROM tp_mock_stub WHERE id = ?", stubId);
        jdbc.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM tp_api_spec WHERE id = ?", specId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    @Test
    void contractSaveFansThroughBeanDetectorsWithoutAnySkip() {
        LinkageImpactSummary summary = dispatchService.detectDownstream(
                new ObjectChangedEventMessage(ObjectChangedEventType.API_SPEC.name(),
                        specId, projectId, null, 1, List.of(), System.currentTimeMillis()));

        assertTrue(summary.edgesChecked() >= 2,
                "both api_spec bean edges must be evaluated, saw " + summary.edgesChecked());
        List<String> skips = skipAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("skips edge api_spec->"))
                .toList();
        assertEquals(List.of(), skips,
                "kind-1 bean detectors must arm, never fall into the skip branch: " + skips);
        assertTrue(summary.downstreamFound() >= 2,
                "one matching request plus one derived stub must be found");
        assertTrue(summary.impactsCreated() >= 2,
                "both fan members must land fresh impact rows");

        assertImpactShape(EDGE_REQUEST, "apitest_request", requestId);
        assertImpactShape(EDGE_STUB, "mock_stub", stubId);
    }

    /** The grouped_draft shape (strategy + status 2) the design adjudication pins. */
    private void assertImpactShape(String edgeKey, String downstreamType, long downstreamId) {
        List<String> rows = jdbc.queryForList(
                "SELECT disposition_strategy || '/' || status FROM tp_linkage_impact"
                        + " WHERE edge_key = ? AND downstream_object_type = ?"
                        + " AND downstream_id = ?",
                String.class, edgeKey, downstreamType, downstreamId);
        assertEquals(List.of("grouped_draft/2"), rows,
                edgeKey + " must arm exactly one grouped-draft impact for its fan member");
    }
}
