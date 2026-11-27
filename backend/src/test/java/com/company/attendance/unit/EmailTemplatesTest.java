package com.company.attendance.unit;

import com.company.attendance.service.EmailTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.company.attendance.service.EmailTemplates.LOGO_CID;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the mail actually says.
 *
 * <p>The assertions here are about the ways a template silently stops working.
 * A link that exists only inside an {@code href} is invisible to anyone whose
 * client shows the plain-text alternative — and that is precisely the person
 * who then cannot get into their account and has nothing to copy. A subject
 * that says "reset" to somebody who never had a password is a support call.
 *
 * <p>Each rendering is also written to {@code target/mail-samples/} so the
 * markup can be opened in a browser and looked at, which is the only way to
 * review mail markup honestly.
 */
class EmailTemplatesTest {

    private static final Path SAMPLES = Path.of("target", "mail-samples");

    private final EmailTemplates templates = new EmailTemplates();

    @Test
    @DisplayName("a new joiner's mail carries the link in both the HTML and the plain text")
    void setupMailCarriesTheLinkBothWays() throws Exception {
        String link = "https://attendance.shareindiainsurance.com/reset-password?token=abc123";
        var message = templates.passwordSetup("Asha", "S0107", link, "72 hours", false);

        save("01-new-joiner", message);

        assertThat(message.subject()).isEqualTo("Set your password for Attendance Pro");
        // Not "has been reset" — they never had a password to reset. The word
        // itself is unavoidable: it is in the URL path.
        assertThat(message.text()).doesNotContain("has been reset");

        for (String body : new String[]{message.html(), message.text()}) {
            assertThat(body).contains(link);
            assertThat(body).contains("Asha");
            assertThat(body).contains("S0107");
            assertThat(body).contains("72 hours");
        }
    }

    @Test
    @DisplayName("a reset says so, and still carries the link both ways")
    void resetMailSaysReset() throws Exception {
        String link = "https://attendance.shareindiainsurance.com/reset-password?token=def456";
        var message = templates.passwordSetup("Rohit", "S0143", link, "30 minutes", true);

        save("02-reset", message);

        assertThat(message.subject()).isEqualTo("Set a new password for Attendance Pro");
        assertThat(message.text()).contains("has been reset");
        assertThat(message.html()).contains(link);
        assertThat(message.text()).contains(link);
        assertThat(message.text()).contains("30 minutes");
    }

    @Test
    @DisplayName("an approval mail names who is waiting and links to the request")
    void approvalMail() throws Exception {
        var message = templates.approvalRequested("Vidhi", "Asha Rao needs your approval",
                "Casual leave, 14-15 December, 2 days",
                "https://attendance.shareindiainsurance.com/approvals/LV-2026-0012");

        save("03-approval", message);

        assertThat(message.subject()).isEqualTo("Asha Rao needs your approval");
        assertThat(message.text()).contains("Casual leave, 14-15 December, 2 days");
        assertThat(message.text()).contains("/approvals/LV-2026-0012");
    }

    @Test
    @DisplayName("an outcome mail states the decision in the subject")
    void outcomeMail() throws Exception {
        var message = templates.requestDecided("Asha", "Your leave was approved",
                "Casual leave, 14-15 December, approved by Vidhi Shah",
                "https://attendance.shareindiainsurance.com/leave/LV-2026-0012");

        save("04-outcome", message);

        // The subject is the whole message for anybody reading on a phone
        // notification, so it has to carry the decision, not just the topic.
        assertThat(message.subject()).isEqualTo("Your leave was approved");
        assertThat(message.text()).contains("approved by Vidhi Shah");
    }

    @Test
    @DisplayName("the reminder names the shift and says what to do instead")
    void punchInReminder() throws Exception {
        var message = templates.punchInReminder("Rohit", "9:30 AM",
                "https://attendance.shareindiainsurance.com/dashboard");

        save("05-punch-in-reminder", message);

        assertThat(message.subject()).isEqualTo("You have not punched in today");
        for (String body : new String[]{message.html(), message.text()}) {
            assertThat(body).contains("Rohit").contains("9:30 AM");
            // Somebody who is genuinely away must be told what to do, or the
            // mail reads as an accusation with no way to answer it.
            assertThat(body).contains("apply for leave");
        }
        // No warning, no manager, nothing about pay. See the template's note.
        assertThat(message.text()).doesNotContainIgnoringCase("salary");
        assertThat(message.text()).doesNotContainIgnoringCase("deduct");
    }

    @Test
    @DisplayName("every message carries the company's name and its mark")
    void everyMessageIsBranded() {
        var all = new EmailTemplates.Message[]{
                templates.passwordSetup("Asha", "S0107", "https://x/y", "72 hours", false),
                templates.approvalRequested("Vidhi", "Title", "Body", "https://x/y"),
                templates.requestDecided("Asha", "Title", "Body", "https://x/y"),
                templates.punchInReminder("Rohit", "9:30 AM", "https://x/y"),
        };

        for (EmailTemplates.Message message : all) {
            assertThat(message.html()).contains("Share India Insurance Brokers");
            // The plain-text copy has no shell, so it is the one that loses a
            // sign-off without anybody noticing.
            assertThat(message.text()).contains("Share India Insurance Brokers");

            // Referenced by content id, never by URL: a remote image is
            // blocked by default in Outlook and most webmail.
            assertThat(message.html()).contains("src=\"cid:" + LOGO_CID + "\"");
            assertThat(message.html()).doesNotContain("src=\"http");
        }
    }

    @Test
    @DisplayName("the mark the mail refers to is actually on the classpath")
    void theMarkIsShipped() {
        // The reference and the file are in different source trees, so one can
        // be moved without the other. The symptom would be every mail arriving
        // with a broken image, which no unit test on the markup alone catches.
        assertThat(EmailTemplatesTest.class.getResource("/mail/shareindia-mark.png"))
                .as("mail/shareindia-mark.png on the classpath")
                .isNotNull();
    }

    @Test
    @DisplayName("a missing body does not render the string 'null' at somebody")
    void handlesAMissingBody() {
        var message = templates.approvalRequested("Vidhi", "Something needs you", null,
                "https://example.com/x");

        assertThat(message.html()).doesNotContain("null");
        assertThat(message.text()).doesNotContain("null");
    }

    /**
     * Writes the rendering out, with the logo pointed at a file.
     *
     * <p>A browser does not resolve {@code cid:}, which is what the real
     * message uses, so a sample saved verbatim shows a broken image and the
     * reviewer concludes the mail is broken. The substitution is the one
     * difference between these files and what is sent.
     */
    private void save(String name, EmailTemplates.Message message) throws Exception {
        Files.createDirectories(SAMPLES);
        Files.copy(Path.of("src", "main", "resources", "mail", "shareindia-mark.png"),
                SAMPLES.resolve("shareindia-mark.png"), REPLACE_EXISTING);

        Files.writeString(SAMPLES.resolve(name + ".html"),
                message.html().replace("cid:" + LOGO_CID, "shareindia-mark.png"));
        Files.writeString(SAMPLES.resolve(name + ".txt"),
                "Subject: " + message.subject() + "\n\n" + message.text());
    }
}
