package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;

record OtpMailContent(String subject, String text, String html) {

    static OtpMailContent forCode(OtpPurpose purpose, String code) {
        String subject = switch (purpose) {
            case VERIFY_EMAIL -> "NexaChat recovery email verification / Xác minh email khôi phục NexaChat";
            case DELETE_ACCOUNT -> "NexaChat account deletion code / Mã xóa tài khoản NexaChat";
            case RESET_PASSWORD -> "NexaChat password reset code / Mã đặt lại mật khẩu NexaChat";
        };
        String englishAction = switch (purpose) {
            case VERIFY_EMAIL -> "verify your recovery email";
            case DELETE_ACCOUNT -> "delete your NexaChat account";
            case RESET_PASSWORD -> "reset your NexaChat password";
        };
        String vietnameseAction = switch (purpose) {
            case VERIFY_EMAIL -> "xác minh email khôi phục";
            case DELETE_ACCOUNT -> "xóa tài khoản NexaChat";
            case RESET_PASSWORD -> "đặt lại mật khẩu NexaChat";
        };
        String text = "NexaChat security code: " + code + "\n"
                + "Use this code to " + englishAction + ". It expires in 10 minutes.\n\n"
                + "Mã bảo mật NexaChat: " + code + "\n"
                + "Dùng mã này để " + vietnameseAction + ". Mã hết hạn sau 10 phút.\n\n"
                + (purpose == OtpPurpose.RESET_PASSWORD
                ? "If you did not request a password reset, ignore this email.\n"
                    + "Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này."
                : "If you did not request this, ignore this email.\n"
                    + "Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email này.");
        String html = "<p>NexaChat security code: <strong>" + code + "</strong></p>"
                + "<p>Use this code to " + englishAction + ". It expires in 10 minutes.</p>"
                + "<hr><p>Mã bảo mật NexaChat: <strong>" + code + "</strong></p>"
                + "<p>Dùng mã này để " + vietnameseAction + ". Mã hết hạn sau 10 phút.</p>"
                + "<p>If you did not request this, ignore this email."
                + "<br>Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email này.</p>";
        return new OtpMailContent(subject, text, html);
    }
}
