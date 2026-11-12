package com.company.attendance.service;

import org.springframework.stereotype.Component;

/**
 * The wording and markup of the mail this application sends.
 *
 * <p>Hand-built strings rather than a template engine. There are four messages,
 * they share one shell, and adding Thymeleaf to render them would mean a
 * dependency, a resource path and a second place to look when the wording is
 * wrong. If this grows past a handful, move it.
 *
 * <p>Every message is built twice, as HTML and as plain text. The plain-text
 * copy is not a courtesy: a setup link that only exists inside an anchor tag is
 * unreachable for anybody whose client blocks HTML, and that is exactly the
 * person who then cannot sign in.
 *
 * <p>The HTML uses table layout and inline styles throughout. Mail clients are
 * not browsers — Outlook renders through Word, Gmail strips {@code <style>}
 * blocks, and flexbox is not available in either.
 */
@Component
public class EmailTemplates {

    private static final String ACCENT = "#00236f";

    /** A new joiner, or anyone whose password an administrator has reset. */
    public Message passwordSetup(String firstName, String employeeCode, String link,
                                 String validity, boolean isReset) {
        String subject = isReset
                ? "Set a new password for Attendance Pro"
                : "Set your password for Attendance Pro";

        String opening = isReset
                ? "Your Attendance Pro password has been reset, so your old one no longer works."
                : "Your Attendance Pro account is ready. Set a password to get started.";

        String text = """
                Hello %s,

                %s

                Set your password here:
                %s

                Your employee ID is %s — you will need it to sign in.

                This link works once and expires in %s. If it has expired, ask HR
                to send you another.

                If you were not expecting this, tell HR before using the link.
                """.formatted(firstName, opening, link, employeeCode, validity);

        String html = shell(
                isReset ? "Set a new password" : "Set your password",
                """
                <p style="margin:0 0 16px">Hello %s,</p>
                <p style="margin:0 0 16px">%s</p>
                %s
                <p style="margin:0 0 16px">Your employee ID is <strong>%s</strong> — you will need
                   it to sign in.</p>
                <p style="margin:0 0 16px;color:#5b6472;font-size:14px">
                   This link works once and expires in %s. If it has expired, ask HR to send
                   you another.</p>
                <p style="margin:0;color:#5b6472;font-size:14px">
                   If you were not expecting this, tell HR before using the link.</p>
                """.formatted(firstName, opening,
                        button(isReset ? "Set a new password" : "Set your password", link),
                        employeeCode, validity));

        return new Message(subject, html, text);
    }

    /** Something is waiting on this person to decide. */
    public Message approvalRequested(String firstName, String title, String body, String link) {
        String text = """
                Hello %s,

                %s

                %s

                Open it here:
                %s
                """.formatted(firstName, title, body == null ? "" : body, link);

        String html = shell("Waiting for your approval", """
                <p style="margin:0 0 16px">Hello %s,</p>
                <p style="margin:0 0 8px;font-weight:600">%s</p>
                <p style="margin:0 0 16px">%s</p>
                %s
                """.formatted(firstName, title, body == null ? "" : body,
                        button("Review the request", link)));

        return new Message(title, html, text);
    }

    /** This person's own request was decided. */
    public Message requestDecided(String firstName, String title, String body, String link) {
        String text = """
                Hello %s,

                %s

                %s

                See the detail here:
                %s
                """.formatted(firstName, title, body == null ? "" : body, link);

        String html = shell(title, """
                <p style="margin:0 0 16px">Hello %s,</p>
                <p style="margin:0 0 8px;font-weight:600">%s</p>
                <p style="margin:0 0 16px">%s</p>
                %s
                """.formatted(firstName, title, body == null ? "" : body,
                        button("Open Attendance Pro", link)));

        return new Message(title, html, text);
    }

    /**
     * A call-to-action that survives a mail client.
     *
     * <p>The raw URL is printed under the button because some clients render
     * neither the background colour nor the link reliably, and a person who
     * cannot click still has to be able to copy.
     */
    private String button(String label, String link) {
        return """
            <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 16px">
              <tr><td style="background:%s;border-radius:6px">
                <a href="%s" style="display:inline-block;padding:12px 22px;color:#ffffff;
                   font-weight:600;text-decoration:none">%s</a>
              </td></tr>
            </table>
            <p style="margin:0 0 16px;font-size:13px;color:#5b6472;word-break:break-all">
               Or paste this into your browser:<br>%s</p>
            """.formatted(ACCENT, link, label, link);
    }

    private String shell(String heading, String content) {
        return """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#f4f6fa;
                 font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                 color:#1b1f27;font-size:16px;line-height:1.5">
              <table role="presentation" cellpadding="0" cellspacing="0"
                     style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:10px">
                <tr><td style="padding:28px 32px 8px">
                  <p style="margin:0 0 20px;font-size:18px;font-weight:700;color:%s">
                     Attendance Pro</p>
                  <h1 style="margin:0 0 20px;font-size:21px;line-height:1.3">%s</h1>
                </td></tr>
                <tr><td style="padding:0 32px 28px">%s</td></tr>
              </table>
              <p style="max-width:560px;margin:16px auto 0;font-size:12px;color:#8a919e;
                 text-align:center">
                 Sent by Attendance Pro. Please do not reply to this message.</p>
            </body></html>
            """.formatted(ACCENT, heading, content);
    }

    /** A rendered message, ready for {@link EmailService#send}. */
    public record Message(String subject, String html, String text) {
    }
}
