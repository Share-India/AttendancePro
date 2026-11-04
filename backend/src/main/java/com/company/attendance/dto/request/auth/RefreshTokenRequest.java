package com.company.attendance.dto.request.auth;

/**
 * The refresh token normally travels in an HttpOnly cookie. This body field is
 * the fallback for clients that cannot use cookies (a native mobile app), and
 * is ignored when the cookie is present.
 */
public record RefreshTokenRequest(String refreshToken) {
}
