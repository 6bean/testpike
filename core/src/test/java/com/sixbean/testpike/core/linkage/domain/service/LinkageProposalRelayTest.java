package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalRecord;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalWrite;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.support.ObjectChangedEventQueueProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Script repair-chain trigger for an armed content edge ( segment B
 * segment 3): the detection pass reports the armed draft rows, the consumer
 * relays one proposal request per draft onto the shared event bus (real
 * RabbitMQ round trip, no mocks), and the proposal snapshot service stores
 * what the reused AI repair generator posts back.
 * <p>Red lines asserted here: a replayed announcement must not request a
 * second proposal (the ledger is the idempotency anchor), a rejected proposal
 * is stored honestly with its reason instead of vanishing (an absent card the
 * user never hears about is a silent failure), and the snapshot never mutates
 * the script row — applying is the one-click accept's job, not detection's.
 * <p>Fixtures are owned rows only (run-suffixed names, deleted by id), and
 * the walk uses the seeded production edge ledger (case-&gt;script).
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageProposalRelayTest {

    /** Routing key of the proposal-request announcement (single-sourced constant). */
    private static final String REQUEST_KEY = "linkage.proposal-requested";

    @Autowired
    private ChangeLinkageDispatchService dispatchService;

    @Autowired
    private com.sixbean.testpike.core.linkage.infrastructure.mq.ChangeLinkageConsumer linkageConsumer;

    @Autowired
    private LinkageProposalService proposalService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    private long projectId;
    private long caseId;
    private long scriptId;

    @BeforeEach
    void seedCaseWithScript() {
        LinkageCaseChain seeded = LinkageCaseFixture.seedProjectCaseWithScript(
                jdbc, caseService, "lnk-prop-" + System.nanoTime(),
                "lnkp" + System.nanoTime(), 1L, "联动提案用例");
        projectId = seeded.projectId();
        caseId = seeded.caseId();
        scriptId = seeded.scriptId();
    }

    @AfterEach
    void removeOwnedRows() {
        LinkageCaseFixture.purgeUpstreamChain(jdbc, "case", caseId);
        LinkageCaseFixture.purgeCaseChain(jdbc,
                new LinkageCaseChain(projectId, caseId, scriptId));
    }

    private ObjectChangedEventQueueProbe busProbe() {
        return new ObjectChangedEventQueueProbe(amqpAdmin, rabbitTemplate,
                caseEventMessageConverter);
    }

    private ObjectChangedEventMessage caseEvent(List<String> changedFields, int version) {
        return new ObjectChangedEventMessage(ObjectChangedEventType.CASE.name(), caseId,
                projectId, 1L, version, changedFields, System.currentTimeMillis());
    }

    private long armScriptDraft() {
        // arm through the consumer entry — the proposal-request relay lives there
        linkageConsumer.onObjectChanged(
                ObjectChangedEventQueueProbe.rawFrame(caseEvent(List.of("title"), 2)));
        long impactId = readScriptImpactId();
        assertTrue(impactId > 0, "the content edit must arm the script draft edge");
        return impactId;
    }

    private long readScriptImpactId() {
        return jdbc.queryForObject("SELECT id FROM tp_linkage_impact"
                + " WHERE edge_key = 'case->script' AND downstream_id = ?",
                Long.class, scriptId);
    }

    @Test
    void armed_script_draft_relays_one_proposal_request_on_the_real_bus() {
        Queue queue = busProbe().bindRoutingKey(REQUEST_KEY);
        long impactId = armScriptDraft();

        // one receive call only: the probe queue is exclusive, a repeated
        // poll would tear down its declaring connection and delete the queue
        LinkageProposalRequestMessage request =
                (LinkageProposalRequestMessage) busProbe().awaitRaw(queue, 20_000);
        assertNotNull(request, "the armed draft must announce a proposal request");
        assertEquals(impactId, request.impactId());
        assertEquals("case->script", request.edgeKey());
        assertEquals("script", request.downstreamObjectType());
        assertEquals(scriptId, request.downstreamObjectId());
        assertEquals(projectId, request.projectId());
        assertEquals(caseId, request.upstreamObjectId());
        assertEquals(2, request.upstreamVersion());
    }

    @Test
    void proposal_snapshot_is_keyed_by_impact_and_replay_writes_nothing() {
        long impactId = armScriptDraft();
        LinkageProposalInput first = new LinkageProposalInput(impactId, "script", scriptId,
                "{\"steps\":[{\"action\":\"click\"}]}", "v2", "test-model", 4, null);

        LinkageProposalWrite written = proposalService.record(first);

        assertNotNull(written.id());
        assertTrue(written.created(), "the first write creates the snapshot");
        assertEquals(1, written.status().intValue(), "a proposal with content waits pending");

        LinkageProposalWrite replay = proposalService.record(first);
        assertEquals(written.id(), replay.id());
        assertTrue(!replay.created(), "a replayed snapshot changes nothing");
        assertEquals(4, proposalService.get(impactId).baseScriptVersion());
        // the script row itself was never touched by posting a proposal
        assertEquals(1, jdbc.queryForObject("SELECT status FROM tp_script WHERE id = ?",
                Integer.class, scriptId).intValue());
    }

    @Test
    void rejected_proposal_lands_honestly_with_its_reason() {
        long impactId = armScriptDraft();
        LinkageProposalWrite rejected = proposalService.record(new LinkageProposalInput(
                impactId, "script", scriptId, null, null, null, 4, "提案服务暂时不可用"));

        assertEquals(3, rejected.status().intValue(),
                "an unusable proposal is stored as rejected, not vanished");
        assertEquals("提案服务暂时不可用", proposalService.get(impactId).rejectReason());
        assertNull(proposalService.get(impactId).proposalJson());
    }
}
