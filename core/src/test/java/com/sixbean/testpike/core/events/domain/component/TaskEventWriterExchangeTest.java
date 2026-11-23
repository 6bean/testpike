package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Detail budget of the process-event ledger is a hot knob and an over-budget
 * write ends in the honest "expand full text" shape (never a silent hard cut);
 * the sealed ciphertext rides a dedicated column so plaintext stays out of
 * the plain detail body.
 */
class TaskEventWriterExchangeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConfigService config = mock(ConfigService.class);

    private TaskEventWriter writer() {
        when(config.getValue(eq("ui.tail.detail-max"), anyString())).thenReturn("16384");
        return new TaskEventWriter(jdbc, config);
    }

    @Test
    void budgetReadsTheHotKeyAndFallsBackToFactoryDefault() {
        when(config.getValue(eq("ui.tail.detail-max"), anyString())).thenReturn("12");
        TaskEventWriter w = new TaskEventWriter(jdbc, config);
        w.step("login-state-probe", "1", "tok", "收到响应", "0123456789ABCDEF tail beyond budget");
        String stored = capturedDetail();
        assertTrue(stored.startsWith("0123456789AB"), "keeps the first budget worth: " + stored);
        assertTrue(stored.contains("展开全文"), "honest pointer instead of a silent cut: " + stored);

        when(config.getValue(eq("ui.tail.detail-max"), anyString()))
                .thenThrow(new IllegalStateException("key row absent"));
        TaskEventWriter fallback = new TaskEventWriter(jdbc, config);
        assertEquals(16384, fallback.detailBudget());
    }

    @Test
    void overBudgetMarkerCarriesTheTotalSize() {
        when(config.getValue(eq("ui.tail.detail-max"), anyString())).thenReturn("8");
        TaskEventWriter w = new TaskEventWriter(jdbc, config);
        w.step("login-state-probe", "1", "tok", "收到响应", "0123456789");
        String stored = capturedDetail();
        assertTrue(stored.contains("共 10 字节"), "total size stated for the honest state: " + stored);
    }

    @Test
    void stepWithoutSealedPayloadKeepsSealedColumnNull() {
        writer().step("login-state-probe", "1", "tok", "判定：有效", "plain detail");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals("plain detail", args.getValue()[5]);
        assertNull(args.getValue()[6], "no sealed material rides without the caller sealing it");
    }

    @Test
    void sealedCiphertextRidesItsOwnColumn() {
        writer().step("credential-test", "2", "tok", "递交的请求", "authorization: eyJhbGci…（总长度 40）",
                "ENC:base64blob");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        String sql = sqlCaptured();
        assertTrue(sql.contains("sealed"), "insert must name the sealed column: " + sql);
        assertEquals("ENC:base64blob", args.getValue()[6]);
        String detail = String.valueOf(args.getValue()[5]);
        assertTrue(!detail.contains("base64blob") && detail.startsWith("authorization"),
                "detail stays the masked display form, ciphertext belongs to sealed");
    }

    private String capturedDetail() {
        return String.valueOf(sqlArgs()[5]);
    }

    private String sqlCaptured() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    private Object[] sqlArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        return args.getValue();
    }
}
