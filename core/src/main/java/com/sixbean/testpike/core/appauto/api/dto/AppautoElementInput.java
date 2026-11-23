package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Element library write payload for create and full update of one image
 * element profile row (tp_appauto_element).
 *
 * @param name                display name, unique per project (max 200 chars)
 * @param locatorType         one of IMAGE / COORD / REGION / OCR
 * @param locatorConfig       archive locator parameters as a JSON object;
 *                            runtime locator resolution stays on the executor
 *                            side (single source, never mirrored here)
 * @param variants            optional resolution variants, each item must
 *                            carry resolution + artifactKey
 * @param confidenceThreshold template/OCR match floor in [0, 1]
 */
public record AppautoElementInput(
        String name,
        String locatorType,
        Map<String, Object> locatorConfig,
        List<Map<String, Object>> variants,
        Double confidenceThreshold) {
}
