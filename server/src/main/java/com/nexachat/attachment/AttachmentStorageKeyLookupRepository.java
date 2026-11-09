package com.nexachat.attachment;

import com.nexachat.message.MessageAttachment;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/** Case-insensitive existence check used only to reject noncanonical file keys. */
public interface AttachmentStorageKeyLookupRepository extends Repository<MessageAttachment, UUID> {

    boolean existsByStorageKeyIgnoreCase(String storageKey);
}
