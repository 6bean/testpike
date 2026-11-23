package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Trigger payload of one census-posting door : the concrete
 * project anchor is mandatory — filing migration cards without a project
 * lens would either scan nothing or scan the wrong segment, so the door
 * refuses here instead of answering an empty posting.
 */
public record CensusPostingInput(Long projectId) {
}
