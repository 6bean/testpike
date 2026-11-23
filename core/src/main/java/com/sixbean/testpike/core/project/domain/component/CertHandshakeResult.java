package com.sixbean.testpike.core.project.domain.component;

import java.time.LocalDateTime;


public record CertHandshakeResult(String tlsVersion, String issuer, LocalDateTime notAfter) {
}
