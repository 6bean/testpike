package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gated read doors over one exchange trail row : the reveal
 * door parses the sealed sidecar line by line ({@code request:/response:<name>}
 * tab {@code ENC:...}) and decrypts only the asked field; the full-text door
 * pages the unclipped trail so the honest "expand full text" pointer stays
 * true. Both refuse cross-door ids, and every refusal wording names the input
 * value and the way out.
 */
class ExchangeTrailQueryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConfigCipher cipher = mock(ConfigCipher.class);
    private final ExchangeSecretLedger secrets = new ExchangeSecretLedger(cipher);
    private final ExchangeTrailQuery query = new ExchangeTrailQuery(jdbc, secrets);

    private void row(long id, String jobKind, String jobRef, String detail,
                     String detailFull, String sealed) {
        Map<String, Object> loaded = new java.util.HashMap<>();
        loaded.put("job_kind", jobKind);
        loaded.put("job_ref", jobRef);
        loaded.put("detail", detail);
        loaded.put("detail_full", detailFull);
        loaded.put("sealed", sealed);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(loaded);
    }

    private void stubSymmetricCipher() {
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "X" + inv.getArgument(0));
        when(cipher.decrypt(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).substring(1));
    }

    // ---------- reveal ----------

    @Test
    void revealParsesSealedLinesAndDecryptsTheAskedField() {
        stubSymmetricCipher();
        row(9L, "login-state-probe", "1", "detail", null,
                "request:authorization\t" + secrets.seal("Bearer real-token") + "\n"
                        + "request:cookie\t" + secrets.seal("sid=abc") + "\n"
                        + "response:set-cookie\t" + secrets.seal("a=1") + "\n"
                        + "response:set-cookie\t" + secrets.seal("b=2"));

        ExchangeTrailQuery.TrailRevealView view =
                query.reveal("login-state-probe", "1", 9L, "request:authorization");
        assertEquals(List.of("Bearer real-token"), view.values(),
                "single value for a single-valued header");
        assertEquals("request:authorization", view.field());

        ExchangeTrailQuery.TrailRevealView multi =
                query.reveal("login-state-probe", "1", 9L, "response:set-cookie");
        assertEquals(List.of("a=1", "b=2"), multi.values(),
                "every Set-Cookie occurrence reveals in line order");
    }

    @Test
    void revealUnknownFieldNamesTheInputAndTheRevealingKeys() {
        stubSymmetricCipher();
        row(9L, "credential-test", "2", "detail", null,
                "request:authorization\t" + secrets.seal("v"));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> query.reveal("credential-test", "2", 9L, "response:location"));
        assertTrue(refused.getMessage().contains("response:location"),
                "names the rejected input value: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("request:authorization"),
                "lists what the row actually can reveal: " + refused.getMessage());
    }

    @Test
    void revealWithoutStoredCiphertextStaysHonest() {
        when(cipher.isEnabled()).thenReturn(false);
        row(9L, "login-state-probe", "1", "detail", null,
                "request:cookie\t（加密服务未配置，密文未留存）");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> query.reveal("login-state-probe", "1", 9L, "request:cookie"));
        assertTrue(refused.getMessage().contains("加密服务未配置"),
                "says why the value never got sealed: " + refused.getMessage());
    }

    @Test
    void revealRefusesRowsOfAnotherDoorObject() {
        row(9L, "login-state-probe", "7", "detail", null, "request:cookie\tENC:x");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> query.reveal("login-state-probe", "1", 9L, "request:cookie"));
        assertTrue(refused.getMessage().contains("id=9"),
                "points at the row that does not belong: " + refused.getMessage());
    }

    // ---------- full text window ----------

    @Test
    void fullTextPagesTheUnclippedTrail() {
        row(9L, "login-state-probe", "1", "clipped preview…", "0123456789ABCDEF", null);
        ExchangeTrailQuery.TrailFullTextView page =
                query.fullText("login-state-probe", "1", 9L, 12, 3);
        assertEquals("CDE", page.text());
        assertEquals(12, page.from());
        assertEquals(16, page.totalChars());
        assertTrue(page.hasMore());
        assertFalse(query.fullText("login-state-probe", "1", 9L, 15, 3).hasMore());
        assertEquals("", query.fullText("login-state-probe", "1", 9L, 99, 3).text(),
                "a cursor past the end is an honest empty page, never a failure");
    }

    @Test
    void fullTextFallsBackToDetailWhenNothingWasOverBudget() {
        row(9L, "login-state-probe", "1", "full detail fits the budget", null, null);
        ExchangeTrailQuery.TrailFullTextView page =
                query.fullText("login-state-probe", "1", 9L, 0, null);
        assertEquals("full detail fits the budget", page.text());
        assertFalse(page.hasMore());
    }

    @Test
    void missingRowIsAnHonestRefusalNotAnEmptyAnswer() {
        when(jdbc.queryForMap(anyString(), any())).thenThrow(
                new org.springframework.dao.EmptyResultDataAccessException(0));
        ServiceException refused = assertThrows(ServiceException.class,
                () -> query.reveal("login-state-probe", "1", 404L, "request:cookie"));
        assertTrue(refused.getMessage().contains("id=404"), refused.getMessage());
    }

    // ---------- writer: full trail lands beside the clipped preview ----------

    @Test
    void writerStoresTheFullTrailOnlyWhenThePreviewWasClipped() {
        JdbcTemplate jdbcWriter = mock(JdbcTemplate.class);
        com.sixbean.testpike.core.config.domain.service.ConfigService config =
                mock(com.sixbean.testpike.core.config.domain.service.ConfigService.class);
        when(config.getValue(eq("ui.tail.detail-max"), anyString())).thenReturn("16384");
        TaskEventWriter writer = new TaskEventWriter(jdbcWriter, config);

        writer.step("login-state-probe", "1", "tok", "收到响应", "short preview",
                null, "the whole unclipped trail");
        ArgumentCaptor<Object[]> over = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcWriter).update(anyString(), over.capture());
        assertEquals("the whole unclipped trail", over.getValue()[7],
                "the full text rides the detail_full column for the expand door");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        writer.step("login-state-probe", "1", "tok", "收到响应", "short preview", null, null);
        verify(jdbcWriter, times(2)).update(anyString(), args.capture());
        List<Object[]> calls = args.getAllValues();
        assertNull(calls.get(1)[7], "no full copy when the preview already carries everything");
        assertEquals(8, calls.get(1).length, "insert keeps one shape for every step");
    }

    @Test
    void revealRoundTripsThroughSealWithoutPlaintextInSealedShape() {
        stubSymmetricCipher();
        String sealedLine = "request:authorization\t" + secrets.seal("Bearer super-secret");
        assertTrue(sealedLine.startsWith("request:authorization\tENC:"),
                "storage shape per the ledger contract: " + sealedLine);
        row(3L, "credential-test", "8", "detail", null, sealedLine);
        assertEquals(List.of("Bearer super-secret"),
                query.reveal("credential-test", "8", 3L, "request:authorization").values());
    }
}
