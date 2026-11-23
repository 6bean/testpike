package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Flow-template save input .
 * <p>The owning project is derived server-side from the source collection —
 * the wire never carries a projectId an attacker could mislabel.
 *
 * @param collectionId source collection whose current members get frozen
 * @param name template name (non-blank, max 200)
 * @param description human note shown on the template card (nullable)
 */
public record FlowTemplateSaveInput(Long collectionId, String name, String description) {
}
