package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Release manifest registration input parameters (unique row per version; re-registration is an idempotent overwrite).
 * JSON column takes string input (service layer validates shape before persisting to database; DB CHECK fallback jsonb_typeof).
 *
 * @param version testPike release version number (non-null).
 * @param componentMatrixJson component matrix JSON array string ([{component,support,pinned}], each item must include the component family name).
 * @param breakingChangesJson breaking changes JSON array string (human-readable itemized entries, each item non-blank).
 * @param executorVersionMin compatible executor protocol version lower bound (closed interval; null=no gate).
 * @param executorVersionMax compatible executor protocol version upper bound (closed interval; null=no gate).
 * @param suggestedPath suggested upgrade path (human-readable version-skipping restriction sentence; nullable).
 * @param source registration source manual/import (null=manual).
 * @param artifactsJson       download artifact array
 *                            [{component_family,url,sha256,size}]; null = no
 *                            artifacts (announce-only release, upgrade buttons
 *                            grey out downstream)
 * @param releaseNotesMd      release notes in markdown (human wording,
 *                            changelog drawer body source); nullable
 */
public record ReleaseRegisterInput(
        String version,
        String componentMatrixJson,
        String breakingChangesJson,
        String executorVersionMin,
        String executorVersionMax,
        String suggestedPath,
        String source,
        String artifactsJson,
        String releaseNotesMd) {
}
