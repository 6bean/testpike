package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.admin.aichat.api.dto.ChatImageView;

/**
 * Dialog user-image upload bridge . Byte
 * passthrough only: the size/extension/magic gates live in core.
 */
public interface CoreChatImageClient {

    ChatImageView uploadChatImage(long projectId, String filename, String contentType, byte[] content);
}
