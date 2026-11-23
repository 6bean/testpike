package com.sixbean.testpike.core.cluster.api.dto;

/**
 * One artifact reachability pre-check row shown in the upgrade plan wizard
 * (step 2 preview). The server probes the download URL without ever fetching
 * the full package (HEAD + Range bytes=0-0), so a broken mirror is caught
 * before anyone commits to a rolling upgrade.
 *
 * @param componentFamily artifact family echo (e.g. core / executor-agent)
 * @param url             probed download URL (display only, never followed blindly)
 * @param declaredSize    size bytes declared in the manifest (0 = not declared)
 * @param observedSize    total size the origin reports (-1 = origin gave no length)
 * @param verdict         machine key: reachable / unreachable / size_mismatch / host_blocked
 * @param reason          plain-language sentence for the wizard row (human wording, no stack traces)
 */
public record ArtifactPrecheckItemView(
        String componentFamily,
        String url,
        long declaredSize,
        long observedSize,
        String verdict,
        String reason) {

    public static final String VERDICT_REACHABLE = "reachable";
    public static final String VERDICT_UNREACHABLE = "unreachable";
    public static final String VERDICT_SIZE_MISMATCH = "size_mismatch";
    public static final String VERDICT_HOST_BLOCKED = "host_blocked";
}
