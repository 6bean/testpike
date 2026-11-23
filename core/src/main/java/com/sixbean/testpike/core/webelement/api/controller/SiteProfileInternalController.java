package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;
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
 * Internal site-profile ledger door. Membership guarding and permission keys
 * live on the admin side; this door only forwards to the single site-profile
 * service, which owns scope derivation, name-clash refusal, the write-free
 * idempotent save and the optimistic-lock (revision) refusal.
 */
@RestController
@RequestMapping("/internal/site-profiles")
public class SiteProfileInternalController {

    private final SiteProfileService service;

    public SiteProfileInternalController(SiteProfileService service) {
        this.service = service;
    }

    /** Create a profile; the scope prefix is derived server-side. */
    @PostMapping
    public Response<SiteProfileView> create(@RequestBody SiteProfileInput input) {
        return Response.builderSuccess(service.create(input));
    }

    /** Profiles of one project, newest activity first. */
    @GetMapping
    public Response<List<SiteProfileView>> list(@RequestParam long projectId) {
        return Response.builderSuccess(service.list(projectId));
    }

    /** One profile by id (NOT_FOUND when the row does not exist). */
    @GetMapping("/{id}")
    public Response<SiteProfileView> get(@PathVariable long id) {
        return Response.builderSuccess(service.get(id));
    }

    /** Update with the caller's revision token; a no-change save writes nothing. */
    @PutMapping("/{id}")
    public Response<SiteProfileView> update(@PathVariable long id, @RequestBody SiteProfileInput input) {
        return Response.builderSuccess(service.update(id, input));
    }

    /** Delete the profile row only; survey runs and collected elements stay. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        service.delete(id);
        return Response.builderSuccess(null);
    }
}
