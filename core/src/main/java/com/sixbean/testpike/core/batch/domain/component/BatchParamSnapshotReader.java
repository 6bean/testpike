package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.common.util.TlsContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batch parameter snapshot reader component: claim batch tasks read snapshots instead of performing reverse lookups on source tables.
 * <p>Returns {@link BatchParamView}, the four-channel pinned-version view, with three-state semantics.
 * If a snapshot exists, use the pinned value as an anchor for reproducible results; if no snapshot row exists because existing batches are running during an upgrade, fall back to real-time reverse lookup.
 * Zero drift from the old semantics is preserved, and warnings are emitted once per batch to prevent log flooding, using the same pattern as warning once for missing ToolConfirmTokenStore keys.
 * If snapshot data is dirty due to decryption or shape anomalies, apply the same fallback and warning, and never fail claim because the claim main chain must be fail-safe.
 * <p>Redis read-through details are in {@link BatchParamSnapshotStore}, and this component only handles view assembly and fallback classification.
 * <p>⚠️ Output contains plaintext credentials, is only for claim envelope dispatch, and must not enter logs or audit records, per the security pin.
 */
@Component
public class BatchParamSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(BatchParamSnapshotReader.class);

    /** The fallback warning deduplication set has an upper limit to prevent unbounded growth, and when exceeded it is cleared and re-recorded, with acceptable observability noise. */
    private static final int WARNED_CAP = 10_000;

    private final BatchParamSnapshotStore store;
    private final BatchParamResolver resolver;
    private final Set<Long> warnedBatches = java.util.Collections.synchronizedSet(
            new LinkedHashSet<>());

    public BatchParamSnapshotReader(BatchParamSnapshotStore store, BatchParamResolver resolver) {
        this.store = store;
        this.resolver = resolver;
    }

    /**
     * Batch task parameter view: if batchId is non-null, read the snapshot, with miss or dirty data falling back to real-time; trial-run tasks have batchId null.
     * No pinned-version semantics means direct real-time reverse lookup, with current behavior unchanged.
     */
    public BatchParamView readForClaim(Long batchId, long projectId, String envBaseUrl) {
        if (batchId == null) {
            return liveView(projectId, envBaseUrl);
        }
        String envelope = store.readEnvelope(batchId);
        if (envelope == null) {
            warnOnce(batchId, "存量批无参数快照，回落实时反查（旧语义）batchId=" + batchId);
            return liveView(projectId, envBaseUrl);
        }
        try {
            return fromSections(resolver.fromEnvelope(envelope));
        } catch (RuntimeException e) {
            warnOnce(batchId, "批次参数快照解析失败，回落实时反查 batchId=" + batchId
                    + ": " + e.getMessage());
            return liveView(projectId, envBaseUrl);
        }
    }

    /** The real-time reverse lookup view has the same source semantics as the five reverse lookup points in claimOne before refactoring and goes through BatchParamResolver as a single point. */
    public BatchParamView liveView(long projectId, String envBaseUrl) {
        return new BatchParamView(resolver.readEnvVars(projectId, envBaseUrl),
                resolver.readConnections(projectId, envBaseUrl),
                resolver.readDataVars(projectId, envBaseUrl),
                resolver.readTlsPolicy(projectId, envBaseUrl));
    }

    @SuppressWarnings("unchecked")
    private BatchParamView fromSections(Map<String, Object> sections) {
        Map<String, String> envVars = toStringMap(
                sections.get(BatchParamResolver.SECTION_ENV_VARS));
        List<Map<String, Object>> connections = sections.get(
                BatchParamResolver.SECTION_CONNECTIONS) instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        Map<String, String> dataVars = toStringMap(
                sections.get(BatchParamResolver.SECTION_DATA_VARS));
        TlsPolicy tls = sections.get(BatchParamResolver.SECTION_TLS) instanceof Map<?, ?> tlsMap
                ? TlsContextResolver.resolve(String.valueOf(tlsMap.get("mode")),
                        asText(tlsMap.get("caPem")), asText(tlsMap.get("clientCertPem")),
                        asText(tlsMap.get("clientKeyPem")))
                : TlsPolicy.plainStrict();
        return new BatchParamView(envVars, connections, dataVars, tls);
    }

    private static Map<String, String> toStringMap(Object section) {
        if (!(section instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void warnOnce(long batchId, String message) {
        boolean first;
        synchronized (warnedBatches) {
            if (warnedBatches.size() >= WARNED_CAP) {
                warnedBatches.clear();
            }
            first = warnedBatches.add(batchId);
        }
        if (first) {
            log.warn(message);
        }
    }
}
