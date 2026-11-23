package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S6 census scanner : fold every web script's inline
 * locators onto normalized page keys and rank elements by how many scripts
 * reference them — that ranking is the migration value order feeding the
 * later batch-draft step. Page attribution and anchor extraction ride the
 * same single-source walk as the save-door registration (R40); templated
 * goto urls without a scheme stay honest (unattributed, never guessed).
 */
class WebElementCensusScannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WebElementCensusScanner scanner() {
        ConfigCacheHolder configs = Mockito.mock(ConfigCacheHolder.class);
        Mockito.when(configs.get(Mockito.anyString())).thenReturn(null);
        return new WebElementCensusScanner(mapper,
                new WebElementPageKeys(new ContractMatchKeyRules(configs)));
    }

    private static LinkedHashMap<Long, String> docs(String... actionJsonByScript) {
        LinkedHashMap<Long, String> byScript = new LinkedHashMap<>();
        for (int i = 0; i < actionJsonByScript.length; i++) {
            byScript.put((long) (i + 1), actionJsonByScript[i]);
        }
        return byScript;
    }

    private static String script(String url, String... steps) {
        return "{\"endType\":\"web\",\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\""
                + url + "\"}}],\"steps\":[" + String.join(",", steps) + "]}";
    }

    @Test
    void elementReferencedByMoreScriptsRanksFirst() {
        WebElementCensusReport report = scanner().scan(docs(
                script("http://shop.test/login",
                        "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\"}}",
                        "{\"action\":\"input\",\"target\":{\"css\":\"#pwd\"},\"value\":\"x\"}"),
                script("http://shop.test/login",
                        "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\"}}")));

        assertEquals(2, report.scannedScripts());
        assertEquals(0, report.unattributedSteps());
        assertEquals(2, report.entries().size());
        WebElementCensusEntry top = report.entries().get(0);
        assertEquals("http://shop.test/login", top.pageKey());
        assertEquals("#login-btn", top.anchors().get("css"));
        assertEquals(List.of(1L, 2L), top.scriptIds());
        assertEquals(2, top.stepCount());
        assertEquals(1, report.entries().get(1).scriptIds().size());
    }

    @Test
    void concreteNumericIdSegmentsFoldOntoOnePageKey() {
        WebElementCensusReport report = scanner().scan(docs(
                script("http://shop.test/pets/123",
                        "{\"action\":\"click\",\"target\":{\"css\":\".adopt\"}}"),
                script("http://shop.test/pets/456",
                        "{\"action\":\"click\",\"target\":{\"css\":\".adopt\"}}")));

        assertEquals(1, report.entries().size());
        assertEquals(2, report.entries().get(0).scriptIds().size());
        assertTrue(report.entries().get(0).pageKey().startsWith("http://shop.test/pets/"));
    }

    @Test
    void templatedGotoUrlsStayUnattributedInsteadOfGuessed() {
        WebElementCensusReport report = scanner().scan(docs(
                script("{{ENV:URL}}/login",
                        "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\"}}")));

        assertEquals(1, report.scannedScripts());
        assertTrue(report.entries().isEmpty());
        assertEquals(1, report.unattributedSteps());
    }

    @Test
    void branchStepsJoinTheCurrentPageInDocumentOrder() {
        WebElementCensusReport report = scanner().scan(docs(
                script("http://shop.test/cart",
                        "{\"action\":\"if\",\"target\":{\"text\":\"promo\"},\"steps\":["
                                + "{\"action\":\"click\",\"target\":{\"text\":\"确认下单\"}}],"
                                + "\"elseSteps\":[{\"action\":\"click\",\"target\":{\"css\":\".deny\"}}]}")));

        // The if-condition target is itself a DOM locator (ledger semantics
        // unchanged): condition anchor + both branch anchors all count.
        assertEquals(3, report.entries().size());
        for (WebElementCensusEntry entry : report.entries()) {
            assertEquals("http://shop.test/cart", entry.pageKey());
        }
    }

    @Test
    void malformedDocumentsCountAsParseFailuresWithoutThrowing() {
        WebElementCensusReport report = scanner().scan(docs(
                "{not json",
                script("http://shop.test/login",
                        "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\"}}")));

        assertEquals(2, report.scannedScripts());
        assertEquals(1, report.parseFailures());
        assertEquals(1, report.entries().size());
    }
}
