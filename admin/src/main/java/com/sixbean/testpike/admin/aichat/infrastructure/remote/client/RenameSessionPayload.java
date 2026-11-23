package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * Wire shape of the core rename door : the owner id rides the
 * body from the login-pinned admin context — core re-checks ownership and
 * never trusts the wire identity alone (horizontal-access red line AF-g).
 */
record RenameSessionPayload(long userId, String title) {
}
