# ==============================================================================
# NexaChat Release ProGuard / R8 Hardening Rules
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Attributes & Annotations
# ------------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable

# Support AndroidX @Keep annotation
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}

# ------------------------------------------------------------------------------
# 2. Moshi DTOs, Adapters, and Reflective JSON Beans
# Moshi binds these beans by reflection (fields + no-arg constructor).
# Unannotated fields match JSON keys by their exact JVM field names.
# R8 must preserve class names, fields, constructors, and generic signatures.
# ------------------------------------------------------------------------------
-keep class com.nexachat.core.network.dto.** { *; }
-keep class com.nexachat.core.network.stomp.dto.** { *; }
-keep class com.nexachat.core.network.DeviceTokenApi$* { *; }
-keep class com.nexachat.summary.** { *; }
-keep class com.nexachat.feature.ask.AskRequest { *; }
-keep class com.nexachat.feature.ask.AskResponse { *; }
-keep class com.nexachat.feature.ask.AskCitation { *; }
-keep class com.nexachat.feature.ask.AskUsage { *; }
# Feature wire beans are not in core.network.dto. R8 full mode otherwise
# deletes response types and the fields Moshi binds by name.
-keep class com.nexachat.feature.reminder.ReminderDto { *; }
-keep class com.nexachat.feature.reminder.ReminderRequest { *; }
-keep class com.nexachat.feature.poll.PollDto { *; }
-keep class com.nexachat.feature.poll.PollOptionDto { *; }
-keep class com.nexachat.feature.poll.PollPageDto { *; }
-keep class com.nexachat.feature.poll.PollChangesDto { *; }
-keep class com.nexachat.feature.poll.PollChangesDto$ChangeDto { *; }
-keep class com.nexachat.feature.poll.CreatePollRequest { *; }
-keep class com.nexachat.feature.poll.VoteRequest { *; }
-keep class com.nexachat.feature.label.LabelDto { *; }
-keep class com.nexachat.feature.label.LabelWriteRequest { *; }
-keep class com.nexachat.feature.invite.InviteLinkRequest { *; }
-keep class com.nexachat.feature.invite.InviteLinkResponse { *; }
-keep class com.nexachat.feature.invite.InviteApprovalRequest { *; }
-keep class com.nexachat.feature.invite.InvitePreviewResponse { *; }
-keep class com.nexachat.feature.invite.JoinResponse { *; }
-keep class com.nexachat.feature.invite.JoinRequestResponse { *; }
-keep class com.nexachat.feature.invite.JoinRequestResponse$UserSummary { *; }
-keep class com.nexachat.feature.archive.ArchivePage { *; }
-keep class com.nexachat.feature.archive.ArchivePage$Item { *; }
-keep class com.nexachat.feature.archive.ArchivePage$Attachment { *; }
-keep class com.nexachat.feature.archive.ArchivePage$Link { *; }
-keep class com.nexachat.call.core.CallFrameCodec$* { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# ------------------------------------------------------------------------------
# 3. Retrofit Interfaces
# Service methods are discovered dynamically at runtime via reflection
# on HTTP method annotations and parameter types.
# ------------------------------------------------------------------------------
-keep interface com.nexachat.core.network.*Api { *; }
-keep interface com.nexachat.call.core.CallRepository$Api { *; }
-keep interface com.nexachat.summary.*Api { *; }
-keep interface com.nexachat.feature.archive.ArchiveApi { *; }
-keep interface com.nexachat.feature.ask.AskApi { *; }
-keep interface com.nexachat.**.*Api { *; }
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ------------------------------------------------------------------------------
# 4. Room Database, Entities, and DAOs
# Room relies on reflective instantiation and schema type matching.
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.migration.Migration { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.nexachat.core.database.** { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------
# 5. Hilt / Dependency Injection
# Entry point interfaces accessed via EntryPointAccessors at runtime.
# ------------------------------------------------------------------------------
-keep class com.nexachat.NexaChatApp { *; }
-keep @dagger.hilt.EntryPoint interface * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.EarlyEntryPoint interface * { *; }

# ------------------------------------------------------------------------------
# 6. WorkManager Workers
# Workers are instantiated reflectively via WorkerFactory using (Context, WorkerParameters).
# ------------------------------------------------------------------------------
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.nexachat.data.OutboxSyncWorker { *; }
-keep class com.nexachat.notification.DeviceTokenSyncWorker { *; }

# ------------------------------------------------------------------------------
# 7. Media3 / ExoPlayer
# ExoPlayer dynamically instantiates renderers, extractors, and decoders.
# ------------------------------------------------------------------------------
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**

# ------------------------------------------------------------------------------
# 8. Glide Image Loader Modules
# Glide discovers AppGlideModule and GeneratedAppGlideModuleImpl via reflection.
# ------------------------------------------------------------------------------
-keep public class * extends com.bumptech.glide.module.AppGlideModule {
    public <init>(...);
}
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule {
    public <init>(...);
}
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep @com.bumptech.glide.annotation.GlideModule class * { *; }
-keep class com.nexachat.core.media.MediaGlideModule { *; }
-dontwarn com.bumptech.glide.**

# ------------------------------------------------------------------------------
# 9. Stream WebRTC Native / JNI Bridge
# Native WebRTC shared libraries bind directly to JNI entry points by class name.
# ------------------------------------------------------------------------------
-keep class org.webrtc.** { *; }
-keepclasseswithmembers class org.webrtc.** {
    native <methods>;
}

# ------------------------------------------------------------------------------
# 10. Firebase Messaging Service
# Instantiated reflectively by Google Play services framework.
# ------------------------------------------------------------------------------
-keep class com.nexachat.notification.NexaFirebaseMessagingService {
    public <init>();
}
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService {
    public <init>();
}

# ------------------------------------------------------------------------------
# 11. General Java/Android Invariants: Enums, Parcelable, JNI Methods
# ------------------------------------------------------------------------------
# Enum values() and valueOf(String) called reflectively
-keepclassmembers enum * {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Android stores Parcelable class names in parcel data, so keep class identity
# along with the CREATOR used to reconstruct each value.
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# General native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}
