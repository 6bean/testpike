package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;


public record AiBatchView(long id, long projectId, long operatorId, int total,
                          int pending, int running, int done, int failed, int cancelled,
                          String createdAt, List<AiBatchItemView> items,
                          // Three-part queue-waiting sentence assembled once on the
                          // core side (reason / backlog / recovery estimate); bound
                          // here by the same JSON name so the poll door keeps it.
                          String queueNotice) {
}
