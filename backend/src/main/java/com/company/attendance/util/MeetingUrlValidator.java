package com.company.attendance.util;

import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.MeetingProvider;
import com.company.attendance.exception.BusinessException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks a meeting link before it is stored, and works out which platform it
 * points at.
 *
 * <p>This matters more than it looks. The join endpoint answers with a 302 to
 * whatever is stored here, which turns an authenticated route on our own
 * domain into a redirector. An allow-list of hosts would be the strongest
 * answer, but the whole point of the feature is that the link can be from any
 * platform, so instead the rules below reject the shapes that make an open
 * redirect useful to somebody else:
 *
 * <ul>
 *   <li>https only — no http, and no javascript:, data: or file: schemes</li>
 *   <li>a host that actually parses, with a dot in it</li>
 *   <li>no embedded credentials, which is the classic way to make a link read
 *       as one domain while resolving to another</li>
 * </ul>
 *
 * <p>Combined with the endpoint requiring authentication and writing an audit
 * row naming who created the meeting, that is the sensible trade.
 */
public final class MeetingUrlValidator {

    private MeetingUrlValidator() {
    }

    /** Validates and returns the normalised URL, or throws. */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Paste the meeting link.");
        }
        String trimmed = raw.trim();

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That does not look like a web address. Paste the full link, starting with https://");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The meeting link must start with https://");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank() || !host.contains(".")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That link has no recognisable web address in it.");
        }

        // https://evil.example.com@meet.google.com/abc reads as Google and is not.
        if (uri.getUserInfo() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Remove the username before the @ in that link.");
        }

        return trimmed;
    }

    /** Which platform the link belongs to. Display and grouping only. */
    public static MeetingProvider providerOf(String url) {
        String host;
        try {
            host = new URI(url.trim()).getHost();
        } catch (URISyntaxException e) {
            return MeetingProvider.OTHER;
        }
        if (host == null) {
            return MeetingProvider.OTHER;
        }
        String h = host.toLowerCase(Locale.ROOT);

        if (h.endsWith("meet.google.com") || h.endsWith("google.com")) {
            return MeetingProvider.GOOGLE_MEET;
        }
        if (h.endsWith("zoom.us") || h.contains(".zoom.")) {
            return MeetingProvider.ZOOM;
        }
        if (h.endsWith("teams.microsoft.com") || h.endsWith("teams.live.com")) {
            return MeetingProvider.MICROSOFT_TEAMS;
        }
        if (h.endsWith("webex.com")) {
            return MeetingProvider.WEBEX;
        }
        return MeetingProvider.OTHER;
    }
}
