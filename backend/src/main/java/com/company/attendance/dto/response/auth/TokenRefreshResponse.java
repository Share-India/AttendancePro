package com.company.attendance.dto.response.auth;

public record TokenRefreshResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
    public static TokenRefreshResponse of(String accessToken, long expiresIn) {
        return new TokenRefreshResponse(accessToken, "Bearer", expiresIn);
    }
}
