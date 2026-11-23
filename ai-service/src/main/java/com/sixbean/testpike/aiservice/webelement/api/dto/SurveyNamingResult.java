package com.sixbean.testpike.aiservice.webelement.api.dto;

import java.util.Map;

/**
 * Naming answer (WE batch segment P3).
 * <p>{@code applied=false} means "no usable AI names this round" — switch off,
 * provider outage or unparsable output alike. The map then stays empty and the
 * caller keeps its rule-collected placeholder names; partial success carries
 * the honest subset keyed by anchor identity (only accepted keys appear).
 */
public record SurveyNamingResult(boolean applied, Map<String, String> names) {
}
