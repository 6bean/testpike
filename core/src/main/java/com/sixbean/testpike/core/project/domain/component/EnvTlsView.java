package com.sixbean.testpike.core.project.domain.component;


public record EnvTlsView(String mode, String reason, String caPem, String clientCertPem, String clientKeyPem) {

    
    public boolean hasCa() {
        return caPem != null;
    }

    
    public boolean hasClientCert() {
        return clientCertPem != null;
    }
}
