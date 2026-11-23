package com.sixbean.testpike.core.llmconfig.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.llmconfig.infrastructure.remote.client.GoldenReplayRouteClient;
import com.sixbean.testpike.core.prompt.domain.component.PromptTemplateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 3 trigger face: when a project's effective model route changes,
 * every prompt domain whose golden pack is registered in
 * {@code ai.behavior.golden-pack.mapping} is replayed on the route axis
 * (old route vs new route, released template held constant) inside ai-service.
 *
 * <p>Posture = offline replay + ledger entry (adjudication ): the call
 * is dispatched after the binding save commits and on a dedicated daemon
 * thread, so no model token burns inside the save transaction; any dispatch
 * or leg failure is logged and swallowed — the human save is never blocked
 * and the promotion gate keeps reading the verdict from the drift ledger.
 * A single failing target does not stop the remaining fan-out.
 */
@Component
public class GoldenRebindReplayTrigger {

    private static final Logger log = LoggerFactory.getLogger(GoldenRebindReplayTrigger.class);

    /**
     * Golden-pack registry key. Cross-service counterpart = ai-service
     * GoldenReplayServiceImpl.KEY_PACK_MAPPING (modules cannot share the class;
     * the registry row itself is single-source in tp_sys_config).
     */
    static final String KEY_PACK_MAPPING = "ai.behavior.golden-pack.mapping";

    private final ConfigService configService;
    private final JdbcTemplate jdbcTemplate;
    private final GoldenReplayRouteClient routeClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService replayExecutor = Executors.newSingleThreadExecutor(run -> {
        Thread t = new Thread(run, "golden-rebind-replay");
        t.setDaemon(true);
        return t;
    });

    public GoldenRebindReplayTrigger(ConfigService configService, JdbcTemplate jdbcTemplate,
                                     GoldenReplayRouteClient routeClient, ObjectMapper objectMapper) {
        this.configService = configService;
        this.jdbcTemplate = jdbcTemplate;
        this.routeClient = routeClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch route-axis replays for an effective-route change. Silent when
     * either side has no route, when both sides name the same route, or when
     * no golden pack domain is registered — all of these have nothing to
     * measure and must not burn tokens.
     */
    public void onProjectRouteChanged(long projectId, String controlModel, String candidateModel) {
        if (controlModel == null || candidateModel == null || controlModel.equals(candidateModel)) {
            return;
        }
        List<RouteReplayTarget> targets;
        try {
            targets = resolveTargets();
        } catch (Exception resolveFailure) {
            log.warn("golden route replay targets unavailable for project {} ({}), "
                    + "skipping this dispatch", projectId, resolveFailure.getMessage());
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        replayExecutor.submit(() -> {
            for (RouteReplayTarget target : targets) {
                try {
                    routeClient.runRouteReplay(target.domain(), target.templateName(),
                            controlModel, candidateModel, projectId);
                } catch (Exception legFailure) {
                    // Fan-out continues; the ledger keeps whatever legs succeeded.
                    log.warn("golden route replay leg failed domain={} template={} project={} "
                                    + "control={} candidate={}: {}",
                            target.domain(), target.templateName(), projectId,
                            controlModel, candidateModel, legFailure.getMessage());
                }
            }
        });
    }

    /**
     * Registered domains (valid setName + version>0, read live per call — no
     * cache, hot-edit takes effect on the next switch) crossed with each
     * domain's released (FULL) template families.
     */
    private List<RouteReplayTarget> resolveTargets() throws Exception {
        String raw = configService.getValue(KEY_PACK_MAPPING, "{}");
        JsonNode root = objectMapper.readTree(raw);
        List<RouteReplayTarget> targets = new ArrayList<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String domain = entry.getKey();
            String setName = entry.getValue().path("setName").asText(null);
            int version = entry.getValue().path("version").asInt(0);
            if (domain == null || domain.isBlank() || setName == null || setName.isBlank()
                    || version <= 0) {
                continue;
            }
            List<String> families = jdbcTemplate.queryForList(
                    "SELECT DISTINCT name FROM tp_prompt_template WHERE domain = ? AND status = ?",
                    String.class, domain, PromptTemplateStatus.FULL);
            for (String family : families) {
                targets.add(new RouteReplayTarget(domain, family));
            }
        }
        return targets;
    }
}
