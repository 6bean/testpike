package com.sixbean.testpike.common.config;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serviceException_带code映射409() throws Exception {
        ResponseEntity<Response<Void>> resp = handler.handleServiceException(
                new ServiceException("用例 %s 已被他人修改，请刷新", BizErrorCode.VERSION_CONFLICT, "TC-DEMO-001"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(), "40901 必须映射 HTTP 409");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(resp.getBody()));
        assertEquals(String.valueOf(BizErrorCode.VERSION_CONFLICT), node.get("code").asText());
        assertEquals("用例 TC-DEMO-001 已被他人修改，请刷新", node.get("msg").asText(), "%s 占位必须格式化");
    }

    @Test
    void serviceException_无code兜底50000() {
        ResponseEntity<Response<Void>> resp = handler.handleServiceException(
                new ServiceException("未指定错误码的业务异常"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.INTERNAL_ERROR), resp.getBody().getCode());
    }

    @Test
    void optimisticLocking_40901语义() throws Exception {
        ResponseEntity<Response<Void>> resp = handler.handleOptimisticLocking(
                new OptimisticLockingException("版本冲突：期望 version=2，实际 version=1"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        JsonNode node = mapper.readTree(mapper.writeValueAsString(resp.getBody()));
        assertEquals("40901", node.get("code").asText());
        assertTrue(node.get("msg").asText().contains("版本冲突"), "文案必须保留业务信息");
    }

    @Test
    void unknownException_兜底500且不泄露内部细节() throws Exception {
        ResponseEntity<Response<Void>> resp = handler.handleException(
                new IllegalStateException("内部敏感堆栈信息: db password=xxx"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        JsonNode node = mapper.readTree(mapper.writeValueAsString(resp.getBody()));
        assertEquals(String.valueOf(BizErrorCode.INTERNAL_ERROR), node.get("code").asText());
        assertTrue(!node.get("msg").asText().contains("password"),
                "响应不得泄露内部异常细节（敏感字段打码铁律）");
    }

    @Test
    void noResource_40401语义() {
        ResponseEntity<Response<Void>> resp = handler.handleNoResource(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/api/v1/not-exist"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.NOT_FOUND), resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().toString().contains("接口不存在"));
    }

    @Test
    void missingRequestParam_40001人话() {
        ResponseEntity<Response<Void>> resp = handler.handleMissingRequestParam(
                new org.springframework.web.bind.MissingServletRequestParameterException("projectId", "long"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.VALIDATION_ERROR), resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().toString().contains("projectId"),
                "msg 必须指明缺失参数名: " + resp.getBody().getMsg());
    }

    @Test
    void methodNotSupported_405人话不伪装500() {
        ResponseEntity<Response<Void>> resp = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.METHOD_NOT_ALLOWED), resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().contains("POST"), "msg 必须点名方法: " + resp.getBody().getMsg());
    }

    @Test
    void requestBodyTypeMismatch_40001人话不伪装500() {
        com.fasterxml.jackson.databind.exc.InvalidFormatException jex = null;
        try {
            mapper.readValue("{\"budgetCents\":1000,\"alertPct\":\"abc\"}", BudgetHolder.class);
        } catch (com.fasterxml.jackson.databind.exc.InvalidFormatException e) {
            jex = e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("夹具 JSON 必须合法", e);
        }
        assertNotNull(jex, "夹具必须产生 InvalidFormatException");
        ResponseEntity<Response<Void>> resp = handler.handleNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", jex));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.VALIDATION_ERROR), resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().contains("alertPct"),
                "msg 必须点名类型不匹配字段: " + resp.getBody().getMsg());
    }

    @Test
    void malformedJson_40001人话不伪装500() {
        ResponseEntity<Response<Void>> resp = handler.handleNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", new com.fasterxml.jackson.core.JsonParseException(
                                (com.fasterxml.jackson.core.JsonParser) null, "Unexpected character 'x'")));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(String.valueOf(BizErrorCode.VALIDATION_ERROR), resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().contains("请求体"), msgOf(resp));
    }

    private static String msgOf(ResponseEntity<Response<Void>> resp) {
        return "msg=" + resp.getBody().getMsg();
    }

    /**
     * Client-aborted async/SSE responses (broken pipe) must map to 409 with an
     * empty body and a single WARN line: nobody is listening anymore, so an
     * ERROR stack per disconnect is log noise (R53 noise-gate finding).
     */
    @Test
    void asyncRequestNotUsable_maps409EmptyBodyAndWarnsWithoutStack() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            org.springframework.web.context.request.async.AsyncRequestNotUsableException ex =
                    new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                            "Response not usable", new java.io.IOException("Broken pipe"));
            ResponseEntity<Void> resp = handler.handleAsyncRequestNotUsable(ex);
            assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
            assertNull(resp.getBody(), "no body to a client that already hung up");
            assertEquals(1, events.list.size(), "exactly one log line per abort");
            assertEquals(ch.qos.logback.classic.Level.WARN, events.list.get(0).getLevel());
            assertTrue(events.list.get(0).getFormattedMessage().contains("Broken pipe"),
                    "the line must name the root cause for ops triage");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void httpStatus_业务码映射正确() {
        assertEquals(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.httpStatus(40001));
        assertEquals(HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.httpStatus(40101));
        assertEquals(HttpStatus.FORBIDDEN, GlobalExceptionHandler.httpStatus(40301));
        assertEquals(HttpStatus.NOT_FOUND, GlobalExceptionHandler.httpStatus(40401));
        assertEquals(HttpStatus.CONFLICT, GlobalExceptionHandler.httpStatus(40902));
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, GlobalExceptionHandler.httpStatus(42801));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, GlobalExceptionHandler.httpStatus(42901));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, GlobalExceptionHandler.httpStatus(50000));
    }
}
