package com.sixbean.testpike.aiservice.llm.domain.component;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.Collection;


public class TokenCounter implements TokenEstimator {

    
    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    
    @Override
    public String engineId() {
        return "cl100k";
    }

    
    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokensOrdinary(text);
    }

    
    public int countTexts(Collection<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String part : parts) {
            total += countTokens(part);
        }
        return total;
    }

    
    public int tokensToApproxChars(int tokens) {
        return Math.max(1, (int) Math.round(tokens * 480.0 / 512));
    }
}
