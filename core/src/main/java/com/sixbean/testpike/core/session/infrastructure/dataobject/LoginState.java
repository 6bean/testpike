package com.sixbean.testpike.core.session.infrastructure.dataobject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Login-state ledger row (tp_login_state, AV2). One row per account login state
 * of one project; secret material lives in tp_credential (single vault) and is
 * referenced by credentialId — this object never carries plaintext or cipher.
 * scopeOriginsJson is the jsonb origin whitelist read as text; use
 * {@link #originsAsList()} so every consumer parses through one place.
 */
public class LoginState {

    /** Shared column list — the single SELECT shape for ledger reads. */
    public static final String SELECT_COLUMNS = """
            l.id, l.project_id, l.state_name, l.role_scope, l.account_label, l.kind,
            l.credential_id, l.state_object_key, l.scope_origins::text AS scope_origins_json,
            l.extra_auth_refs::text AS extra_auth_refs_json,
            l.source_kind, l.expires_at, l.last_verified_at, l.status, l.remark,
            l.created_at, l.updated_at,
            l.keepalive_enabled, l.keepalive_url, l.keepalive_interval_minutes,
            l.last_keepalive_at, l.last_keepalive_verdict,
            l.keepalive_fail_streak, l.keepalive_stop_reason
            """;

    public static final RowMapper<LoginState> ROW_MAPPER = new BeanPropertyRowMapper<>(LoginState.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private Long id;
    private Long projectId;
    private String stateName;
    private String roleScope;
    private String accountLabel;
    private String kind;
    private Long credentialId;
    private String stateObjectKey;
    private String scopeOriginsJson;
    private String extraAuthRefsJson;
    private Integer sourceKind;
    private LocalDateTime expiresAt;
    private LocalDateTime lastVerifiedAt;
    private Integer status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Keep-alive display columns: switch state, probe target, per-row cadence
    // override and the scheduler's last outcome. Read-only
    // here — every write goes through the runner/gate SQL so no second
    // write path can drift the pacing anchor.
    private Integer keepaliveEnabled;
    private String keepaliveUrl;
    private Integer keepaliveIntervalMinutes;
    private LocalDateTime lastKeepaliveAt;
    private String lastKeepaliveVerdict;
    private Integer keepaliveFailStreak;
    private String keepaliveStopReason;

    /** Parsed origin whitelist; malformed or absent json reads as empty (fail-closed). */
    public List<String> originsAsList() {
        if (scopeOriginsJson == null || scopeOriginsJson.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(scopeOriginsJson, new TypeReference<List<String>>() { });
        } catch (Exception parseFailed) {
            return List.of();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public String getRoleScope() {
        return roleScope;
    }

    public void setRoleScope(String roleScope) {
        this.roleScope = roleScope;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public void setAccountLabel(String accountLabel) {
        this.accountLabel = accountLabel;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(Long credentialId) {
        this.credentialId = credentialId;
    }

    public String getStateObjectKey() {
        return stateObjectKey;
    }

    public void setStateObjectKey(String stateObjectKey) {
        this.stateObjectKey = stateObjectKey;
    }

    public String getScopeOriginsJson() {
        return scopeOriginsJson;
    }

    public void setScopeOriginsJson(String scopeOriginsJson) {
        this.scopeOriginsJson = scopeOriginsJson;
    }

    public String getExtraAuthRefsJson() {
        return extraAuthRefsJson;
    }

    public void setExtraAuthRefsJson(String extraAuthRefsJson) {
        this.extraAuthRefsJson = extraAuthRefsJson;
    }

    public Integer getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(Integer sourceKind) {
        this.sourceKind = sourceKind;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(LocalDateTime lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getKeepaliveEnabled() {
        return keepaliveEnabled;
    }

    public void setKeepaliveEnabled(Integer keepaliveEnabled) {
        this.keepaliveEnabled = keepaliveEnabled;
    }

    public String getKeepaliveUrl() {
        return keepaliveUrl;
    }

    public void setKeepaliveUrl(String keepaliveUrl) {
        this.keepaliveUrl = keepaliveUrl;
    }

    public Integer getKeepaliveIntervalMinutes() {
        return keepaliveIntervalMinutes;
    }

    public void setKeepaliveIntervalMinutes(Integer keepaliveIntervalMinutes) {
        this.keepaliveIntervalMinutes = keepaliveIntervalMinutes;
    }

    public LocalDateTime getLastKeepaliveAt() {
        return lastKeepaliveAt;
    }

    public void setLastKeepaliveAt(LocalDateTime lastKeepaliveAt) {
        this.lastKeepaliveAt = lastKeepaliveAt;
    }

    public String getLastKeepaliveVerdict() {
        return lastKeepaliveVerdict;
    }

    public void setLastKeepaliveVerdict(String lastKeepaliveVerdict) {
        this.lastKeepaliveVerdict = lastKeepaliveVerdict;
    }

    public Integer getKeepaliveFailStreak() {
        return keepaliveFailStreak;
    }

    public void setKeepaliveFailStreak(Integer keepaliveFailStreak) {
        this.keepaliveFailStreak = keepaliveFailStreak;
    }

    public String getKeepaliveStopReason() {
        return keepaliveStopReason;
    }

    public void setKeepaliveStopReason(String keepaliveStopReason) {
        this.keepaliveStopReason = keepaliveStopReason;
    }
}
