package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.feature.ask.AskCitation;
import com.nexachat.feature.ask.AskRequest;
import com.nexachat.feature.ask.AskResponse;
import com.nexachat.feature.ask.AskUsage;
import com.nexachat.summary.SummaryRequest;
import com.nexachat.summary.SummaryResponse;
import com.squareup.moshi.Json;
import com.squareup.moshi.Moshi;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Release hardening regression test:
 * Scans reflective DTO classes for fields lacking {@link Json} annotations,
 * proving that their serialization/deserialization relies on JVM field names,
 * and asserts that proguard-rules.pro retains keep rules for all reflective packages.
 */
public final class DtoProguardKeepContractTest {

    private static final List<Class<?>> SAMPLE_DTO_CLASSES = Arrays.asList(
            AttachmentDto.class,
            MessageDto.class,
            ConversationInboxDto.class,
            ContactDto.class,
            AskRequest.class,
            AskResponse.class,
            AskCitation.class,
            AskUsage.class,
            SummaryRequest.class,
            SummaryResponse.class
    );

    @Test
    public void dtoClassesContainUnannotatedFieldsRequiringNameRetention() {
        List<String> unannotatedFields = new ArrayList<>();
        for (Class<?> clazz : SAMPLE_DTO_CLASSES) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!field.isAnnotationPresent(Json.class)) {
                    unannotatedFields.add(clazz.getSimpleName() + "." + field.getName());
                }
            }
        }

        // Regression assertion: these reflective beans rely on field names surviving R8.
        assertFalse(
                "Expected reflective DTO fields without @Json annotation",
                unannotatedFields.isEmpty()
        );
        assertTrue(
                "AttachmentDto.storageKey must rely on reflective field retention",
                unannotatedFields.contains("AttachmentDto.storageKey")
        );
        assertTrue(
                "AskRequest.question must rely on reflective field retention",
                unannotatedFields.contains("AskRequest.question")
        );
        assertTrue(
                "AskResponse.answer must rely on reflective field retention",
                unannotatedFields.contains("AskResponse.answer")
        );
    }

    @Test
    public void proguardRulesKeepAllReflectiveDtoPackages() throws Exception {
        File rulesFile = locateFile("proguard-rules.pro");
        if (rulesFile == null) {
            rulesFile = locateFile("android/app/proguard-rules.pro");
        }
        assertNotNull("proguard-rules.pro must exist", rulesFile);
        String rulesContent = new String(Files.readAllBytes(rulesFile.toPath()));

        // Check required keep rules for DTO packages
        assertTrue(
                "Must keep com.nexachat.core.network.dto.**",
                rulesContent.contains("-keep class com.nexachat.core.network.dto.**")
        );
        assertTrue(
                "Must keep com.nexachat.core.network.stomp.dto.**",
                rulesContent.contains("-keep class com.nexachat.core.network.stomp.dto.**")
        );
        assertTrue(
                "Must keep com.nexachat.feature.ask DTO classes",
                rulesContent.contains("com.nexachat.feature.ask.AskRequest") &&
                rulesContent.contains("com.nexachat.feature.ask.AskResponse")
        );
        assertTrue(
                "Must keep com.nexachat.summary.**",
                rulesContent.contains("-keep class com.nexachat.summary.**")
        );
        assertTrue(
                "Must keep WorkManager ListenableWorker constructors",
                rulesContent.contains("androidx.work.ListenableWorker")
        );
        assertTrue(
                "Must keep RoomDatabase and entities/DAOs",
                rulesContent.contains("androidx.room.RoomDatabase")
        );
        assertTrue(
                "Must keep Glide AppGlideModule",
                rulesContent.contains("com.bumptech.glide.module.AppGlideModule")
        );
        assertTrue(
                "Must keep WebRTC classes",
                rulesContent.contains("org.webrtc.**")
        );
        assertTrue(
                "Must keep Firebase Messaging Service",
                rulesContent.contains("com.google.firebase.messaging.FirebaseMessagingService")
        );
    }

    @Test
    public void moshiSerializesReflectiveFieldsUsingExactFieldNames() throws Exception {
        Moshi moshi = new Moshi.Builder().build();

        // AskRequest serialization uses exact field name "question"
        AskRequest request = new AskRequest("What was discussed?", "en");
        String json = moshi.adapter(AskRequest.class).toJson(request);
        assertTrue("JSON must contain key 'question'", json.contains("\"question\":\"What was discussed?\""));
        assertTrue("JSON must contain key 'language'", json.contains("\"language\":\"en\""));

        // AskResponse deserialization maps from exact JSON keys
        String responseJson = "{\"answer\":\"The plan was accepted\",\"mode\":\"LEXICAL\"}";
        AskResponse response = moshi.adapter(AskResponse.class).fromJson(responseJson);
        assertNotNull(response);
        assertEquals("The plan was accepted", response.getAnswer());
        assertEquals("LEXICAL", response.getMode());
    }

    @Test
    public void networkSecurityConfigInMainHasNoCleartextDomains() throws Exception {
        File configFile = locateFile("src/main/res/xml/network_security_config.xml");
        if (configFile == null) {
            configFile = locateFile("android/app/src/main/res/xml/network_security_config.xml");
        }
        assertNotNull("network_security_config.xml must exist in main", configFile);
        String xmlContent = new String(Files.readAllBytes(configFile.toPath()));

        assertTrue(
                "Main network security config must have cleartextTrafficPermitted=false",
                xmlContent.contains("cleartextTrafficPermitted=\"false\"")
        );
        assertFalse(
                "Main network security config must NOT allow 10.0.2.2 cleartext in release",
                xmlContent.contains("10.0.2.2")
        );
        assertFalse(
                "Main network security config must NOT contain domain-config exemptions in release",
                xmlContent.contains("<domain-config")
        );
    }

    private static File locateFile(String relativePath) {
        File current = new File(".").getAbsoluteFile();
        while (current != null) {
            File candidate = new File(current, relativePath);
            if (candidate.isFile()) {
                return candidate;
            }
            current = current.getParentFile();
        }
        return null;
    }
}
