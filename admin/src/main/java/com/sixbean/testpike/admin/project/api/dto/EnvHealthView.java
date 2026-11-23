package com.sixbean.testpike.admin.project.api.dto;


public record EnvHealthView(String status, String checkedAt, String msg,
                            String tlsMode, String certIssuer, String certNotAfter,
                            Boolean certChainComplete, String certCheckedAt) {
}
