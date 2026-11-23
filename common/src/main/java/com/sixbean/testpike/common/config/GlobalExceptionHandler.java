package com.sixbean.testpike.common.config;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Spring 6 message shape of a relay I/O failure:
     * {@code I/O error on POST request for "http://host:port/path": ...}. The quoted URL
     * is the fingerprint target; message is used because the exception is also constructed
     * directly (tests/older paths) without the structured resource accessor being set.
     */
    private static final Pattern RELAY_TARGET = Pattern.compile("request for \"([^\"]+)\"");

    private final DownstreamForwardingGuard forwardingGuard;

    public GlobalExceptionHandler() {
        this(new DownstreamForwardingGuard());
    }

    public GlobalExceptionHandler(DownstreamForwardingGuard forwardingGuard) {
        this.forwardingGuard = forwardingGuard;
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Response<Void>> handleServiceException(ServiceException ex) {
        int code = ex.getCode() != null ? ex.getCode() : BizErrorCode.INTERNAL_ERROR;
        String message = format(ex.getMessage(), ex.getArgs());
        if (code == BizErrorCode.INTERNAL_ERROR) {
            log.warn("ServiceException 未指定业务错误码，兜底 50000: message={}", message);
        }
        return build(code, message);
    }

    
    @ExceptionHandler(OptimisticLockingException.class)
    public ResponseEntity<Response<Void>> handleOptimisticLocking(OptimisticLockingException ex) {
        return build(OptimisticLockingException.CODE, ex.getMessage());
    }

    
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Response<Void>> handleValidation(ValidationException ex) {
        return build(BizErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + (fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "校验失败"))
                .orElse("参数校验失败");
        return build(BizErrorCode.VALIDATION_ERROR, message);
    }

    
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Response<Void>> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return build(BizErrorCode.NOT_FOUND, "接口不存在: " + ex.getResourcePath());
    }

    
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Response<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return build(BizErrorCode.METHOD_NOT_ALLOWED,
                "接口不支持 " + ex.getMethod() + " 方法（" + (ex.getSupportedHttpMethods() == null
                        ? "仅支持其他方法" : "支持方法=" + ex.getSupportedHttpMethods()) + "）");
    }

    
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingRequestParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return build(BizErrorCode.VALIDATION_ERROR, "缺少必填参数: " + ex.getParameterName());
    }

    
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException jme) {
            return build(BizErrorCode.VALIDATION_ERROR,
                    "请求体字段类型不匹配: " + jme.getPathReference() + "（请检查字段值格式）");
        }
        return build(BizErrorCode.VALIDATION_ERROR, "请求体 JSON 格式非法，无法解析，请检查提交内容");
    }

    
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Response<Void>> handleMaxUploadSizeExceeded(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        long maxMb = ex.getMaxUploadSize() > 0 ? ex.getMaxUploadSize() / 1024 / 1024 : -1;
        return build(BizErrorCode.VALIDATION_ERROR, maxMb > 0
                ? "文件超过服务器上传上限（" + maxMb + "MB），请联系管理员调整配置"
                : "文件超过服务器上传上限，请联系管理员调整配置");
    }

    /**
 * Downstream forwarding failure typing : while core was half-dead, the
 * executor protocol relay (register/claim/heartbeat every ~2s) fell into the catch-all
 * and dumped a full stack per request (309 identical ERROR lines in one window). This
 * typing emits one line naming the target and root-cause class without a stack; repeats
 * of the same fingerprint collapse inside a hot-tunable window
 * (tp.log.forwarding-collapse-window-ms, default 60s) and the rollover line carries the
 * suppressed count — convergence without muting, never silently swallowed. The
 * caller-visible contract stays 50000 "system busy" so executor retry semantics and
 * E2E assertions cannot drift along with log hygiene.
 */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Response<Void>> handleDownstreamFailure(ResourceAccessException ex) {
        String target = relayTarget(ex);
        String rootCause = rootCauseClass(ex);
        String line = "下游服务不可达（转发失败，依赖可能未启动）: " + target + " 根因=" + rootCause;
        String toLog = forwardingGuard.admit(
                ex.getClass().getName() + "|" + target + "|" + rootCause, line);
        if (toLog != null) {
            log.error(toLog);
        }
        return build(BizErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }

    /** Extract the relayed target (host:port+path); fall back to a readable prefix when the
     *  message shape differs so no two distinct failures ever share an empty fingerprint. */
    private static String relayTarget(ResourceAccessException ex) {
        String message = ex.getMessage();
        if (message != null) {
            Matcher m = RELAY_TARGET.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
        }
        return message == null ? ex.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 160));
    }

    private static String rootCauseClass(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    /**
     * Client-aborted async/SSE responses (broken pipe on a stream nobody is
     * reading anymore): answer 409 with an empty body — there is no listener
     * left to consume one — and log a single WARN naming the root cause.
     * Without this mapping every disconnect reached the catch-all below and
     * emitted a full ERROR stack, which tripped the R53 log noise gate in
     * the admin logs during the tail-f live windows.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsable(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        Throwable root = ex.getCause() == null ? ex : ex.getCause();
        log.warn("异步/SSE 响应因客户端断连不可用: {}", String.valueOf(root));
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleException(Exception ex) {
        log.error("未预期异常", ex);
        return build(BizErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }

    private ResponseEntity<Response<Void>> build(int code, String message) {
        Response<Void> body = Response.builderFail(String.valueOf(code), message);
        return ResponseEntity.status(httpStatus(code)).body(body);
    }

    
    static HttpStatus httpStatus(int code) {
        return switch (code / 100) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 405 -> HttpStatus.METHOD_NOT_ALLOWED;
            case 413 -> HttpStatus.valueOf(413);
            case 423 -> HttpStatus.LOCKED;
            case 428 -> HttpStatus.PRECONDITION_REQUIRED;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 409 -> HttpStatus.CONFLICT;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    
    private String format(String message, Object[] args) {
        if (message == null) {
            return "业务处理失败";
        }
        if (args != null && args.length > 0) {
            try {
                return String.format(message, args);
            } catch (Exception e) {
                log.warn("ServiceException message 格式化失败，原样返回: message={}", message);
            }
        }
        return message;
    }
}
