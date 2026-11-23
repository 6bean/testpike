package com.sixbean.testpike.admin.reports.api.controller;

import com.sixbean.testpike.admin.reports.api.dto.CaliberView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.report.CaliberDefinition;
import com.sixbean.testpike.common.report.CaliberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/reports/calibers")
public class CaliberController {

 
    @GetMapping
    public Response<List<CaliberView>> calibers() {
        List<CaliberView> views = CaliberService.listAll().stream()
                .map(CaliberController::toView)
                .toList();
        return Response.builderSuccess(views);
    }

    private static CaliberView toView(CaliberDefinition def) {
        return new CaliberView(def.code(), def.displayName(), def.version(),
                def.description(), def.hoverText());
    }
}
