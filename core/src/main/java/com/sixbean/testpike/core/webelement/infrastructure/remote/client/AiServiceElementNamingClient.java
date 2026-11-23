package com.sixbean.testpike.core.webelement.infrastructure.remote.client;

import java.util.List;
import java.util.Map;

/**
 * ai-service survey-naming door (WE batch segment P3, core side).
 * Interface here / Rest impl beside it per the framework layering rules; the
 * real core → ai-service hop is verified on the L3 live face (segment P5).
 */
public interface AiServiceElementNamingClient {

    /**
 * Ask for business-semantic names of one surveyed page's candidates.
 * @param sessionId explore session owning the survey process stream
 * (R62 segment b2); the ai-service side lands one naming-batch frame
 * keyed by it, null skips only the frame
 * @param descriptors deduped candidate rows, each {key,text,role,nearby}
 * @return names keyed by descriptor key; empty map when nothing was named
 * (switch off, provider trouble, or an unusable answer)
 * @throws RuntimeException transport failure — the caller degrades honestly
 */
    Map<String, String> nameCandidates(long projectId, String pageUrl, Long sessionId,
                                       List<Map<String, String>> descriptors);
}
