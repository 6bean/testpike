package com.sixbean.testpike.core.config.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal system configuration interface in the core service layer, consumed by admin over HTTP; see /configs.
 * <p> Sensitive values are returned masked; changes take effect immediately; only system administrators are allowed, gated by @RequirePermission(config:manage) in the admin layer.
 *
 */
@RestController
@RequestMapping("/internal/configs")
public class ConfigInternalController {

    private final ConfigService configService;

    public ConfigInternalController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public Response<List<ConfigInfo>> list(@RequestParam(required = false) String groupName) {
        return Response.builderSuccess(configService.list(groupName));
    }

    @PostMapping
    public Response<ConfigInfo> create(@RequestBody ConfigInput input) {
        return Response.builderSuccess(configService.create(OperatorContext.currentOperatorId(), input));
    }

    @PutMapping("/{id}")
    public Response<ConfigInfo> update(@PathVariable long id, @RequestBody ConfigInput input) {
        return Response.builderSuccess(configService.update(OperatorContext.currentOperatorId(), id, input));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        configService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Reads by key from business data sources, such as screen recording retention period and timeout threshold; changes take effect immediately. */
    @GetMapping("/value")
    public Response<String> getValue(@RequestParam String key, @RequestParam(required = false) String defaultValue) {
        return Response.builderSuccess(configService.getValue(key, defaultValue == null ? "" : defaultValue));
    }
}
