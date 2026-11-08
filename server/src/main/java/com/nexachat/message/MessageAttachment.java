package com.nexachat.message;

import com.nexachat.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code message_attachments} (V1__init.sql + V2__attachments.sql): one
 * stored file plus its message binding.
 *
 * <p><b>Lifecycle (V2, phase-09 multimedia):</b> uploads exist BEFORE a
 * message references them — {@code POST /api/v1/attachments} inserts a row
 * with {@code owner_id} = uploader and {@code message_id = NULL};
 * {@code MessageService.send()} then claims the row by setting
 * {@code message_id} inside the send transaction (owner-checked, single-use —
 * an already-bound row cannot be re-attached). This single-table design was
 * chosen over a separate staging table: no row is copied or moved on bind, the
 * entity stays one shape, and orphaned unbound uploads are harmless
 * (owner-private until bound, GC-able later).
 *
 * <p>{@code storage_key} is the server-generated path under the storage root
 * (never the client filename — traversal-safe per §13) and is UNIQUE.
 * {@code original_name} is sanitized display metadata only.
 */
@Entity
@Table(name = "message_attachments")
public class MessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * LAZY per plan §9.3.7. NULL until the row is bound by a send — V2 dropped
     * the NOT NULL constraint so uploads can precede their message.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    /** The uploader; the only principal allowed to bind or read an unbound row. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "storage_key", nullable = false, length = 120, unique = true)
    private String storageKey;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    /** MIME sniffed from content at upload time — never the client-declared type. */
    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_ms")
    private Integer durationMs;

    /**
     * Optional client-supplied BlurHash placeholder for image attachments
     * (6..64 base83 characters). {@code null} for non-image and legacy rows.
     */
    @Column(name = "blur_hash", length = 64)
    private String blurHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageAttachment() {
        // JPA only
    }

    /**
     * Creates an UNBOUND upload row owned by {@code owner}; it becomes visible
     * to conversation members only after {@link #bindTo(Message)}.
     */
    public static MessageAttachment upload(User owner, String storageKey, String originalName,
                                           String mimeType, long sizeBytes,
                                           Integer width, Integer height, Integer durationMs,
                                           String blurHash) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.owner = owner;
        attachment.storageKey = storageKey;
        attachment.originalName = originalName;
        attachment.mimeType = mimeType;
        attachment.sizeBytes = sizeBytes;
        attachment.width = width;
        attachment.height = height;
        attachment.durationMs = durationMs;
        attachment.blurHash = blurHash;
        return attachment;
    }

    /**
     * Claims this upload for {@code message}. Callers must first verify the
     * row is still unbound ({@code getMessage() == null}) and owned by the
     * sender — {@link MessageService} enforces both in the send transaction.
     */
    public void bindTo(Message message) {
        this.message = message;
    }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    /** @return the bound message, or {@code null} while the upload is unclaimed */
    public Message getMessage() {
        return message;
    }

    public User getOwner() {
        return owner;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getBlurHash() {
        return blurHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
