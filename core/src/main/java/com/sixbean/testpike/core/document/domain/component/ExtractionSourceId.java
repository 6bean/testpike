package com.sixbean.testpike.core.document.domain.component;


public final class ExtractionSourceId {

    private static final String PREFIX = "doc:";

    private ExtractionSourceId() {
    }

    
    public static String forDocument(long documentId) {
        return PREFIX + documentId;
    }
}
