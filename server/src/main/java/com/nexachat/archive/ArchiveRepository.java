package com.nexachat.archive;

import com.nexachat.archive.dto.ArchiveAttachmentRow;
import com.nexachat.archive.dto.ArchiveLinkRow;
import com.nexachat.message.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only queries for the storage browser over the existing message tables.
 *
 * <p>Each page is one query (no per-item follow-ups):
 * <ul>
 *   <li>media/file: {@code messages} driven by {@code idx_msg_conv_seq} and
 *       joined to {@code message_attachments} through the existing
 *       {@code idx_att_msg} index, ordered by the message's global sequence;</li>
 *   <li>link: one page of URL-bearing TEXT messages, again through
 *       {@code idx_msg_conv_seq}; the URL list itself is parsed in Java.</li>
 * </ul>
 *
 * <p>Nullable parameters are CAST so PostgreSQL can type a NULL bind value.
 * The keyset predicates are strict comparisons so consecutive pages never
 * repeat an item, including when new messages arrive while a client pages.
 */
public interface ArchiveRepository extends Repository<Message, UUID> {

    @Query(value = """
            SELECT m.id AS "messageId", m.seq AS "seq", m.sender_id AS "senderId",
                   m.created_at AS "createdAt",
                   a.id AS "attachmentId", a.original_name AS "fileName",
                   a.mime_type AS "mimeType", a.size_bytes AS "sizeBytes",
                   a.duration_ms AS "durationMs", a.blur_hash AS "blurHash",
                   a.width AS "width", a.height AS "height"
            FROM message_attachments a
            JOIN messages m ON m.id = a.message_id
            WHERE m.conversation_id = :conversationId
              AND m.deleted_at IS NULL
              AND m.type <> 'AUDIO'
              AND (CAST(:senderId AS uuid) IS NULL OR m.sender_id = CAST(:senderId AS uuid))
              AND (CAST(:fromTs AS timestamptz) IS NULL
                   OR m.created_at >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs AS timestamptz) IS NULL
                   OR m.created_at <= CAST(:toTs AS timestamptz))
              AND (CAST(:cursorSeq AS bigint) IS NULL
                   OR m.seq < CAST(:cursorSeq AS bigint)
                   OR (m.seq = CAST(:cursorSeq AS bigint)
                       AND a.id > CAST(:cursorAttachmentId AS uuid)))
              AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
            ORDER BY m.seq DESC, a.id ASC
            """, nativeQuery = true)
    List<ArchiveAttachmentRow> findMediaPage(@Param("conversationId") UUID conversationId,
                                             @Param("senderId") UUID senderId,
                                             @Param("fromTs") Instant fromTs,
                                             @Param("toTs") Instant toTs,
                                             @Param("cursorSeq") Long cursorSeq,
                                             @Param("cursorAttachmentId") UUID cursorAttachmentId,
                                             Pageable pageable);

    @Query(value = """
            SELECT m.id AS "messageId", m.seq AS "seq", m.sender_id AS "senderId",
                   m.created_at AS "createdAt",
                   a.id AS "attachmentId", a.original_name AS "fileName",
                   a.mime_type AS "mimeType", a.size_bytes AS "sizeBytes",
                   a.duration_ms AS "durationMs", a.blur_hash AS "blurHash",
                   a.width AS "width", a.height AS "height"
            FROM message_attachments a
            JOIN messages m ON m.id = a.message_id
            WHERE m.conversation_id = :conversationId
              AND m.deleted_at IS NULL
              AND m.type <> 'AUDIO'
              AND (CAST(:senderId AS uuid) IS NULL OR m.sender_id = CAST(:senderId AS uuid))
              AND (CAST(:fromTs AS timestamptz) IS NULL
                   OR m.created_at >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs AS timestamptz) IS NULL
                   OR m.created_at <= CAST(:toTs AS timestamptz))
              AND (CAST(:cursorSeq AS bigint) IS NULL
                   OR m.seq < CAST(:cursorSeq AS bigint)
                   OR (m.seq = CAST(:cursorSeq AS bigint)
                       AND a.id > CAST(:cursorAttachmentId AS uuid)))
              AND a.mime_type NOT LIKE 'image/%'
              AND a.mime_type NOT LIKE 'video/%'
            ORDER BY m.seq DESC, a.id ASC
            """, nativeQuery = true)
    List<ArchiveAttachmentRow> findFilePage(@Param("conversationId") UUID conversationId,
                                            @Param("senderId") UUID senderId,
                                            @Param("fromTs") Instant fromTs,
                                            @Param("toTs") Instant toTs,
                                            @Param("cursorSeq") Long cursorSeq,
                                            @Param("cursorAttachmentId") UUID cursorAttachmentId,
                                            Pageable pageable);

    /**
     * One page of TEXT messages that contain at least one {@code http://} or
     * {@code https://} token. {@code maxSeq} is inclusive so a page that was
     * cut inside a multi-link message can resume at the same seq.
     */
    @Query(value = """
            SELECT m.id AS "messageId", m.seq AS "seq", m.sender_id AS "senderId",
                   m.created_at AS "createdAt", m.content AS "content"
            FROM messages m
            WHERE m.conversation_id = :conversationId
              AND m.deleted_at IS NULL
              AND m.type = 'TEXT'
              AND m.content IS NOT NULL
              AND m.content ~* 'https{0,1}://'
              AND (CAST(:senderId AS uuid) IS NULL OR m.sender_id = CAST(:senderId AS uuid))
              AND (CAST(:fromTs AS timestamptz) IS NULL
                   OR m.created_at >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs AS timestamptz) IS NULL
                   OR m.created_at <= CAST(:toTs AS timestamptz))
              AND (CAST(:maxSeq AS bigint) IS NULL OR m.seq <= CAST(:maxSeq AS bigint))
            ORDER BY m.seq DESC
            """, nativeQuery = true)
    List<ArchiveLinkRow> findLinkMessagePage(@Param("conversationId") UUID conversationId,
                                             @Param("senderId") UUID senderId,
                                             @Param("fromTs") Instant fromTs,
                                             @Param("toTs") Instant toTs,
                                             @Param("maxSeq") Long maxSeq,
                                             Pageable pageable);
}
