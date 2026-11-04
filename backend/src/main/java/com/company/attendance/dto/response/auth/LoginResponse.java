package com.company.attendance.dto.response.auth;

/**
 * The refresh token is deliberately absent: it is delivered as an HttpOnly
 * cookie so JavaScript cannot read it. Only the short-lived access token is
 * handed to the client, and it is held in memory rather than localStorage.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        boolean mustChangePassword,
        CurrentUserResponse user
) {
    public static LoginResponse of(String accessToken, long expiresIn, boolean mustChangePassword,
                                   CurrentUserResponse user) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, mustChangePassword, user);
    }
}
