package com.nexachat.message;

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
 * Maps {@code message_attachments} (V1__init.sql): one stored file bound to a
 * message. {@code storage_key} is the server-generated path under the storage
 * root (never the client filename — traversal-safe per §13) and is UNIQUE.
 *
 * <p>{@code message_id} is NOT NULL in the DDL, so rows only exist once bound to
 * a message — the phase-8 upload flow is responsible for producing rows the
 * send path can then claim via {@code attachmentIds}.
 */
@Entity
@Table(name = "message_attachments")
public class MessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** LAZY per plan §9.3.7. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageAttachment() {
        // JPA only
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

    public Message getMessage() {
        return message;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
