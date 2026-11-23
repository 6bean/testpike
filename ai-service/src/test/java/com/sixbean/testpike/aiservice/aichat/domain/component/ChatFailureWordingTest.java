import com.sixbean.testpike.aiservice.aichat.domain.component.ChatFailureWording;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat failure line must carry a human sentence naming WHAT went
 * wrong plus the working recovery door (resend / copy controls live on the
 * line) — never a raw provider or parser token such as "closed", never a
 * technical exception message, never a class name. The fold is one place:
 * every wire exception, whatever its own text, maps into one of the four
 * report classes' sentences.
 */
class ChatFailureWordingTest {

    private static String screen(Throwable cause) {
        return ChatFailureWording.screenSentence(cause);
    }

    @Test
    void streamParseBreakNamesTheBreakAndOffersResendWithoutRawTokens() {
        // The real report shape: the client wrapped a parser failure whose
        // JDK message was the bare token "closed".
        ServiceException cause = new ServiceException(
                "AI 流式响应解析失败: closed", BizErrorCode.AI_FORMAT_ERROR);
        String sentence = screen(cause);
        assertTrue(sentence.contains("中断"), sentence);
        assertTrue(sentence.contains("重发"), sentence);
        assertFalse(sentence.contains("closed"), sentence);
        assertFalse(sentence.contains("解析失败"), sentence);
    }

    @Test
    void streamGuardTimeoutNamesTheWaitWithoutGuardInternals() {
        ServiceException cause = new ServiceException(
                "llm stream guard: 线间空闲上限", BizErrorCode.AI_TIMEOUT);
        String sentence = screen(cause);
        assertTrue(sentence.contains("超时"), sentence);
        assertTrue(sentence.contains("重发"), sentence);
        assertFalse(sentence.contains("llm stream guard"), sentence);
    }

    @Test
    void providerRejectionStaysHonestWithoutLeakingUpstreamText() {
        ServiceException cause = new ServiceException(
                "AI 供应商调用失败: upstream returned 429", BizErrorCode.AI_PROVIDER_ERROR);
        String sentence = screen(cause);
        assertTrue(sentence.contains("模型服务"), sentence);
        assertTrue(sentence.contains("重发"), sentence);
        assertFalse(sentence.contains("429"), sentence);
        assertFalse(sentence.contains("upstream"), sentence);
    }

    @Test
    void unknownFailureNeverEchoesTheTechnicalMessage() {
        String sentence = screen(new RuntimeException("boom NPE at com.sixbean.Foo"));
        assertFalse(sentence.contains("boom"), sentence);
        assertFalse(sentence.contains("com.sixbean"), sentence);
        assertTrue(sentence.contains("重发"), sentence);
    }

    @Test
    void classificationWalksTheCauseChainToTheNearestKnownCode() {
        // Wrapper exceptions around the real cause still land on the right
        // class instead of falling through to the generic sentence.
        ServiceException inner = new ServiceException(
                "AI 流式响应解析失败: closed", BizErrorCode.AI_FORMAT_ERROR);
        String sentence = screen(new IllegalStateException("wrapped", inner));
        assertTrue(sentence.contains("中断"), sentence);
    }

    @Test
    void everyScreenSentenceCarriesTheRecoveryDoor() {
        // R63 element 2: the resend door is a real control on the line, and
        // the copy door exists too — no sentence may point at nothing.
        for (Throwable cause : new Throwable[]{
                new ServiceException("x", BizErrorCode.AI_TIMEOUT),
                new ServiceException("x", BizErrorCode.AI_FORMAT_ERROR),
                new ServiceException("x", BizErrorCode.AI_PROVIDER_ERROR),
                new IllegalStateException("x")}) {
            String sentence = screen(cause);
            assertTrue(sentence.contains("可点"), sentence);
            assertTrue(sentence.contains("「重发」"), sentence);
        }
    }

    @Test
    void aDoorWrittenHumanSentencePassesThroughUntouched() {
        // The config door's own words are the authority (the provider-list
        // rejection names the page and the action) — the fold must not
        // rephrase an already-actionable server sentence.
        ServiceException door = new ServiceException(
                "未配置可用 LLM 供应商：请在系统设置-大模型清单启用至少一个端点",
                BizErrorCode.AI_PROVIDER_ERROR);
        assertTrue(screen(door).contains("未配置可用 LLM 供应商"), screen(door));
        assertTrue(screen(door).contains("大模型清单"), screen(door));
    }

    @Test
    void nullCauseGetsTheGenericSentenceNotANullSurface() {
        assertEquals(screen(null), screen(new RuntimeException((String) null)));
    }
}
