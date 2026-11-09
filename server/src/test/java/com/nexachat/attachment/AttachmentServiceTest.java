package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageAttachment;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.dto.AttachmentResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A0000000D49484452");

    @Mock
    private MessageAttachmentRepository attachmentRepository;
    @Mock
    private AttachmentStorageKeyLookupRepository storageKeyLookupRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private UserRepository userRepository;

    private AttachmentService attachmentService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        attachmentService = new AttachmentService(
                attachmentRepository, storageKeyLookupRepository,
                memberRepository, storageService, userRepository);
        lenient().when(storageService.resolveUrl(anyString()))
                .thenAnswer(i -> "/api/v1/files/" + i.getArgument(0));
        lenient().when(userRepository.findById(ownerId)).thenReturn(Optional.of(user(ownerId)));
    }

    private static StorageService.StreamHandle handle(byte[] content) {
        return new StorageService.StreamHandle(new ByteArrayInputStream(content), content.length);
    }

    private static User user(UUID id) {
        User user = User.create("+84901112233", "hash", "User");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private MessageAttachment attachment(UUID ownerId, String storageKey, boolean bound) {
        MessageAttachment attachment = MessageAttachment.upload(
                user(ownerId), storageKey, "photo.png", "image/png", 16L, null, null, null, null);
        ReflectionTestUtils.setField(attachment, "id", UUID.randomUUID());
        if (bound) {
            attachment.bindTo(messageIn(UUID.randomUUID()));
        }
        return attachment;
    }

    private Message messageIn(UUID conversationId) {
        Message message;
        try {
            var ctor = Message.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            message = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        Conversation conversation = Conversation.direct(user(ownerId), "a:b");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        ReflectionTestUtils.setField(message, "conversation", conversation);
        return message;
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ upload

    @Test
    void upload_happy_persistsUnboundOwnedRow() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_BYTES);
        when(storageService.store(eq("attachments"), eq("photo.png"), eq("image/png"), eq(PNG_BYTES)))
                .thenReturn(new StorageService.StoredFile("attachments/x.png", PNG_BYTES.length, "image/png"));

        AttachmentResponse response = attachmentService.upload(ownerId, file, 320, 200, null, null);

        ArgumentCaptor<MessageAttachment> saved = ArgumentCaptor.forClass(MessageAttachment.class);
        verify(attachmentRepository).save(saved.capture());
        MessageAttachment row = saved.getValue();
        assertThat(row.getMessage()).isNull(); // unbound until a send claims it
        assertThat(row.getOwner().getId()).isEqualTo(ownerId);
        assertThat(row.getStorageKey()).isEqualTo("attachments/x.png");
        assertThat(row.getMimeType()).isEqualTo("image/png");
        assertThat(row.getWidth()).isEqualTo(320);
        assertThat(row.getHeight()).isEqualTo(200);

        assertThat(response.url()).isEqualTo("/api/v1/files/attachments/x.png");
        assertThat(response.originalName()).isEqualTo("photo.png");
        assertThat(response.mimeType()).isEqualTo("image/png");
        assertThat(response.sizeBytes()).isEqualTo(PNG_BYTES.length);
    }

    @Test
    void upload_wrongMagic_propagatesMimeNotAllowed() {
        byte[] exeDisguisedAsPng = hex("4D5A90000300000004000000");
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", exeDisguisedAsPng);
        when(storageService.store(anyString(), anyString(), anyString(), any()))
                .thenThrow(ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, file, null, null, null, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
        assertThat(ex.getHttpStatus()).isEqualTo(415);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void upload_oversize_throwsFileTooLargeBeforeStore() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(AttachmentService.MAX_FILE_SIZE_BYTES + 1);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, file, null, null, null, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_TOO_LARGE);
        assertThat(ex.getHttpStatus()).isEqualTo(413);
        verify(file, never()).getBytes();
        verify(storageService, never()).store(any(), any(), any(), any());
    }

    @Test
    void upload_missingFile_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, null, null, null, null, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void upload_traversalFilename_sanitizedToBaseName() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/evil.png", "image/png", PNG_BYTES);
        when(storageService.store(anyString(), anyString(), anyString(), any()))
                .thenReturn(new StorageService.StoredFile("attachments/x.png", PNG_BYTES.length, "image/png"));

        AttachmentResponse response = attachmentService.upload(ownerId, file, null, null, null, null);

        // The client name survives only as display metadata — never as a path.
        assertThat(response.originalName()).isEqualTo("evil.png");
    }

    @Test
    void upload_blurHash_persistedAndReturned() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_BYTES);
        when(storageService.store(eq("attachments"), eq("photo.png"), eq("image/png"), eq(PNG_BYTES)))
                .thenReturn(new StorageService.StoredFile("attachments/x.png", PNG_BYTES.length, "image/png"));
        String blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj";

        AttachmentResponse response = attachmentService.upload(
                ownerId, file, null, null, null, blurHash);

        ArgumentCaptor<MessageAttachment> saved = ArgumentCaptor.forClass(MessageAttachment.class);
        verify(attachmentRepository).save(saved.capture());
        assertThat(saved.getValue().getBlurHash()).isEqualTo(blurHash);
        assertThat(response.blurHash()).isEqualTo(blurHash);
    }

    @Test
    void upload_blurHashAbsent_isNull() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_BYTES);
        when(storageService.store(eq("attachments"), eq("photo.png"), eq("image/png"), eq(PNG_BYTES)))
                .thenReturn(new StorageService.StoredFile("attachments/x.png", PNG_BYTES.length, "image/png"));

        AttachmentResponse response = attachmentService.upload(
                ownerId, file, null, null, null, null);

        assertThat(response.blurHash()).isNull();
    }

    @Test
    void upload_invalidBlurHash_isRejectedBeforeStore() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_BYTES);

        for (String bad : new String[] {
                "ABCDE", "A".repeat(65), "bad!hash", "has space", "has/slash"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> attachmentService.upload(ownerId, file, null, null, null, bad));
            assertThat(ex.getCode()).as("blurHash %s", bad).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
            assertThat(ex.getHttpStatus()).isEqualTo(400);
        }
        verify(storageService, never()).store(any(), any(), any(), any());
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void upload_blurHashOnNonImage_isRejectedBeforeStore() {
        byte[] pdfBytes = hex("255044462D312E340A25");
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", pdfBytes);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(
                        ownerId, file, null, null, null, "LEHV6nWB2yk8pyo0adR*.7kCMdnj"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(storageService, never()).store(any(), any(), any(), any());
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void upload_negativeDimension_throwsValidation() {
        MockMultipartFile file = new MockMultipartFile("file", "p.png", "image/png", PNG_BYTES);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.upload(ownerId, file, -1, null, null, null));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(storageService, never()).store(any(), any(), any(), any());
    }

    // ------------------------------------------------------------- sanitizeName

    @Test
    void sanitizeOriginalName_stripsPathAndControlChars() {
        assertThat(AttachmentService.sanitizeOriginalName("../../etc/evil.png")).isEqualTo("evil.png");
        assertThat(AttachmentService.sanitizeOriginalName("..\\..\\evil.png")).isEqualTo("evil.png");
        assertThat(AttachmentService.sanitizeOriginalName("a\rb\nc.png")).isEqualTo("abc.png");
        assertThat(AttachmentService.sanitizeOriginalName("quote\"semi;.png")).isEqualTo("quotesemi.png");
        assertThat(AttachmentService.sanitizeOriginalName("   ")).isEqualTo("file");
        assertThat(AttachmentService.sanitizeOriginalName(null)).isEqualTo("file");
    }

    // ---------------------------------------------------------------- download

    @Test
    void loadContent_ownerOfUnboundUpload_allowed() throws Exception {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", false);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(storageService.open("attachments/x.png")).thenReturn(handle(PNG_BYTES));

        try (AttachmentService.AttachmentContent content =
                     attachmentService.loadContent(attachment.getId(), ownerId)) {
            assertThat(content.content().content().readAllBytes()).containsExactly(PNG_BYTES);
            assertThat(content.attachment().getMimeType()).isEqualTo("image/png");
        }
    }

    @Test
    void loadContent_unboundUpload_nonOwner_forbidden() {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", false);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.loadContent(attachment.getId(), requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(403);
    }

    @Test
    void loadContent_memberOfBoundConversation_allowed() throws Exception {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", true);
        UUID conversationId = attachment.getMessage().getConversation().getId();
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, requesterId))
                .thenReturn(true);
        when(storageService.open("attachments/x.png")).thenReturn(handle(PNG_BYTES));

        try (AttachmentService.AttachmentContent content =
                     attachmentService.loadContent(attachment.getId(), requesterId)) {
            assertThat(content.content().content().readAllBytes()).containsExactly(PNG_BYTES);
        }
    }

    @Test
    void loadContent_nonMemberOfBoundConversation_forbidden() {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", true);
        UUID conversationId = attachment.getMessage().getConversation().getId();
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, requesterId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.loadContent(attachment.getId(), requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        verify(storageService, never()).open(anyString());
    }

    @Test
    void loadContent_unknownId_throwsAttachmentNotFound() {
        UUID missing = UUID.randomUUID();
        when(attachmentRepository.findById(missing)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.loadContent(missing, requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    // ----------------------------------------------- storage-key authZ (files path)

    @Test
    void assertStorageKeyReadable_nonAttachmentKey_passesUnchecked() {
        when(attachmentRepository.findByStorageKey("avatars/me.jpg")).thenReturn(Optional.empty());

        // Avatars have no membership concept — any authenticated user passes.
        attachmentService.assertStorageKeyReadable("avatars/me.jpg", requesterId);
    }

    @Test
    void assertStorageKeyReadable_rejectsCaseVariantOfCanonicalAttachmentKey() {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", true);
        when(attachmentRepository.findByStorageKey("ATTACHMENTS/X.PNG"))
                .thenReturn(Optional.of(attachment));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.assertStorageKeyReadable("ATTACHMENTS/X.PNG", requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        verify(memberRepository, never()).existsByConversation_IdAndUser_Id(any(), any());
    }

    @Test
    void assertStorageKeyReadable_caseInsensitiveLookupRejectsWhenExactLookupMisses() {
        when(attachmentRepository.findByStorageKey("ATTACHMENTS/X.PNG")).thenReturn(Optional.empty());
        when(storageKeyLookupRepository.existsByStorageKeyIgnoreCase("ATTACHMENTS/X.PNG"))
                .thenReturn(true);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.assertStorageKeyReadable("ATTACHMENTS/X.PNG", requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.ATTACHMENT_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        verify(memberRepository, never()).existsByConversation_IdAndUser_Id(any(), any());
    }

    @Test
    void assertStorageKeyReadable_attachmentKey_appliesOwnerMemberRule() {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", true);
        UUID conversationId = attachment.getMessage().getConversation().getId();
        when(attachmentRepository.findByStorageKey("attachments/x.png"))
                .thenReturn(Optional.of(attachment));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, requesterId))
                .thenReturn(false);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.assertStorageKeyReadable("attachments/x.png", requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void assertStorageKeyReadable_unboundAttachment_nonOwner_forbidden() {
        // An unbound upload is owner-only on the raw files path too — the
        // /attachments/{id}/content rule and the /files/** rule must agree.
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", false);
        when(attachmentRepository.findByStorageKey("attachments/x.png"))
                .thenReturn(Optional.of(attachment));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> attachmentService.assertStorageKeyReadable("attachments/x.png", requesterId));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        assertThat(ex.getHttpStatus()).isEqualTo(403);
        // No bound conversation → the member check is never even consulted.
        verify(memberRepository, never()).existsByConversation_IdAndUser_Id(any(), any());
    }

    @Test
    void assertStorageKeyReadable_unboundAttachment_owner_passes() {
        MessageAttachment attachment = attachment(ownerId, "attachments/x.png", false);
        when(attachmentRepository.findByStorageKey("attachments/x.png"))
                .thenReturn(Optional.of(attachment));

        attachmentService.assertStorageKeyReadable("attachments/x.png", ownerId);

        verify(memberRepository, never()).existsByConversation_IdAndUser_Id(any(), any());
    }
}
