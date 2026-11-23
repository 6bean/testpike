package com.sixbean.testpike.api.cluster.api.controller;

import com.sixbean.testpike.api.cluster.infrastructure.remote.client.CoreClusterEnrollClient;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal")
public class ClusterEnrollProtocolController {

    private final CoreClusterEnrollClient coreClusterEnrollClient;

    public ClusterEnrollProtocolController(CoreClusterEnrollClient coreClusterEnrollClient) {
        this.coreClusterEnrollClient = coreClusterEnrollClient;
    }

    
    @PostMapping("/cluster/enroll")
    public Response<Map<String, Object>> enroll(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreClusterEnrollClient.enroll(
                str(body.get("token")), str(body.get("fingerprint"))));
    }

    
    @PostMapping("/cluster/enroll/heartbeat")
    public Response<Map<String, Object>> heartbeat(@RequestBody Map<String, Object> body) {
        Object components = body.get("components");
        return Response.builderSuccess(coreClusterEnrollClient.heartbeat(
                str(body.get("token")), str(body.get("fingerprint")),
                str(body.get("step")), str(body.get("stepStatus")), str(body.get("message")),
                components instanceof List<?> list ? list : null));
    }

 
    @GetMapping("/cluster/installkit-config")
    public Response<Map<String, Object>> installkitConfig() {
        return Response.builderSuccess(coreClusterEnrollClient.installkitConfig());
    }

    
    @GetMapping("/cluster/engine-recipe")
    public Response<Map<String, Object>> engineRecipe(@RequestParam String code) {
        return Response.builderSuccess(coreClusterEnrollClient.engineRecipe(code));
    }

    
    @PostMapping("/cluster/commands/poll")
    public Response<List<Map<String, Object>>> poll(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(coreClusterEnrollClient.pollCommand(body));
    }

 
    @PostMapping("/cluster/commands/progress")
    public Response<Void> progress(@RequestBody Map<String, Object> body) {
        coreClusterEnrollClient.progressCommand(body);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/cluster/commands/result")
    public Response<Void> result(@RequestBody Map<String, Object> body) {
        coreClusterEnrollClient.completeCommand(body);
        return Response.builderSuccess(null);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
