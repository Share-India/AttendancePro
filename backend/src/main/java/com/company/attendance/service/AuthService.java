package com.company.attendance.service;

import com.company.attendance.dto.request.auth.ChangePasswordRequest;
import com.company.attendance.dto.request.auth.ForgotPasswordRequest;
import com.company.attendance.dto.request.auth.LoginRequest;
import com.company.attendance.dto.request.auth.ResetPasswordRequest;
import com.company.attendance.dto.response.auth.CurrentUserResponse;
import com.company.attendance.dto.response.auth.LoginResponse;
import com.company.attendance.dto.response.auth.TokenRefreshResponse;
import com.company.attendance.security.UserPrincipal;

/** Authentication and credential lifecycle. */
public interface AuthService {

    /** Returns the access token plus the raw refresh token for the caller to set as a cookie. */
    AuthenticationResult login(LoginRequest request);

    AuthenticationResult refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    void logoutAll(Long userId);

    /** Always succeeds from the caller's perspective — no account enumeration. */
    void requestPasswordReset(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(UserPrincipal principal, ChangePasswordRequest request);

    CurrentUserResponse getCurrentUser(Long userId);

    /** Carries the raw refresh token separately so only the controller ever sees it. */
    record AuthenticationResult(LoginResponse login, TokenRefreshResponse refresh, String rawRefreshToken) {

        public static AuthenticationResult forLogin(LoginResponse login, String rawRefreshToken) {
            return new AuthenticationResult(login, null, rawRefreshToken);
        }

        public static AuthenticationResult forRefresh(TokenRefreshResponse refresh, String rawRefreshToken) {
            return new AuthenticationResult(null, refresh, rawRefreshToken);
        }
    }
}
