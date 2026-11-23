package com.sixbean.testpike.common.util;

/**
 * <ul>
 * </ul>
 */
public final class TlsContextResolver {

    private TlsContextResolver() {
    }

    
    public static TlsPolicy defaultStrict() {
        return TlsPolicy.plainStrict();
    }

    
    public static TlsPolicy resolve(String rawMode, String caPem, String clientCertPem, String clientKeyPem) {
        TlsMode mode = TlsMode.fromRaw(rawMode);
        String ca = blankToNull(caPem);
        String cert = blankToNull(clientCertPem);
        String key = blankToNull(clientKeyPem);
        if (cert == null || key == null) {
            cert = null;
            key = null;
        }
        if (mode == TlsMode.CUSTOM_CA && ca == null) {
            return new TlsPolicy(TlsMode.STRICT, null, cert, key);
        }
        return new TlsPolicy(mode, ca, cert, key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
