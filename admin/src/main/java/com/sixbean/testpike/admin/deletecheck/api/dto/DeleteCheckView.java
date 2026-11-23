package com.sixbean.testpike.admin.deletecheck.api.dto;

import java.util.List;


public record DeleteCheckView(List<DeleteRefView> refs, boolean deletable, String message) {
}
