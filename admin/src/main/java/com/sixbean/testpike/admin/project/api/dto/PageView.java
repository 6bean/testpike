package com.sixbean.testpike.admin.project.api.dto;


import java.util.List;


public record PageView<T>(List<T> list, Long total) {
}
