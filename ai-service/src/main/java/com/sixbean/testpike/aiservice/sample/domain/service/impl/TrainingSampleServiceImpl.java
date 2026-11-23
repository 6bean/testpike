package com.sixbean.testpike.aiservice.sample.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import com.sixbean.testpike.common.util.SnapshotJsonSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <ul>
 * </ul>
 *
 */
@Service
public class TrainingSampleServiceImpl implements TrainingSampleService {

    private static final Logger log = LoggerFactory.getLogger(TrainingSampleServiceImpl.class);

    
    private static final String LOG_ACTION_REJECT = "拒绝";
    private static final String LOG_ACTION_NEED_CHANGE = "需修改";

    
    static final String TYPE_REJECT = "驳回";
    static final String TYPE_DIFF = "修改差异";

    
    private static final int CHANGE_REVIEW = 2;

    private final CoreGateway coreGateway;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TrainingSampleServiceImpl(CoreGateway coreGateway, NamedParameterJdbcTemplate jdbc,
                                     ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void captureSample(CaseIngestEventMessage message) {
        long caseId = message.caseId();
        int version = message.caseVersion() == null ? 0 : message.caseVersion();
        Map<String, Object> payload = switch (message.action()) {
            case "REJECT" -> rejectPayload(message, caseId);
            case "PASS" -> diffPayload(caseId, version);
            default -> null;
        };
        if (payload == null) {
            log.info("D2 样本沉淀零动作 caseId={} action={}（不满足沉淀条件）", caseId, message.action());
            return;
        }
        String type = message.action().equals("REJECT") ? TYPE_REJECT : TYPE_DIFF;
        save(caseId, type, version, payload);
    }

    
    private Map<String, Object> rejectPayload(CaseIngestEventMessage message, long caseId) {
        List<Map<String, Object>> logs = coreGateway.getCaseReviewLogs(caseId);
        Map<String, Object> review = new LinkedHashMap<>();
        logs.stream()
                .filter(row -> LOG_ACTION_REJECT.equals(String.valueOf(row.get("action"))))
                .findFirst()
                .ifPresentOrElse(row -> {
                    review.put("remark", row.get("remark"));
                    review.put("feedbackTypes", row.getOrDefault("feedbackTypes", List.of()));
                }, () -> {
                    review.put("remark", message.remark());
                    review.put("feedbackTypes", List.of());
                });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("review", review);
        payload.put("case", coreGateway.getCase(caseId));
        return payload;
    }

    
    private Map<String, Object> diffPayload(long caseId, int headVersion) {
        List<Map<String, Object>> logs = coreGateway.getCaseReviewLogs(caseId);
        boolean hasNeedChange = logs.stream()
                .anyMatch(row -> LOG_ACTION_NEED_CHANGE.equals(String.valueOf(row.get("action"))));
        if (!hasNeedChange) {
            return null;
        }
        Integer base = baseVersionBelowHead(coreGateway.listCaseVersions(caseId), headVersion);
        if (base == null) {
            log.info("D2 修改差异基准版本缺失 caseId={} head={}（版本史无更旧评审态变更行）", caseId, headVersion);
            return null;
        }
        return coreGateway.getCaseVersionDiff(caseId, headVersion, base);
    }

    
    private static Integer baseVersionBelowHead(List<Map<String, Object>> versions, int headVersion) {
        Integer candidate = null;
        for (Map<String, Object> row : versions) {
            Object v = row.get("version");
            Object ct = row.get("changeType");
            if (!(v instanceof Number nv) || !(ct instanceof Number nc)) {
                continue;
            }
            int version = nv.intValue();
            if (version >= headVersion || nc.intValue() != CHANGE_REVIEW) {
                continue;
            }
            if (candidate == null || version > candidate) {
                candidate = version;
            }
        }
        return candidate;
    }

    
    private void save(long caseId, String sampleType, int sourceVersion, Map<String, Object> payload) {
        Object sanitized = SnapshotJsonSanitizer.sanitizeDeep(payload, objectMapper);
        String json;
        try {
            json = objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            throw new IllegalStateException("训练样本序列化失败 caseId=" + caseId, e);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("sampleType", sampleType)
                .addValue("sampleJson", json)
                .addValue("sourceVersion", sourceVersion);
        jdbc.update("INSERT INTO tp_training_sample (case_id, sample_type, sample_json, source_version) "
                + "VALUES (:caseId, :sampleType, :sampleJson::jsonb, :sourceVersion)", params);
        log.info("D2 样本沉淀入库 caseId={} type={} version={}", caseId, sampleType, sourceVersion);
    }
}
