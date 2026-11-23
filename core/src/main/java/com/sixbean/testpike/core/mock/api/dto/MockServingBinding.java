package com.sixbean.testpike.core.mock.api.dto;

/**
 * Resolved mock serving binding for one dispatching consumer .
 * <p>Carries everything the envelope needs to redirect a run's traffic into a
 * published mock space: the routing handle, the externally reachable base URL
 * (K8 public base + traffic prefix + space key), and the current content pin
 * read through the snapshot service — pin source stays single (MockSpaceService
 * .snapshot), consumers never recompute it.
 *
 * @param spaceKey global routing handle of the space
 * @param baseUrl traffic-plane prefix this space answers under
 * @param snapshotVersion content pin at assembly time (executor stamps it onto
 * hit attribution; equality-compare only)
 */
public record MockServingBinding(String spaceKey, String baseUrl, String snapshotVersion) {
}
