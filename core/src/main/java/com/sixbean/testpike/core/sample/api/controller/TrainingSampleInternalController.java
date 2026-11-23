package com.sixbean.testpike.core.sample.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import com.sixbean.testpike.core.sample.api.dto.WordingPair;
import com.sixbean.testpike.core.sample.domain.service.TrainingSampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/training-samples")
public class TrainingSampleInternalController {

    private final TrainingSampleService trainingSampleService;

    public TrainingSampleInternalController(TrainingSampleService trainingSampleService) {
        this.trainingSampleService = trainingSampleService;
    }

    /**
 * The optional sort pair reaches the core column whitelist (single gate). */
    @GetMapping
    public Response<PageResult<TrainingSampleItem>> listSamples(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String sampleType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                trainingSampleService.list(projectId, sampleType, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/wording-pairs")
    public Response<List<WordingPair>> listWordingPairs(@RequestParam long projectId,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return Response.builderSuccess(trainingSampleService.listWordingPairs(projectId, limit));
    }

    
    @PutMapping("/{id}/adoption")
    public Response<Void> updateAdoption(@PathVariable Long id, @RequestParam int adopted) {
        trainingSampleService.updateAdoption(id, adopted);
        return Response.builderSuccess(null);
    }
}
