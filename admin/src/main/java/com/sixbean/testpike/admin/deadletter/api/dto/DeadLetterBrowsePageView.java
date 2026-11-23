package com.sixbean.testpike.admin.deadletter.api.dto;

import java.util.List;

/**
 * Wire shape of the core paging result for dead-letter browsing .
 * <p>list+total is deserialized straight from the core envelope; the admin door
 * converts it to the shared PageView without touching either value.
 */
public record DeadLetterBrowsePageView(List<DeadLetterLedgerRowView> list, long total) {
}
