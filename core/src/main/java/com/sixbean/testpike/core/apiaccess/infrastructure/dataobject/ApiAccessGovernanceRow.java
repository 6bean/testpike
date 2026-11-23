package com.sixbean.testpike.core.apiaccess.infrastructure.dataobject;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;

/**
 * Governance row of the pass-through door (BeanPropertyRowMapper target; the DO stays inside
 * infrastructure and converts to the record at {@link #toInfo()}).
 */
public class ApiAccessGovernanceRow {

    private String endpointKey;
    private boolean scopeVerified;
    private boolean reachable;
    private String aiDesc;
    private String excludeReason;
    private String rowOwnerEntity;
    private String rowOwnerIdParam;
    private String requiredPermission;
    private String searchTerms;

    /** Convert to the domain view consumed by the policy. */
    public ApiAccessGovernanceView toInfo() {
        return new ApiAccessGovernanceView(endpointKey, scopeVerified, reachable, aiDesc, excludeReason,
                rowOwnerEntity, rowOwnerIdParam, requiredPermission, searchTerms);
    }

    public String getSearchTerms() {
        return searchTerms;
    }

    public void setSearchTerms(String searchTerms) {
        this.searchTerms = searchTerms;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public void setRequiredPermission(String requiredPermission) {
        this.requiredPermission = requiredPermission;
    }

    public String getEndpointKey() {
        return endpointKey;
    }

    public void setEndpointKey(String endpointKey) {
        this.endpointKey = endpointKey;
    }

    public boolean isScopeVerified() {
        return scopeVerified;
    }

    public void setScopeVerified(boolean scopeVerified) {
        this.scopeVerified = scopeVerified;
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public String getAiDesc() {
        return aiDesc;
    }

    public void setAiDesc(String aiDesc) {
        this.aiDesc = aiDesc;
    }

    public String getExcludeReason() {
        return excludeReason;
    }

    public void setExcludeReason(String excludeReason) {
        this.excludeReason = excludeReason;
    }

    public String getRowOwnerEntity() {
        return rowOwnerEntity;
    }

    public void setRowOwnerEntity(String rowOwnerEntity) {
        this.rowOwnerEntity = rowOwnerEntity;
    }

    public String getRowOwnerIdParam() {
        return rowOwnerIdParam;
    }

    public void setRowOwnerIdParam(String rowOwnerIdParam) {
        this.rowOwnerIdParam = rowOwnerIdParam;
    }
}
