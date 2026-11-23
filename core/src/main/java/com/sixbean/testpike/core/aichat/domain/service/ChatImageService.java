package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatImageInfo;

/**
 * Upload door for images a user attaches to a dialog question (AO batch
 * P4). Single write point of the channel: validate first, store once,
 * mint only canonical chat-image keys — a refused upload leaves zero bytes
 * behind (same posture as comment attachments).
 */
public interface ChatImageService {

    /**
 * Store one user image under the project's segment.
 *
 * @param projectId issuing project segment (must exist, H1 anchor)
 * @param fileName original file name (cleaned at StorageKeys)
 * @param declaredContentType client-declared MIME (blank tolerated; magic wins)
 * @param content raw image bytes
 * @return the minted key + display label, never null
 * @throws com.sixbean.testpike.common.exception.ServiceException when the
 * project is unknown, the bytes fail the image gates, or storage
 * refuses — always naming the offending value
 */
    ChatImageInfo upload(long projectId, String fileName, String declaredContentType, byte[] content);

    /**
 * Mint a presigned GET url for one chat-image key (AO batch P4 segment
 * 2c-B read door). This is the SINGLE ownership point of the read channel:
 * ai-service never touches object storage itself — the key's project
 * segment, its shape, and its presence are all checked here before any url
 * leaves the door (H1 red line: a presigned url for another project's
 * photo would hand the bytes over horizontally).
 *
 * @param projectId requesting project segment (carried by the dialog run)
 * @param objectKey canonical chat-image key to read
 * @return presigned GET url, never null/blank
 * @throws com.sixbean.testpike.common.exception.ServiceException when the
 * key is not a canonical chat-image key, belongs to another
 * project, or the object is gone — always naming the offender
 */
    String presignReadUrl(long projectId, String objectKey);
}
