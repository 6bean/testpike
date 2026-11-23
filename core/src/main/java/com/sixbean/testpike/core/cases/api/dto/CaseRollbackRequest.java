package com.sixbean.testpike.core.cases.api.dto;

/**
 * Version rollback request (contract POST /cases/{id}/versions/{v}/rollback).
 *
 * @param version optimistic lock version number (must equal the current version of the use case, otherwise 40901; concurrency protection).
 */
public record CaseRollbackRequest(Integer version) {
}
