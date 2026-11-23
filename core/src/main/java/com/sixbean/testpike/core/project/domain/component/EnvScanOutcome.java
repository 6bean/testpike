package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.project.api.dto.EnvProbeOutcome;


public record EnvScanOutcome(EnvProbeOutcome probe, EnvScanState state) {
}
