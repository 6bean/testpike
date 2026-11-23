package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Serving-URL card supply : the two K8 values the workbench
 * assembles a reachable mock URL from — {publicBaseUrl}{trafficBasePath}/
 * {spaceKey}. Both read straight from tp_sys_config via core so a deployment
 * behind a reverse proxy only edits one key; the bridge never invents a host.
 *
 * @param publicBaseUrl externally reachable base of the traffic plane
 * ("" = key unset, the UI words the gap honestly)
 * @param trafficBasePath routing root prefix every space lives under
 */
public record MockServingUrlView(String publicBaseUrl, String trafficBasePath) {
}
