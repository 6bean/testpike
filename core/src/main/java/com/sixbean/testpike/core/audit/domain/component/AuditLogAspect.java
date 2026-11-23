package com.sixbean.testpike.core.audit.domain.component;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;


import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogService auditLogService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditLogAspect(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        EvaluationContext ctx = new MethodBasedEvaluationContext(
                pjp.getTarget(), signature.getMethod(), pjp.getArgs(), paramNameDiscoverer);

        Map<String, Object> before = evalMap(ctx, audit.before());
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            recordFailure(audit, ctx, before, ex);
            throw ex;
        }
        ctx.setVariable("result", result);
        recordSuccess(audit, ctx, before);
        return result;
    }

    private void recordSuccess(Audit audit, EvaluationContext ctx, Map<String, Object> before) {
        try {
            String objectId = evalString(ctx, audit.objectId());
            if (objectId == null) {
                objectId = "0";
            }
            Map<String, Object> after = mergeBodyAfter(evalMap(ctx, audit.after()));
            auditLogService.record(new AuditLogInput(
                    resolveOperatorId(ctx), null, audit.objectType(),
                    objectId, audit.action(), resolveBefore(ctx, before), after));
        } catch (Exception e) {
            log.warn("审计记录失败（不阻断业务）: objectType={} action={} err={}",
                    audit.objectType(), audit.action(), e.getMessage());
        }
    }

    private void recordFailure(Audit audit, EvaluationContext ctx, Map<String, Object> before, Throwable ex) {
        try {
            String objectId = evalStringSafe(ctx, audit.objectId());
            if (objectId == null) {
                objectId = "0";
            }
            Map<String, Object> after = new LinkedHashMap<>();
            Map<String, Object> bodyAfter = AuditLogContext.takeAfter();
            if (bodyAfter != null) {
                after.putAll(bodyAfter);
            }
            after.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            auditLogService.record(new AuditLogInput(
                    OperatorContext.currentOperatorId(), null, audit.objectType(),
                    objectId, audit.action(), resolveBefore(ctx, before), after));
        } catch (Exception e) {
            log.warn("审计失败记录失败（不阻断业务）: objectType={} action={} err={}",
                    audit.objectType(), audit.action(), e.getMessage());
        }
    }

    
    /** Body-assembled outcome snapshots merge over annotation SpEL maps; the ThreadLocal is always consumed here. */
    private Map<String, Object> mergeBodyAfter(Map<String, Object> fromAnnotation) {
        Map<String, Object> bodyAfter = AuditLogContext.takeAfter();
        if (bodyAfter == null) {
            return fromAnnotation;
        }
        Map<String, Object> merged = new LinkedHashMap<>(fromAnnotation != null ? fromAnnotation : Map.of());
        merged.putAll(bodyAfter);
        return merged;
    }

    private Map<String, Object> resolveBefore(EvaluationContext ctx, Map<String, Object> before) {
        if (before != null) {
            AuditLogContext.clear();
            return before;
        }
        return AuditLogContext.takeBefore();
    }

    
    private String evalStringSafe(EvaluationContext ctx, String expression) {
        try {
            return evalString(ctx, expression);
        } catch (Exception e) {
            return null;
        }
    }

    
    private Long resolveOperatorId(EvaluationContext ctx) {
        Long fromHeader = OperatorContext.currentOperatorId();
        if (fromHeader != null) {
            return fromHeader;
        }
        try {
            Object value = parser.parseExpression("#operatorId").getValue(ctx);
            return value instanceof Number n ? n.longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String evalString(EvaluationContext ctx, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Object value = parser.parseExpression(expression).getValue(ctx);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evalMap(EvaluationContext ctx, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Object value = parser.parseExpression(expression).getValue(ctx);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value != null) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("value", value);
            return snapshot;
        }
        return null;
    }
}
