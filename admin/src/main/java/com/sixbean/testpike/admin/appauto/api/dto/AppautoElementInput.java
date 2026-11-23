package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Element library write payload bridged to the core element API (mirror of
 * the core record, same field names for straight mapping; create and full
 * update share this shape).
 *
 * @param name                display name, unique per project
 * @param locatorType         one of IMAGE / COORD / REGION / OCR
 * @param locatorConfig       archive locator parameters as a JSON object
 * @param variants            optional resolution variants (resolution + artifactKey)
 * @param confidenceThreshold template/OCR match floor in [0, 1]
 */
public record AppautoElementInput(
        String name,
        String locatorType,
        Map<String, Object> locatorConfig,
        List<Map<String, Object>> variants,
        Double confidenceThreshold) {
}
