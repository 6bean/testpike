package com.sixbean.testpike.core.project.domain.component;

import java.time.LocalDateTime;


public record CertProbeOutcome(boolean collected, String tlsVersion, String issuer,
                               LocalDateTime notAfter, boolean chainTrusted) {

    
    public static CertProbeOutcome absent() {
        return new CertProbeOutcome(false, null, null, null, false);
    }
}
