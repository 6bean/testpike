package com.sixbean.testpike.core.token.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.token.api.dto.ApiTokenView;

import java.sql.Timestamp;


public class ApiTokenRow {

    
    private static final int FINGERPRINT_LEN = 8;

    private Long id;
    private Long userId;
    private String name;
    private String tokenHash;
    private String scopes;
    private Timestamp expiresAt;
    private Integer revoked;
    private Timestamp lastUsedAt;
    private Integer ownerStatus;
    private Integer ownerDeleted;
    /** GA1 project pin (normalized ids, NULL = unrestricted). */
    private String projectScope;

 
    public ApiTokenView toView() {
        int revokedCode = revoked == null ? 0 : revoked;
        return new ApiTokenView(id, name, scopes,
                tokenHash == null || tokenHash.length() < FINGERPRINT_LEN
                        ? tokenHash : tokenHash.substring(0, FINGERPRINT_LEN),
                revokedCode,
                ViewFormats.formatTs(lastUsedAt),
                ViewFormats.formatTs(expiresAt),
                projectScope);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getRevoked() {
        return revoked;
    }

    public void setRevoked(Integer revoked) {
        this.revoked = revoked;
    }

    public Timestamp getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Timestamp lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Integer getOwnerStatus() {
        return ownerStatus;
    }

    public void setOwnerStatus(Integer ownerStatus) {
        this.ownerStatus = ownerStatus;
    }

    public Integer getOwnerDeleted() {
        return ownerDeleted;
    }

    public void setOwnerDeleted(Integer ownerDeleted) {
        this.ownerDeleted = ownerDeleted;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }
}
