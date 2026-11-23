package com.sixbean.testpike.aiservice.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(String type, String text,
                          @JsonProperty("image_url") ImageUrlPayload imageUrl) {

    
    public static ContentPart text(String text) {
        return new ContentPart("text", text, null);
    }

    
    public static ContentPart imageUrl(String url) {
        return new ContentPart("image_url", null, new ImageUrlPayload(url));
    }
}
