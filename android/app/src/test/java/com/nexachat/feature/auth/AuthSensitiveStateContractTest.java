package com.nexachat.feature.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

/** Guards against auth fragments manually serializing passwords or OTPs into saved state. */
public final class AuthSensitiveStateContractTest {

    private static final Pattern SENSITIVE_BUNDLE_WRITE = Pattern.compile(
            "(?is)\\b(?:out|outState)\\.put(?:String|CharSequence|Parcelable|Serializable)"
                    + "\\s*\\(\\s*[\"'][^\"']*(?:password|confirm|otp|code)[^\"']*[\"']");
    private static final Pattern SENSITIVE_BUNDLE_RESTORE = Pattern.compile(
            "(?is)\\b(?:state|savedInstanceState)\\.get(?:String|CharSequence|Parcelable|Serializable)"
                    + "\\s*\\(\\s*[\"'][^\"']*(?:password|confirm|otp|code)[^\"']*[\"']");

    @Test
    public void authFragmentsDoNotWriteOrRestoreSecretsThroughBundles() throws Exception {
        List<String> files = List.of(
                "feature/auth/LoginFragment.java",
                "feature/auth/RegisterFragment.java",
                "feature/auth/ForgotPasswordFragment.java",
                "feature/profile/RecoveryEmailOtpFragment.java",
                "feature/profile/DeleteAccountFragment.java");
        for (String relative : files) {
            String source = new String(
                    Files.readAllBytes(findSource(relative).toPath()), StandardCharsets.UTF_8);
            assertFalse(relative + " writes a secret to saved instance state",
                    SENSITIVE_BUNDLE_WRITE.matcher(source).find());
            assertFalse(relative + " restores a secret from saved instance state",
                    SENSITIVE_BUNDLE_RESTORE.matcher(source).find());
        }
    }

    @Test
    public void secretInputsDoNotSaveAndroidViewHierarchyState() throws Exception {
        String[][] secretInputs = {
            {"fragment_login.xml", "passwordInput"},
            {"fragment_register.xml", "passwordInput"},
            {"fragment_register.xml", "confirmPasswordInput"},
            {"fragment_forgot_password.xml", "forgotOtpInput"},
            {"fragment_forgot_password.xml", "forgotNewPasswordInput"},
            {"fragment_forgot_password.xml", "forgotConfirmPasswordInput"},
            {"fragment_recovery_email_otp.xml", "recoveryOtpInput"},
            {"fragment_delete_account.xml", "passwordInput"}
        };

        for (String[] secretInput : secretInputs) {
            String layout = secretInput[0];
            String viewId = secretInput[1];
            String xml = new String(
                    Files.readAllBytes(findLayout(layout).toPath()), StandardCharsets.UTF_8);
            Pattern element = Pattern.compile(
                    "(?is)<com\\.google\\.android\\.material\\.textfield\\.TextInputEditText\\b"
                            + "[^>]*android:id=\"@\\+id/" + Pattern.quote(viewId) + "\"[^>]*>");
            java.util.regex.Matcher matcher = element.matcher(xml);
            assertTrue(layout + " is missing secret input " + viewId, matcher.find());
            assertTrue(layout + "#" + viewId + " can be saved in the view hierarchy",
                    matcher.group().contains("android:saveEnabled=\"false\""));
        }

        String profile = new String(Files.readAllBytes(findSource(
                "feature/profile/ProfileFragment.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("dynamic password inputs can be saved in the view hierarchy",
                profile.contains("input.setSaveEnabled(false)"));
    }

    private static File findSource(String relative) {
        List<File> candidates = List.of(
                new File("src/main/java/com/nexachat/" + relative),
                new File("app/src/main/java/com/nexachat/" + relative),
                new File("android/app/src/main/java/com/nexachat/" + relative),
                new File("../app/src/main/java/com/nexachat/" + relative));
        return candidates.stream()
                .filter(File::isFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not locate auth source file " + relative));
    }

    private static File findLayout(String name) {
        List<File> candidates = List.of(
                new File("src/main/res/layout/" + name),
                new File("app/src/main/res/layout/" + name),
                new File("android/app/src/main/res/layout/" + name),
                new File("../app/src/main/res/layout/" + name));
        return candidates.stream()
                .filter(File::isFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not locate secret input layout " + name));
    }
}
