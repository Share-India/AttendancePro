package com.company.attendance.controller;

import com.company.attendance.config.JwtProperties;
import com.company.attendance.config.SecurityProperties;
import com.company.attendance.dto.request.auth.ChangePasswordRequest;
import com.company.attendance.dto.request.auth.ForgotPasswordRequest;
import com.company.attendance.dto.request.auth.LoginRequest;
import com.company.attendance.dto.request.auth.RefreshTokenRequest;
import com.company.attendance.dto.request.auth.ResetPasswordRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.auth.CurrentUserResponse;
import com.company.attendance.dto.response.auth.LoginResponse;
import com.company.attendance.dto.response.auth.TokenRefreshResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Authentication endpoints.
 *
 * <p>The refresh token is delivered as an HttpOnly cookie and never appears in a
 * response body, so a script that manages to run on the page still cannot read
 * it. The short-lived access token goes to the client, which holds it in memory.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Sign in, token lifecycle and password management")
public class AuthController {

    private final AuthService authService;
    private final SecurityProperties securityProperties;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService,
                          SecurityProperties securityProperties,
                          JwtProperties jwtProperties) {
        this.authService = authService;
        this.securityProperties = securityProperties;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with an employee ID or email address")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletResponse response) {
        var result = authService.login(request);
        setRefreshCookie(response, result.rawRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(result.login(), "Signed in successfully"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token (rotates the refresh token)")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String rawToken = resolveRefreshToken(request, body);
        var result = authService.refresh(rawToken);
        setRefreshCookie(response, result.rawRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(result.refresh(), "Token refreshed"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody(required = false) RefreshTokenRequest body,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        authService.logout(resolveRefreshToken(request, body));
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.success("Signed out"));
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every active session for the signed-in user")
    public ResponseEntity<ApiResponse<Void>> logoutAll(@CurrentUser UserPrincipal principal,
                                                       HttpServletResponse response) {
        authService.logoutAll(principal.getUserId());
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.success("All sessions signed out"));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        // Always the same answer, whether or not the address exists.
        return ResponseEntity.ok(ApiResponse.success(
                "If an account exists for that address, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a reset token")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                                           HttpServletResponse response) {
        authService.resetPassword(request);
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.success("Password updated. Please sign in."));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change the signed-in user's password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@CurrentUser UserPrincipal principal,
                                                            @Valid @RequestBody ChangePasswordRequest request,
                                                            HttpServletResponse response) {
        authService.changePassword(principal, request);
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.success("Password updated. Please sign in again."));
    }

    @GetMapping("/me")
    @Operation(summary = "Profile, roles and permissions for the signed-in user")
    public ResponseEntity<ApiResponse<CurrentUserResponse>> me(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(authService.getCurrentUser(principal.getUserId())));
    }

    // -----------------------------------------------------------------

    /** Cookie first; the body field exists only for clients that cannot use cookies. */
    private String resolveRefreshToken(HttpServletRequest request, RefreshTokenRequest body) {
        String fromCookie = readRefreshCookie(request);
        if (fromCookie != null) {
            return fromCookie;
        }
        return body != null ? body.refreshToken() : null;
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> securityProperties.refreshCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        long maxAge = jwtProperties.refreshTokenExpirationDays() * 24 * 60 * 60;
        response.addHeader("Set-Cookie", buildCookie(rawToken, maxAge));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", buildCookie("", 0));
    }

    /**
     * Built by hand rather than with ResponseCookie so SameSite is explicit and
     * the same string shape is used for both setting and clearing.
     */
    private String buildCookie(String value, long maxAgeSeconds) {
        StringBuilder cookie = new StringBuilder()
                .append(securityProperties.refreshCookieName()).append('=').append(value)
                .append("; Path=/api/v1/auth")
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly")
                .append("; SameSite=").append(securityProperties.refreshCookieSameSite());

        if (securityProperties.refreshCookieSecure()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
