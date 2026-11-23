package com.sixbean.testpike.core.apiaccess;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Handler shapes the pass-through door must classify, kept as a plain class (never registered as a
 * bean) so the introspector and the policy can be exercised without a web context.
 */
@RequestMapping("/internal/fixture")
public class ApiAccessFixtureController {

    /** Project-scoped list with optional filters and a defaulted page. */
    @GetMapping("/items")
    public String listItems(@RequestParam long projectId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") int page) {
        return "list";
    }

    /** One item by path id (anchor rides the path). */
    @GetMapping("/items/{id}")
    public String item(@PathVariable long id, @RequestParam long projectId) {
        return "item";
    }

    /** Multi-project read: the anchor is a comma-joined string, as the visible-domain doors spell it. */
    @GetMapping("/items/visible")
    public String visibleItems(@RequestParam String projectIds) {
        return "visible";
    }

    /** Array-shaped filter (goes on the wire as repeated parameters). */
    @GetMapping("/items/by-status")
    public String byStatus(@RequestParam long projectId, @RequestParam List<Integer> status) {
        return "status";
    }

    /** Create with a declared payload type: the landing whitelist reads its fields off the type. */
    @PostMapping("/tags")
    public String createTag(@RequestBody TagPayloadFixture payload) {
        return "created-tag";
    }

    /** Anchored delete: change class, must be refused by the door. */
    @DeleteMapping("/items/{id}")
    public String deleteItem(@PathVariable long id) {
        return "deleted";
    }

    /** Body-taking create: shape the door does not compose. */
    @PostMapping("/items")
    public String createItem(@RequestBody String payload) {
        return "created";
    }

    /** Optional body: still a change class, still refused. */
    @PutMapping("/items/{id}")
    public String updateItem(@PathVariable long id, @RequestBody(required = false) String payload) {
        return "updated";
    }

    /** Header-bound input: not addressable, so the entry is not dispatchable. */
    @GetMapping("/by-header")
    public String byHeader(@RequestHeader String tenant, @RequestParam long projectId) {
        return "header";
    }

    /** Container-supplied argument is invisible to callers, and does not block the entry. */
    @GetMapping("/with-request-object")
    public String withRequest(HttpServletRequest request, @RequestParam long projectId) {
        return "request-object";
    }

    /** No project anchor at all: stays unreachable until governance verifies it. */
    @GetMapping("/dictionary")
    public String dictionary() {
        return "dictionary";
    }
}
