package com.sixbean.testpike.core.project.api.dto;


public record EnvHealthScanResult(int scanned, int healthy, int unhealthy, int alerted, int recovered) {
}
