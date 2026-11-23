package com.sixbean.testpike.admin.credential.infrastructure.remote.client;

import com.sixbean.testpike.admin.credential.api.dto.CredentialSaveRequest;
import com.sixbean.testpike.admin.credential.api.dto.CredentialView;

import java.util.List;


public interface CoreCredentialClient {

    
    long createCredential(long projectId, CredentialSaveRequest request);

    
    void updateCredential(long id, CredentialSaveRequest request);

    
    CredentialView findCredential(long id);

    
    List<CredentialView> listCredentials(long projectId, Boolean enabledOnly);

    
    void setCredentialEnabled(long id, boolean enabled);

    
    void deleteCredential(long id);

    /**
     * "Test now" for the vault: one real request against the target with
     * the {{credential}} placeholder filled server-side. Sync run, masked
     * trail under the returned token.
     */
    com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView testCredential(
            long id, com.sixbean.testpike.admin.credential.api.dto.CredentialTestRequest request);

    /** Event window after cursor for one credential test run. */
    java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> testEvents(
            long id, String runToken, long cursor);

    /** Run history of one credential's test trail (revisit entry list). */
    java.util.List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView> testRuns(long id, Integer limit);

    /** Gated reveal of one sealed header value of one test-trail row. */
    com.sixbean.testpike.admin.project.api.dto.TrailRevealView revealTestTrailField(
            long id, long eventId, String field);

    /** Paged full-text window of one test-trail row. */
    com.sixbean.testpike.admin.project.api.dto.TrailFullTextView testTrailFullText(
            long id, long eventId, int from, Integer limit);
}
