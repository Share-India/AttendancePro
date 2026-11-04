package com.company.attendance.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's address. The first entry of X-Forwarded-For is used
 * when present, because the app sits behind Nginx in every deployment.
 */
public final class IpAddressUtils {

    private static final String[] HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    };

    private IpAddressUtils() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                String candidate = comma > 0 ? value.substring(0, comma) : value;
                return truncate(candidate.trim());
            }
        }
        return truncate(request.getRemoteAddr());
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String agent = request.getHeader("User-Agent");
        return agent == null ? null : agent.substring(0, Math.min(agent.length(), 255));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 45 ? value.substring(0, 45) : value;
    }
}
