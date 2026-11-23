package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Heartbeat component version extended-reporting ledger-entry component (version detection system, frozen document heartbeat extended-reporting data plane).
 * <p> Consumer position uses the same shape as the core heartbeat port monitorFrames: the API layer passes components[] through unchanged in a Map.
 * Missing keys or empty lists result in zero calls (old executor zero drift), and ledger-entry failures must not drag down the heartbeat main chain (the caller uses try-catch to leave a trace).
 * <p> Row-level semantics (honest ledger entry, no dirty values in the ledger): a row is a Map with non-blank family and non-blank currentVersion.
 * Only then is it persisted to the ledger, while non-Map rows, missing family, or blank versions are skipped row by row with a warn log for row-level isolation during 15-second heartbeat storms.
 * A single bad row does not swallow good rows in the same batch, and ledger entry uses the {@link ClusterComponentService#upsert} single-row idempotent channel.
 * The node×family unique key overwrites the current value, and passing null for requirement/target preserves the baseline sticky value while the judgment spectrum automatically re-evaluates.
 */
@Component
public class ClusterComponentIngestor {

    private static final Logger log = LoggerFactory.getLogger(ClusterComponentIngestor.class);

    private final ClusterComponentService componentService;

    public ClusterComponentIngestor(ClusterComponentService componentService) {
        this.componentService = componentService;
    }

    /**
     * Heartbeat extended-reporting ledger entry uses nodeId as the executor id unified addressing string and rows as the protocol components[] raw rows.
     * <p> Returns the number of successfully persisted ledger rows for observation and logging only, while row-level bad data is honestly skipped and the method itself does not throw for bad rows.
     */
    public int ingest(String nodeId, List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int landed = 0;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                log.warn("心跳部件扩报存在非对象行（诚实跳过该行）: {}", row);
                continue;
            }
            String family = text(map.get("family"));
            String version = text(map.get("currentVersion"));
            if (family == null || version == null) {
                log.warn("心跳部件扩报行缺 family 或 currentVersion（诚实跳过该行）: {}", map);
                continue;
            }
            // Source validation, unique-key idempotency, and judgment-spectrum re-evaluation are all handled by the existing service-layer chain, leaving zero duplicate logic in this component.
            // Row-level isolation rejects a single row at the service layer when it is overlong or contains dirty values, surfacing as DataAccessException or ServiceException.
            // The row is honestly skipped so that during 15-second heartbeat storms one bad row cannot swallow good rows in the same batch or cause the whole batch to be dropped.
            try {
                componentService.upsert(nodeId, family, version, "heartbeat", null, null);
                landed++;
            } catch (RuntimeException e) {
                log.warn("心跳部件扩报行入账失败（该行诚实跳过，同批其余照常）: family={} cause={}",
                        family, e.getMessage());
            }
        }
        return landed;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
