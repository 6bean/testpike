package com.sixbean.testpike.api.executor.infrastructure.remote.client;

import java.util.List;
import java.util.Map;


public interface CoreExecutorClient {

    
    Map<String, Object> register(Map<String, Object> body);

 
    Map<String, Object> heartbeat(long executorId, Map<String, Object> body);

    
    Map<String, Object> claim(Map<String, Object> body);

    
    Map<String, Object> submitResult(Map<String, Object> body);

 
    Map<String, Object> grantArtifact(Map<String, Object> body);

    
    Map<String, Object> ingestSessionArtifacts(Map<String, Object> body);

    
    Map<String, Object> cancelTask(long taskId);

 
    Map<String, Object> occupyPoolItem(long itemId, long projectId, long lockedBy);

    
    Map<String, Object> releasePoolItem(long itemId, long projectId);

    
    Map<String, Object> renewDeviceLease(long deviceId, long executorId);

    
    Map<String, Object> releaseDeviceLease(long deviceId, long executorId, String reason);

    
    Map<String, Object> visualLocate(Map<String, Object> body);

    
    Map<String, Object> aiSampleCacheHit(Map<String, Object> body);

    
    Map<String, Object> aiSampleRecord(Map<String, Object> body);

    
    Map<String, Object> stepSuggestionRecord(Map<String, Object> body);

    /** Evidence-plane baseline grant passthrough (core /internal/web-elements/evidence-grant). */
    Map<String, Object> elementEvidenceGrant(Map<String, Object> body);

    
    Map<String, Object> grantPackageDownload(Map<String, Object> body);

    /**
 * Forward the perf compiled-artifact download grant to core ( A-10
 * : the api protocol door relays the executor's objectKey verbatim
 * with zero business semantics — scene-guard rejection and presigning both
 * live in core, returning {objectKey, downloadUrl}.
 */
    Map<String, Object> grantPerfArtifactDownload(Map<String, Object> body);

    
    List<Map<String, Object>> takeoverInputPoll(Map<String, Object> body);

    /**
 * protocol door relays the claim verbatim and carries zero business
 * semantics: the window check, the queue pop and the attempt counter all
 * live in core. Absent answer = nothing is waiting (not an error).
 */
    Map<String, Object> claimCaptureSession(long executorId);

    /**
 * Capture-round write-back door : candidate metadata rows only —
 * the value field of red line 3 has no slot in the shape, and the scope
 * whitelist filter is re-run in core regardless of what the executor sent.
 * Returns the number of rows stored (-1 = session already gone).
 */
    Long reportCaptureSession(String sessionId, Map<String, Object> body);

    /**
 * Commit-round value door : the executor answers the re-queued
 * commit signal with freshly read values for the checked subset. The body
 * transits memory only toward core's vault registration — never stored,
 * never logged on this hop (red lines 3/4). Returns rows registered
 * (-1 = no commit signal or the session is already gone).
 */
    Long commitCaptureValues(String sessionId, Map<String, Object> body);
}
