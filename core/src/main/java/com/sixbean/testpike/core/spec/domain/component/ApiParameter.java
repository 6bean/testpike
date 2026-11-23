package com.sixbean.testpike.core.spec.domain.component;

import java.util.List;


public record ApiParameter(String name, String location, boolean required, String type,
                           String format, String example) {
}
