package com.sixbean.testpike.core.credential.domain.service;

import com.sixbean.testpike.core.credential.api.dto.CredentialTestInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestOutcome;

/**
 * "Test now" door for the credential vault: fires one real request at a
 * target address with the decrypted credential material injected from the
 * placeholder, recording the whole exchange (masked) into the shared
 * process-event ledger for the tail drawer.
 */
public interface CredentialTestService {

    /** Runs one test synchronously; grade rides as a first-class field . */
    CredentialTestOutcome test(long credentialId, CredentialTestInput input);
}
