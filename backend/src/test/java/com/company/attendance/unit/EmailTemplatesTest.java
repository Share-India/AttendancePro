package com.company.attendance.unit;

import com.company.attendance.service.EmailTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
    @DisplayName("a missing body does not render the string 'null' at somebody")
    void handlesAMissingBody() {
        var message = templates.approvalRequested("Vidhi", "Something needs you", null,
                "https://example.com/x");

        assertThat(message.html()).doesNotContain("null");
        assertThat(message.text()).doesNotContain("null");
    }

    private void save(String name, EmailTemplates.Message message) throws Exception {
        Files.createDirectories(SAMPLES);
        Files.writeString(SAMPLES.resolve(name + ".html"), message.html());
        Files.writeString(SAMPLES.resolve(name + ".txt"),
                "Subject: " + message.subject() + "\n\n" + message.text());
    }
}
