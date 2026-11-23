package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared link-folding corpus against the Java single source
 * (ContractMatchKeyRules.foldedPath). The exact same file on disk drives the
 * Python executor frontier test, so the two sides of the site-survey deep
 * drill can never drift apart on dedup semantics: concrete URLs such as
 * /pr/order/123 and templates such as /pr/order/{id} must fold to one page.
 * Extra K8 rules stay empty here on purpose — only built-in shapes are pinned.
 */
class LinkFoldCorpusContractTest {

    private static final String CORPUS_RESOURCE = "webelement/link_fold_corpus.json";

    @Test
    void sharedCorpusRowsFoldExactlyThroughTheJavaSingleSource() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode corpus;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CORPUS_RESOURCE)) {
            assertNotNull(in, "shared link-fold corpus must live at " + CORPUS_RESOURCE);
            corpus = mapper.readTree(in);
        }
        JsonNode rows = corpus.path("rows");
        assertTrue(rows.size() >= 12, "corpus must pin at least a dozen shapes, was " + rows.size());

        ContractMatchKeyRules rules = new ContractMatchKeyRules(new ConfigCacheHolder());
        for (JsonNode row : rows) {
            String path = row.path("path").asText();
            String expected = row.path("folded").asText();
            assertEquals(expected, rules.foldedPath(path),
                    "fold drift on corpus row: " + path);
        }
    }
}
