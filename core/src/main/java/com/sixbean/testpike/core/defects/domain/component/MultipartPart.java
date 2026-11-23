package com.sixbean.testpike.core.defects.domain.component;


public record MultipartPart(String filename, String contentType, byte[] content) {
}
