package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;

/**
 * Evaluation-set sample row as shown by the admin facade (AW1; mirrors the
 * core SampleView shape field-for-field — the bridge forwards without any
 * second judgement, so display fields come exactly as core decided them).
 *
 * @param id             sample id
 * @param setName        owning evaluation set name
 * @param version        set version this row belongs to
 * @param snapshotDigest frozen digest of the version (anchor for reruns)
 * @param input          sample question / input text
 * @param expected       expected answer / reference output
 * @param referenceDocs  attached reference document names (may be empty)
 * @param source         origin badge: manual / batch-export / adopted / ai-generated / training-raise
 * @param sensitive      1 = content must not leave the tenant (dispatch gate reads it)
 * @param createdBy      creator user id
 * @param createdAt      creation time ('yyyy-MM-dd HH:mm:ss')
 */
public record EvalSampleView(Long id, String setName, Integer version, String snapshotDigest,
                             String input, String expected, List<String> referenceDocs,
                             String source, Integer sensitive, Long createdBy, String createdAt) {
}
