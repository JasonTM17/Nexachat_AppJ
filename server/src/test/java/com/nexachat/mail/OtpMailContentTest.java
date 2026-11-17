package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class OtpMailContentTest {

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    @Test
    void everyPurposeCarriesTheCodeInSubjectFreeTextAndHtmlInBothLanguages() {
        for (OtpPurpose purpose : OtpPurpose.values()) {
            for (Locale locale : new Locale[] {Locale.ENGLISH, VIETNAMESE}) {
                OtpMailContent content = OtpMailContent.forCode(purpose, "482916", locale);

                assertThat(content.subject()).isNotBlank().doesNotContain("482916");
                assertThat(content.text()).contains("482916");
                assertThat(content.html()).contains(">482916<").startsWith("<!doctype html>");
                assertThat(content.text()).doesNotContain("http");
                assertThat(content.html()).doesNotContain("http", "<a ", "<img");
            }
        }
    }

    @Test
    void aMessageIsWrittenInOneLanguageOnly() {
        OtpMailContent english = OtpMailContent.forCode(OtpPurpose.RESET_PASSWORD, "482916", Locale.ENGLISH);
        OtpMailContent vietnamese = OtpMailContent.forCode(OtpPurpose.RESET_PASSWORD, "482916", VIETNAMESE);

        assertThat(english.subject()).isEqualTo("Your NexaChat password reset code");
        assertThat(english.text()).contains("10 minutes").doesNotContain("phút", "Mã");
        assertThat(english.html()).contains("lang=\"en\"").doesNotContain("phút");
        assertThat(vietnamese.subject()).isEqualTo("Mã đặt lại mật khẩu NexaChat");
        assertThat(vietnamese.text()).contains("10 phút").doesNotContain("minutes", "Never share");
        assertThat(vietnamese.html()).contains("lang=\"vi\"").doesNotContain("minutes");
    }

    @Test
    void anUnknownOrMissingLanguageFallsBackToEnglish() {
        assertThat(OtpMailContent.forCode(OtpPurpose.REGISTER_EMAIL, "111222", null).subject())
                .isEqualTo("Your NexaChat sign-up code");
        assertThat(OtpMailContent.forCode(OtpPurpose.REGISTER_EMAIL, "111222", Locale.FRENCH).subject())
                .isEqualTo("Your NexaChat sign-up code");
    }

    @Test
    void deletionMessageWarnsNotToShareTheCode() {
        OtpMailContent english = OtpMailContent.forCode(OtpPurpose.DELETE_ACCOUNT, "482916", Locale.ENGLISH);

        assertThat(english.text()).contains("permanently delete", "Never share this code");
    }

    @Test
    void htmlEscapesWhateverItIsGiven() {
        OtpMailContent content = OtpMailContent.forCode(OtpPurpose.REGISTER_EMAIL, "<b>&", Locale.ENGLISH);

        assertThat(content.html()).contains("&lt;b&gt;&amp;").doesNotContain("<b>&<");
    }
}
