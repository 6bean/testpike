package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.regex.Pattern;

/**
 * One named word-form of the pre-send redaction vocabulary (value object,
 * behaviour kernel second round item 2). {@code form} is the human label
 * rendered into the mask marker so the model and any reader see WHICH kind
 * of value was masked without ever seeing the value itself; {@code pattern}
 * is the detection regex matching that whole span.
 */
public record RedactRule(String form, Pattern pattern) {
}
