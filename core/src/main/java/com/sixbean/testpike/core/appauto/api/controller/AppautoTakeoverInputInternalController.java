package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.domain.service.AppautoTakeoverService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/appauto/takeover/input")
public class AppautoTakeoverInputInternalController {

    private final AppautoTakeoverService takeoverService;

    public AppautoTakeoverInputInternalController(AppautoTakeoverService takeoverService) {
        this.takeoverService = takeoverService;
    }

    
    @PostMapping("/enqueue")
    @SuppressWarnings("unchecked")
    public Response<Map<String, Object>> enqueue(@RequestBody Map<String, Object> body) {
        Object commands = body.get("commands");
        if (!(commands instanceof List<?> list)) {
            throw new ServiceException("接管输入下发必须携带 commands 指令列表——请补填至少一条指令后重新下发，正确写法：commands 为非空指令数组",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long requestId = requireLong(body.get("requestId"), "入队必须携带接管申请 id");
        long operatorId = requireLong(body.get("operatorId"), "入队必须携带操作人 id（跨服务 DTO 透传）");
        takeoverService.enqueueInput(requestId, operatorId, (List<Map<String, Object>>) list);
        return Response.builderSuccess(Map.of("accepted", list.size()));
    }

    
    @PostMapping("/poll")
    public Response<List<Map<String, Object>>> poll(@RequestBody Map<String, Object> body) {
        int limit = body.get("limit") == null ? 10 : (int) requireLong(body.get("limit"),
                "limit 非法（须为数字）");
        long executorId = requireLong(body.get("executorId"), "领取必须携带执行器 id");
        Long requestId = nullableLong(body.get("requestId"));
        if (requestId != null) {
            return Response.builderSuccess(takeoverService.pollInputCommands(requestId,
                    executorId, limit));
        }
        return Response.builderSuccess(takeoverService.pollInputCommandsForExecutor(executorId, limit));
    }

    private static long requireLong(Object value, String why) {
        Long parsed = nullableLong(value);
        if (parsed == null) {
            throw new ServiceException("接管输入协议请求缺参——" + why
                    + "；请核对请求体后重新发送，正确写法：该字段为数字 id", BizErrorCode.VALIDATION_ERROR);
        }
        return parsed;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("接管输入协议请求数字字段非法（当前：" + value
                    + "）——请改为不带引号的数字，正确写法：limit=10、executorId=123",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
