package com.sixbean.testpike.admin.auth.api.dto;

import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;


import java.util.List;

/**
 *
 * @param programs active program lens snapshot for the header switcher (empty when the
 * user sees no program — never null, so the UI needs no special case)
 */
public record MeResult(CurrentUser user, List<UserProjectInfo> projects, boolean passwordExpired,
                       List<UserProgramView> programs) {
}
