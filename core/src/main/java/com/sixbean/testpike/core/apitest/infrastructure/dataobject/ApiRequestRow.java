package com.sixbean.testpike.core.apitest.infrastructure.dataobject;


public class ApiRequestRow {

    private long id;
    private long collectionId;
    private String name;
    private String method;
    private String urlPath;
    private String headersJson;
    private String bodyText;
    private String authJson;
    private String assertionsJson;
    private Long promotedCaseId;
    private boolean contractStale;
    private boolean mockStale;
    private String preconditionJson;
    private String loopJson;
    private String protocol;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getCollectionId() {
        return collectionId;
    }

    public void setCollectionId(long collectionId) {
        this.collectionId = collectionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUrlPath() {
        return urlPath;
    }

    public void setUrlPath(String urlPath) {
        this.urlPath = urlPath;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public void setHeadersJson(String headersJson) {
        this.headersJson = headersJson;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getAuthJson() {
        return authJson;
    }

    public void setAuthJson(String authJson) {
        this.authJson = authJson;
    }

    public String getAssertionsJson() {
        return assertionsJson;
    }

    public void setAssertionsJson(String assertionsJson) {
        this.assertionsJson = assertionsJson;
    }

    public Long getPromotedCaseId() {
        return promotedCaseId;
    }

    public void setPromotedCaseId(Long promotedCaseId) {
        this.promotedCaseId = promotedCaseId;
    }

    public boolean isContractStale() {
        return contractStale;
    }

    public void setContractStale(boolean contractStale) {
        this.contractStale = contractStale;
    }

    public boolean isMockStale() {
        return mockStale;
    }

    public void setMockStale(boolean mockStale) {
        this.mockStale = mockStale;
    }

    public String getPreconditionJson() {
        return preconditionJson;
    }

    public void setPreconditionJson(String preconditionJson) {
        this.preconditionJson = preconditionJson;
    }

    public String getLoopJson() {
        return loopJson;
    }

    public void setLoopJson(String loopJson) {
        this.loopJson = loopJson;
    }
}
