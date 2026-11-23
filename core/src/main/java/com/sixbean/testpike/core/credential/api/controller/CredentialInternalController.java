package com.sixbean.testpike.core.credential.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
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
import com.sixbean.testpike.core.credential.api.dto.CredentialTestInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialTestService;
import com.sixbean.testpike.core.credential.domain.service.impl.CredentialTestServiceImpl;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;


@RestController
@RequestMapping("/internal/credentials")
public class CredentialInternalController {

    private final CredentialService credentialService;
    private final CredentialTestService credentialTestService;
    private final TaskEventWindowReader taskEvents;
    private final com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery exchangeTrails;

    public CredentialInternalController(CredentialService credentialService,
                                        CredentialTestService credentialTestService,
                                        TaskEventWindowReader taskEvents,
                                        com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery exchangeTrails) {
        this.credentialService = credentialService;
        this.credentialTestService = credentialTestService;
        this.taskEvents = taskEvents;
        this.exchangeTrails = exchangeTrails;
    }

    /**
     * "Test now": fire one real request with the vault material injected
     * from the {{CRED:key}} placeholder; the trail lands in the shared
     * process-event ledger, masked (sync run + test-events tail paging).
     */
    @PostMapping("/{id}/test")
    public Response<com.sixbean.testpike.core.credential.api.dto.CredentialTestOutcome> test(
            @PathVariable long id, @RequestBody CredentialTestInput input) {
        return Response.builderSuccess(credentialTestService.test(id, input));
    }

    /** Event window after cursor for one credential test run. */
    @GetMapping("/{id}/test-events")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> testEvents(
            @PathVariable long id, @RequestParam String runToken,
            @RequestParam(defaultValue = "0") long cursor) {
        return Response.builderSuccess(taskEvents.since(
                CredentialTestServiceImpl.EVENT_JOB_KIND, String.valueOf(id), runToken, cursor, null));
    }

    /** Run history of this credential's test trail (revisit entry list,
     *  newest run first; the per-run replay itself stays on test-events). */
    @GetMapping("/{id}/test-runs")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>> testRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(taskEvents.runs(
                CredentialTestServiceImpl.EVENT_JOB_KIND, String.valueOf(id), limit));
    }

    /** Gated reveal of one sealed header value of one credential-test trail row. */
    @GetMapping("/{id}/test-events/{eventId}/reveal")
    public Response<com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery.TrailRevealView>
            revealTrailField(@PathVariable long id, @PathVariable long eventId,
                             @RequestParam String field) {
        return Response.builderSuccess(exchangeTrails.reveal(
                CredentialTestServiceImpl.EVENT_JOB_KIND, String.valueOf(id), eventId, field));
    }

    /** Paged full-text window of one credential-test trail row (same gate + audit). */
    @GetMapping("/{id}/test-events/{eventId}/full")
    public Response<com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery.TrailFullTextView>
            trailFullText(@PathVariable long id, @PathVariable long eventId,
                          @RequestParam(defaultValue = "0") int from,
                          @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(exchangeTrails.fullText(
                CredentialTestServiceImpl.EVENT_JOB_KIND, String.valueOf(id), eventId, from, limit));
    }

    
    @PostMapping
    public Response<Long> create(@RequestParam long projectId, @RequestBody CredentialInput input) {
        return Response.builderSuccess(credentialService.create(projectId, input));
    }

    
    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody CredentialInput input) {
        credentialService.update(id, input);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}")
    public Response<CredentialInfo> get(@PathVariable long id) {
        return Response.builderSuccess(credentialService.get(id));
    }

    
    @GetMapping
    public Response<List<CredentialInfo>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) Boolean enabledOnly) {
        return Response.builderSuccess(credentialService.list(projectId, enabledOnly));
    }

    
    @PutMapping("/{id}/enabled")
    public Response<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        credentialService.setEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        credentialService.delete(id);
        return Response.builderSuccess(null);
    }
}
