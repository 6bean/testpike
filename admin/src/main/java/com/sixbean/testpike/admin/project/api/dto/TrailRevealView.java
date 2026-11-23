package com.sixbean.testpike.admin.project.api.dto;

import java.util.List;

/**
 * Admin mirror of the gated trail reveal answer : the plaintext
 * values of one sealed header of one exchange-trail row. Multi-valued headers
 * (Set-Cookie) answer one entry per stored line, in trail order. The row and
 * the caller's project membership were both checked server-side, and the call
 * itself landed an audit row.
 */
public record TrailRevealView(String field, List<String> values) {
}
