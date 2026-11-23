package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoStabilityScanResult(int groupsScanned, int flakyGroups,
                                         boolean aiSuggestionEnabled) {
}
