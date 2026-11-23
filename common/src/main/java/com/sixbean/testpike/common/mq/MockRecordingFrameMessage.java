package com.sixbean.testpike.common.mq;

import java.util.Map;

/**
 * One recorded proxy frame on the wire (exchange {@code testpike.events},
 * routing key {@code mock.recording.frame}; AG batch . The
 * mock-server builds these frames while a cassette box is open: every
 * forwarded upstream request/response pair is captured, masked through the
 * same desensitizer as the hit ledger, and shipped in
 * {@link MockRecordingFrameBatchMessage} batches. Core stages the frames and
 * maintains the live endpoint cluster in tp_mock_recording.summary_json —
 * the recording table row remains the single control-plane truth.
 *
 * @param frameId plane-assigned unique id (sample-ring identity + replay evidence)
 * @param method HTTP verb forwarded
 * @param path request path below the proxy prefix (raw, pre-normalization)
 * @param requestHeaders lowercase header names, sensitive values already masked
 * @param requestBody request body text masked through the shared pipeline (null = absent)
 * @param responseStatus upstream status answered through the proxy (null = transport failure)
 * @param responseHeaders upstream response headers, masked by the same gate
 * @param responseBody upstream response body masked through the shared pipeline
 * @param latencyMs forward-and-answer wall time
 * @param occurredAt epoch millis when the frame completed
 * @param spaceKey routing handle of the space this frame was recorded under
 * @param recordingId the open cassette box id (snapshot's activeRecordingId)
 */
public record MockRecordingFrameMessage(String frameId, String method, String path,
                                        Map<String, String> requestHeaders, String requestBody,
                                        Integer responseStatus, Map<String, String> responseHeaders,
                                        String responseBody,
                                        Integer latencyMs, long occurredAt,
                                        String spaceKey, long recordingId) {
}
