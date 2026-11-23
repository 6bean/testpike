package com.sixbean.testpike.core.llmconfig.domain.component;

/**
 * One route-axis replay target discovered from the golden-pack registry:
 * the template family (name) inside a prompt domain whose golden pack is
 * registered and which carries a released row to replay.
 */
public record RouteReplayTarget(String domain, String templateName) {
}
