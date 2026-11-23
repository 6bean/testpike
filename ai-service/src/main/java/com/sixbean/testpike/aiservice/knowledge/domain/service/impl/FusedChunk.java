package com.sixbean.testpike.aiservice.knowledge.domain.service.impl;

import org.springframework.ai.document.Document;


public record FusedChunk(Document document, double score) {
}
