package com.company.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this application addresses the mail it sends.
 *
 * <p>Separate from Spring's own {@code spring.mail.*}, which says how to reach
 * the SMTP server. These are the decisions that belong to the product: who the
 * mail appears to come from, where its links point, and whether it is sent at
 * all.
 *
 * @param enabled   false turns delivery off and logs what would have been sent.
 *                  The default for development, where there is no reason for a
 *                  test run to put real mail in a real person's inbox. Also the
 *                  honest setting for a deployment whose links do not yet
 *                  resolve: a setup mail that arrives before the site is
 *                  reachable spends the one-time token on a dead link.
 * @param from      the envelope sender. Must be a mailbox the SMTP server will
 *                  accept mail from, or the server rejects it as a relay — on
 *                  shared hosting that generally means the authenticated
 *                  account itself, not an alias of it.
 * @param fromName  the display name beside that address.
 * @param baseUrl   the public origin links are built from, with no trailing
 *                  slash. Mail cannot use a relative path, and getting this
 *                  wrong produces mail that looks right and whose every link is
 *                  broken — so it is validated at startup rather than trusted.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String fromName,
        String baseUrl
) {

    /** The address as a mail client should show it: {@code Name <box@example.com>}. */
    public String fromHeader() {
        return fromName == null || fromName.isBlank()
                ? from
                : fromName + " <" + from + ">";
    }

    /** An absolute link to a path within the application. */
    public String link(String path) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
