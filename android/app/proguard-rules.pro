# Retrofit discovers service methods from runtime annotations and generic types.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep interface com.nexachat.core.network.*Api { *; }
# This Retrofit API is nested under CallRepository, so it does not match the
# core.network package rule above.
-keep interface com.nexachat.call.core.CallRepository$Api { *; }
-keep interface com.nexachat.summary.*Api { *; }

# Moshi binds these beans by reflection (fields + no-arg constructor). R8 must
# keep the classes, members, and generic signatures.
-keep class com.nexachat.core.network.dto.** { *; }
-keep class com.nexachat.core.network.stomp.dto.** { *; }
-keep class com.nexachat.summary.AiSummaryConsent* { *; }
-keep class com.nexachat.summary.SummaryRequest { *; }
-keep class com.nexachat.summary.SummaryResponse { *; }
-keep class com.nexachat.summary.SummaryItem { *; }
-keep class com.nexachat.summary.SummaryActionItem { *; }
-keep class com.nexachat.summary.SummaryParticipant { *; }

# Room's runtime schema/DAO discovery depends on the entity and database types.
-keep class com.nexachat.core.database.** { *; }

# Hilt's application entry point is named by the Android manifest. Hilt modules
# and generated components use direct references and the library's consumer rules.
-keep class com.nexachat.NexaChatApp { *; }

# Stream WebRTC resolves native bridge classes by name at runtime.
-keep class org.webrtc.** { *; }
