package com.company.attendance.service;

import org.springframework.stereotype.Component;

/**
 * The wording and markup of the mail this application sends.
 *
 * <p>Hand-built strings rather than a template engine. There are five messages,
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

    /** Who the mail is from, as a person reads it rather than as an address. */
    private static final String COMPANY = "Share India Insurance Brokers";

    /**
     * The content id the corporate mark is referenced by.
     *
     * <p>A {@code cid:} reference rather than a URL on purpose. Outlook and
     * most webmail block remote images from a sender who is not already
     * trusted, and a logo that is blocked by default is not branding — it is
     * a grey box with a broken-image icon in it. The bytes travel with the
     * message instead: {@link EmailService} attaches them, which is why this
     * constant is not private.
     */
    public static final String LOGO_CID = "shareindia-mark";

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

        return message(subject, html, text);
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

        return message(title, html, text);
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

        return message(title, html, text);
    }

    /**
     * Nobody has punched in for this person and nothing excuses it.
     *
     * <p>The wording assumes forgetfulness rather than absence, because that
     * is what it usually is and because the system cannot tell the two apart.
     * It is sent while the day can still be fixed by punching in, so it asks
     * for that and nothing else -- no warning, no mention of pay, and no copy
     * to a manager. Somebody genuinely away has a manager who knows already.
     */
    public Message punchInReminder(String firstName, String shiftStart, String link) {
        String subject = "You have not punched in today";

        String text = """
                Hello %s,

                There is no punch-in recorded against your attendance today.
                Your shift started at %s.

                If you are at work, please punch in now -- the time is taken
                from when you punch, so a late punch shortens the day it
                records.

                If you are away today, apply for leave or ask your manager to
                correct the day for you.

                Punch in here:
                %s
                """.formatted(firstName, shiftStart, link);

        String html = shell(subject, """
                <p style="margin:0 0 16px">Hello %s,</p>
                <p style="margin:0 0 16px">There is no punch-in recorded against your
                   attendance today. Your shift started at
                   <strong>%s</strong>.</p>
                <p style="margin:0 0 16px">If you are at work, please punch in now &mdash;
                   the time is taken from when you punch, so a late punch shortens the
                   day it records.</p>
                %s
                <p style="margin:0 0 16px;font-size:14px;color:#5b6472">If you are away
                   today, apply for leave or ask your manager to correct the day for
                   you.</p>
                """.formatted(firstName, shiftStart, button("Punch in", link)));

        return message(subject, html, text);
    }

    /**
     * Every message, with the sender's own sign-off added to the plain text.
     *
     * <p>The HTML gets its footer from {@link #shell}; the plain-text copy has
     * no shell, so without this it would arrive as an unsigned block of text
     * from an address nobody recognises. The trimming matters: each body is a
     * text block ending in a newline, and two separators' worth of blank line
     * reads as a formatting mistake.
     */
    private Message message(String subject, String html, String text) {
        String signed = text.stripTrailing() + """


                --
                Attendance Pro — %s
                Sent automatically. Please do not reply to this message.
                """.formatted(COMPANY);

        return new Message(subject, html, signed);
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
                <tr><td style="padding:26px 32px 8px">
                  <!--
                    The mark and the name are two cells of one table, not an
                    inline-block pair. Outlook renders through Word, which
                    drops vertical-align on anything that is not a table cell,
                    and the logo would sit on the text's baseline instead of
                    beside it.
                  -->
                  <table role="presentation" cellpadding="0" cellspacing="0"
                         style="margin:0 0 22px">
                    <tr>
                      <td style="padding-right:12px;vertical-align:middle">
                        <img src="cid:%s" width="40" height="40"
                             alt="Share India"
                             style="display:block;width:40px;height:40px;border:0">
                      </td>
                      <td style="vertical-align:middle">
                        <div style="font-size:16px;font-weight:700;line-height:1.25;color:%s">
                           %s</div>
                        <div style="font-size:11px;letter-spacing:.08em;color:#8a919e;
                             text-transform:uppercase">Attendance Pro</div>
                      </td>
                    </tr>
                  </table>
                  <h1 style="margin:0 0 20px;font-size:21px;line-height:1.3">%s</h1>
                </td></tr>
                <tr><td style="padding:0 32px 28px">%s</td></tr>
              </table>
              <p style="max-width:560px;margin:16px auto 0;font-size:12px;color:#8a919e;
                 text-align:center">
                 Sent by Attendance Pro for %s.<br>
                 Please do not reply to this message.</p>
            </body></html>
            """.formatted(LOGO_CID, ACCENT, COMPANY, heading, content, COMPANY);
    }

    /** A rendered message, ready for {@link EmailService#send}. */
    public record Message(String subject, String html, String text) {
    }
}
