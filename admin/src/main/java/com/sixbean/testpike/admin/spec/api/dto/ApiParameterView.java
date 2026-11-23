package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;


public record ApiParameterView(String name, String location, boolean required, String type) {
}
