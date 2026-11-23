package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * One assembly row of a signature auth preset (mirror of core AuthPresetAssembly).
 *
 * @param into  placement slot, header or query
 * @param name  header/query key name
 * @param value template text (${gen.*} functions / {{CRED:key}} references)
 */
public record AuthPresetAssemblyDto(String into, String name, String value) {
}
