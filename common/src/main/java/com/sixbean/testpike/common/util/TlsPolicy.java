package com.sixbean.testpike.common.util;


public record TlsPolicy(TlsMode mode, String caPem, String clientCertPem, String clientKeyPem) {

    
    public static TlsPolicy plainStrict() {
        return new TlsPolicy(TlsMode.STRICT, null, null, null);
    }

    
    public boolean ignoreVerify() {
        return mode == TlsMode.INSECURE;
    }

    
    public boolean hasClientCertPair() {
        return clientCertPem != null && clientKeyPem != null;
    }

    
    public boolean isPlainStrict() {
        return mode == TlsMode.STRICT && caPem == null && !hasClientCertPair();
    }
}
