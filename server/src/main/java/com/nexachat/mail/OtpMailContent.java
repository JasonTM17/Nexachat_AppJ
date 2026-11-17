package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;

import java.util.Locale;

/**
 * Subject, plain text and HTML of a security-code message, written in one language.
 * The message carries the code and nothing else to act on: no links, no images.
 */
record OtpMailContent(String subject, String text, String html) {

    static final int EXPIRY_MINUTES = 10;

    private static final String PLUM = "#733D66";
    private static final String PLUM_DARK = "#592850";
    private static final String PAGE = "#FFF7FC";
    private static final String CODE_BOX = "#F8EAF5";
    private static final String BORDER = "#EADCE7";
    private static final String INK = "#1D1B1E";
    private static final String MUTED = "#6B646A";
    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";
    private static final String MONO = "'SF Mono',SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";

    /** Wording for one purpose in one language. */
    private record Copy(String subject, String heading, String intro, String ignore) {
    }

    /** Wording shared by every purpose in one language. */
    private record Shared(String lang, String codeLabel, String expires, String neverShare,
                          String footer, String preheader) {
    }

    static OtpMailContent forCode(OtpPurpose purpose, String code, Locale locale) {
        boolean vietnamese = locale != null && "vi".equalsIgnoreCase(locale.getLanguage());
        Copy copy = vietnamese ? vietnamese(purpose) : english(purpose);
        Shared shared = vietnamese ? VIETNAMESE : ENGLISH;
        String preheader = shared.preheader().formatted(code, EXPIRY_MINUTES);
        String expires = shared.expires().formatted(EXPIRY_MINUTES);
        return new OtpMailContent(copy.subject(),
                text(copy, shared, code, expires),
                html(copy, shared, code, expires, preheader));
    }

    private static final Shared ENGLISH = new Shared("en", "Your code",
            "It expires in %d minutes.",
            "Never share this code. NexaChat will never ask you for it.",
            "This message was sent automatically, please do not reply.",
            "Your code is %s. It expires in %d minutes.");

    private static final Shared VIETNAMESE = new Shared("vi", "Mã của bạn",
            "Mã có hiệu lực trong %d phút.",
            "Không chia sẻ mã này với bất kỳ ai. NexaChat sẽ không bao giờ hỏi mã của bạn.",
            "Email này được gửi tự động, vui lòng không trả lời.",
            "Mã của bạn là %s. Mã có hiệu lực trong %d phút.");

    private static Copy english(OtpPurpose purpose) {
        return switch (purpose) {
            case REGISTER_EMAIL -> new Copy("Your NexaChat sign-up code",
                    "Confirm your email",
                    "Enter this code in NexaChat to finish creating your account.",
                    "If you did not try to create an account, you can ignore this email.");
            case VERIFY_EMAIL -> new Copy("Verify your NexaChat recovery email",
                    "Verify your recovery email",
                    "Enter this code in NexaChat to use this address as your recovery email.",
                    "If you did not ask for this, you can ignore this email.");
            case RESET_PASSWORD -> new Copy("Your NexaChat password reset code",
                    "Reset your password",
                    "Enter this code in NexaChat to choose a new password.",
                    "If you did not ask to reset your password, you can ignore this email. Your password stays the same.");
            case DELETE_ACCOUNT -> new Copy("Confirm deleting your NexaChat account",
                    "Confirm account deletion",
                    "Enter this code in NexaChat to permanently delete your account and its data.",
                    "If you did not ask to delete your account, do not share this code and change your password.");
        };
    }

    private static Copy vietnamese(OtpPurpose purpose) {
        return switch (purpose) {
            case REGISTER_EMAIL -> new Copy("Mã đăng ký NexaChat của bạn",
                    "Xác nhận email của bạn",
                    "Nhập mã này trong NexaChat để hoàn tất việc tạo tài khoản.",
                    "Nếu bạn không tạo tài khoản, hãy bỏ qua email này.");
            case VERIFY_EMAIL -> new Copy("Xác minh email khôi phục NexaChat",
                    "Xác minh email khôi phục",
                    "Nhập mã này trong NexaChat để dùng địa chỉ này làm email khôi phục.",
                    "Nếu bạn không yêu cầu, hãy bỏ qua email này.");
            case RESET_PASSWORD -> new Copy("Mã đặt lại mật khẩu NexaChat",
                    "Đặt lại mật khẩu",
                    "Nhập mã này trong NexaChat để chọn mật khẩu mới.",
                    "Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Mật khẩu của bạn vẫn giữ nguyên.");
            case DELETE_ACCOUNT -> new Copy("Xác nhận xóa tài khoản NexaChat",
                    "Xác nhận xóa tài khoản",
                    "Nhập mã này trong NexaChat để xóa vĩnh viễn tài khoản và dữ liệu của bạn.",
                    "Nếu bạn không yêu cầu xóa tài khoản, đừng chia sẻ mã này và hãy đổi mật khẩu.");
        };
    }

    private static String text(Copy copy, Shared shared, String code, String expires) {
        return "NexaChat\n\n"
                + copy.heading() + "\n"
                + copy.intro() + "\n\n"
                + shared.codeLabel() + ": " + code + "\n"
                + expires + "\n\n"
                + shared.neverShare() + "\n"
                + copy.ignore() + "\n\n"
                + "--\n" + shared.footer();
    }

    private static String html(Copy copy, Shared shared, String code, String expires, String preheader) {
        String safeCode = escape(code);
        return "<!doctype html>"
                + "<html lang=\"" + shared.lang() + "\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\">"
                + "<meta name=\"supported-color-schemes\" content=\"light\">"
                + "<title>" + escape(copy.subject()) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:" + PAGE + ";\">"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;"
                + "mso-hide:all;\">" + escape(preheader) + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"background:" + PAGE + ";\"><tr><td align=\"center\" style=\"padding:32px 16px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"max-width:480px;\">"
                + "<tr><td style=\"padding:0 0 16px 8px;font-family:" + FONT + ";font-size:20px;font-weight:700;"
                + "color:" + PLUM + ";\">NexaChat</td></tr>"
                + "<tr><td style=\"background:#FFFFFF;border:1px solid " + BORDER + ";border-radius:24px;"
                + "padding:32px 28px;font-family:" + FONT + ";color:" + INK + ";\">"
                + "<h1 style=\"margin:0 0 12px;font-size:22px;line-height:28px;font-weight:600;color:" + INK + ";\">"
                + escape(copy.heading()) + "</h1>"
                + "<p style=\"margin:0;font-size:16px;line-height:24px;color:" + INK + ";\">"
                + escape(copy.intro()) + "</p>"
                + "<div style=\"margin:24px 0 8px;padding:18px 8px;background:" + CODE_BOX + ";border-radius:16px;"
                + "text-align:center;font-family:" + MONO + ";font-size:34px;line-height:40px;font-weight:700;"
                + "letter-spacing:8px;color:" + PLUM_DARK + ";\">" + safeCode + "</div>"
                + "<p style=\"margin:0 0 24px;text-align:center;font-size:14px;line-height:20px;color:" + MUTED + ";\">"
                + escape(expires) + "</p>"
                + "<p style=\"margin:0 0 8px;font-size:14px;line-height:20px;color:" + INK + ";\">"
                + escape(shared.neverShare()) + "</p>"
                + "<p style=\"margin:0;font-size:14px;line-height:20px;color:" + MUTED + ";\">"
                + escape(copy.ignore()) + "</p>"
                + "</td></tr>"
                + "<tr><td style=\"padding:16px 8px 0;font-family:" + FONT + ";font-size:12px;line-height:18px;"
                + "color:" + MUTED + ";text-align:center;\">" + escape(shared.footer()) + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
