package com.sixbean.testpike.foreign;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A handler living outside this application's core package, used by the catalog test to prove the
 * generated catalog only speaks for this service's own controllers (framework handlers such as the
 * error page must not become callable entries).
 */
@RequestMapping("/foreign")
public class ForeignMappingFixture {

    /** Any read-shaped mapping; it should never appear in the catalog. */
    @GetMapping("/thing")
    public String thing() {
        return "thing";
    }
}
