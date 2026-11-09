package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi response for one keyset page of conversation archive items. */
public final class ArchivePage {

    @Nullable private final List<Item> items;
    @Nullable private final String nextCursor;

    public ArchivePage() {
        this(Collections.emptyList(), null);
    }

    public ArchivePage(@Nullable List<Item> items, @Nullable String nextCursor) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = nextCursor;
    }

    public List<Item> getItems() {
        return items == null ? Collections.emptyList() : items;
    }

    @Nullable
    public String getNextCursor() {
        return nextCursor;
    }

    /** One media/file message or one extracted URL from a text message. */
    public static final class Item {
        @Nullable private final String messageId;
        @Nullable private final Long seq;
        @Nullable private final String senderId;
        @Nullable private final String createdAt;
        @Nullable private final String kind;
        @Nullable private final Attachment attachment;
        @Nullable private final Link link;

        public Item() {
            this(null, null, null, null, null, null, null);
        }

        public Item(
                @Nullable String messageId,
                @Nullable Long seq,
                @Nullable String senderId,
                @Nullable String createdAt,
                @Nullable String kind,
                @Nullable Attachment attachment,
                @Nullable Link link) {
            this.messageId = messageId;
            this.seq = seq;
            this.senderId = senderId;
            this.createdAt = createdAt;
            this.kind = kind;
            this.attachment = attachment;
            this.link = link;
        }

        @Nullable public String getMessageId() { return messageId; }
        @Nullable public Long getSeq() { return seq; }
        @Nullable public String getSenderId() { return senderId; }
        @Nullable public String getCreatedAt() { return createdAt; }
        @Nullable public String getKind() { return kind; }
        @Nullable public Attachment getAttachment() { return attachment; }
        @Nullable public Link getLink() { return link; }

        /** Links can share a message id, so their URL is part of the stable UI key. */
        public String stableKey() {
            String id = messageId == null ? "" : messageId;
            String url = link == null || link.getUrl() == null ? "" : link.getUrl();
            return id + "\n" + url;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Item that)) return false;
            return Objects.equals(messageId, that.messageId)
                    && Objects.equals(seq, that.seq)
                    && Objects.equals(senderId, that.senderId)
                    && Objects.equals(createdAt, that.createdAt)
                    && Objects.equals(kind, that.kind)
                    && Objects.equals(attachment, that.attachment)
                    && Objects.equals(link, that.link);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, seq, senderId, createdAt, kind, attachment, link);
        }
    }

    /** Attachment fields from the frozen archive wire contract. */
    public static final class Attachment {
        @Nullable private final String id;
        @Nullable private final String fileName;
        @Nullable private final String mimeType;
        @Nullable private final Long sizeBytes;
        @Nullable private final Long durationMs;
        @Nullable private final String blurHash;
        @Nullable private final Integer width;
        @Nullable private final Integer height;

        public Attachment() {
            this(null, null, null, null, null, null, null, null);
        }

        public Attachment(
                @Nullable String id,
                @Nullable String fileName,
                @Nullable String mimeType,
                @Nullable Long sizeBytes,
                @Nullable Long durationMs,
                @Nullable String blurHash,
                @Nullable Integer width,
                @Nullable Integer height) {
            this.id = id;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.blurHash = blurHash;
            this.width = width;
            this.height = height;
        }

        @Nullable public String getId() { return id; }
        @Nullable public String getFileName() { return fileName; }
        @Nullable public String getMimeType() { return mimeType; }
        @Nullable public Long getSizeBytes() { return sizeBytes; }
        @Nullable public Long getDurationMs() { return durationMs; }
        @Nullable public String getBlurHash() { return blurHash; }
        @Nullable public Integer getWidth() { return width; }
        @Nullable public Integer getHeight() { return height; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Attachment that)) return false;
            return Objects.equals(id, that.id)
                    && Objects.equals(fileName, that.fileName)
                    && Objects.equals(mimeType, that.mimeType)
                    && Objects.equals(sizeBytes, that.sizeBytes)
                    && Objects.equals(durationMs, that.durationMs)
                    && Objects.equals(blurHash, that.blurHash)
                    && Objects.equals(width, that.width)
                    && Objects.equals(height, that.height);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, fileName, mimeType, sizeBytes, durationMs, blurHash, width, height);
        }
    }

    public static final class Link {
        @Nullable private final String url;

        public Link() {
            this(null);
        }

        public Link(@Nullable String url) {
            this.url = url;
        }

        @Nullable public String getUrl() { return url; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Link that)) return false;
            return Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url);
        }
    }
}
