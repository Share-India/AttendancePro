package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.config.JwtProperties;
import com.company.attendance.config.SecurityProperties;
import com.company.attendance.dto.request.auth.ChangePasswordRequest;
import com.company.attendance.dto.request.auth.ForgotPasswordRequest;
import com.company.attendance.dto.request.auth.LoginRequest;
import com.company.attendance.dto.request.auth.ResetPasswordRequest;
import com.company.attendance.dto.response.auth.CurrentUserResponse;
import com.company.attendance.dto.response.auth.LoginResponse;
import com.company.attendance.dto.response.auth.TokenRefreshResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.PasswordResetToken;
import com.company.attendance.entity.RefreshToken;
import com.company.attendance.entity.User;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.PasswordResetTokenRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.JwtTokenProvider;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AuthService;
import com.company.attendance.service.LoginAttemptService;
import com.company.attendance.service.TokenRevocationService;
import com.company.attendance.util.IpAddressUtils;
import com.company.attendance.util.TokenHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmployeeManagerRepository employeeManagerRepository;
    private final LoginAttemptService loginAttemptService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final SecurityProperties securityProperties;
    private final AuditLogService auditLogService;

    public AuthServiceImpl(UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordResetTokenRepository passwordResetTokenRepository,
                           EmployeeManagerRepository employeeManagerRepository,
                           LoginAttemptService loginAttemptService,
                           TokenRevocationService tokenRevocationService,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider,
                           JwtProperties jwtProperties,
                           SecurityProperties securityProperties,
                           AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.employeeManagerRepository = employeeManagerRepository;
        this.loginAttemptService = loginAttemptService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
        this.securityProperties = securityProperties;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // Login
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public AuthenticationResult login(LoginRequest request) {
        String identifier = request.usernameOrEmail().trim();

        User user = userRepository.findByUsernameOrEmail(identifier).orElse(null);

        // The same generic failure is returned whether the account is missing or
        // the password is wrong, so the endpoint cannot be used to enumerate users.
        if (user == null) {
            loginAttemptService.record(identifier, null, false, "NO_SUCH_ACCOUNT");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (user.isCurrentlyLocked()) {
            loginAttemptService.record(identifier, user.getId(), false, "ACCOUNT_LOCKED");
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                    "Account is locked. Try again in " + securityProperties.accountLockoutMinutes()
                            + " minutes or contact HR.");
        }

        if (!user.isActive()) {
            loginAttemptService.record(identifier, user.getId(), false, "ACCOUNT_INACTIVE");
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.registerFailure(user.getId());
            loginAttemptService.record(identifier, user.getId(), false, "BAD_PASSWORD");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        Employee employee = user.getEmployee();
        if (employee != null && !employee.isEmployable()) {
            loginAttemptService.record(identifier, user.getId(), false, "EMPLOYEE_INACTIVE");
            throw new BusinessException(ErrorCode.EMPLOYEE_INACTIVE);
        }

        loginAttemptService.clearFailures(user.getId(), IpAddressUtils.resolve(currentRequest()));
        loginAttemptService.record(identifier, user.getId(), true, null);

        UserPrincipal principal = UserPrincipal.from(user);
        String accessToken = tokenProvider.generateAccessToken(principal);
        String rawRefreshToken = issueRefreshToken(user, UUID.randomUUID().toString());

        auditLogService.recordAuthEvent(AuditAction.LOGIN_SUCCESS, user.getId(), user.getUsername(),
                "Signed in successfully");

        LoginResponse response = LoginResponse.of(
                accessToken,
                tokenProvider.getAccessTokenExpirySeconds(),
                user.isMustChangePassword(),
                buildCurrentUser(user, principal));

        return AuthenticationResult.forLogin(response, rawRefreshToken);
    }

    // -----------------------------------------------------------------
    // Refresh with rotation and theft detection
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public AuthenticationResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        // A token that was already rotated is being replayed. The innocent
        // explanation is a race; the dangerous one is a stolen token. Revoke the
        // whole family and force a fresh sign-in either way.
        if (stored.isRevoked()) {
            // Read the owner BEFORE the bulk update: revokeFamily clears the
            // persistence context, which detaches `stored` and turns its lazy
            // user proxy into a LazyInitializationException.
            Long ownerId = stored.getUser().getId();
            String ownerUsername = stored.getUser().getUsername();
            String familyId = stored.getFamilyId();

            // Committed in its own transaction: this method throws next, and a
            // rollback here would leave the stolen token usable.
            tokenRevocationService.revokeFamily(familyId, "REUSE_DETECTED");
            auditLogService.recordAuthEvent(AuditAction.TOKEN_REUSE_DETECTED, ownerId, ownerUsername,
                    "Rotated refresh token replayed; token family revoked");
            log.warn("Refresh token reuse detected for user {}", ownerId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        if (stored.isExpired()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        User user = userRepository.findWithRolesById(stored.getUser().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (!user.isActive() || user.isCurrentlyLocked()) {
            tokenRevocationService.revokeAllForUser(user.getId(), "ACCOUNT_UNUSABLE");
            throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
        }

        String newRawToken = issueRefreshToken(user, stored.getFamilyId());

        stored.setRevokedAt(Instant.now());
        stored.setRevokedReason("ROTATED");
        refreshTokenRepository.save(stored);

        UserPrincipal principal = UserPrincipal.from(user);
        String accessToken = tokenProvider.generateAccessToken(principal);

        return AuthenticationResult.forRefresh(
                TokenRefreshResponse.of(accessToken, tokenProvider.getAccessTokenExpirySeconds()),
                newRawToken);
    }

    private String issueRefreshToken(User user, String familyId) {
        String rawToken = TokenHasher.generateToken();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(TokenHasher.hash(rawToken));
        token.setFamilyId(familyId);
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenExpirationDays(), ChronoUnit.DAYS));

        HttpServletRequest request = currentRequest();
        token.setIpAddress(IpAddressUtils.resolve(request));
        token.setDeviceInfo(IpAddressUtils.userAgent(request));

        refreshTokenRepository.save(token);
        return rawToken;
    }

    // -----------------------------------------------------------------
    // Logout
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.hash(rawRefreshToken)).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(Instant.now());
                token.setRevokedReason("LOGOUT");
                refreshTokenRepository.save(token);
            }
            auditLogService.recordAuthEvent(AuditAction.LOGOUT, token.getUser().getId(),
                    token.getUser().getUsername(), "Signed out");
        });
    }

    @Override
    @Transactional
    public void logoutAll(Long userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now(), "LOGOUT_ALL");
        auditLogService.recordAuthEvent(AuditAction.LOGOUT_ALL, userId, null,
                "Revoked " + revoked + " active session(s)");
    }

    // -----------------------------------------------------------------
    // Password lifecycle
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        String email = request.email().trim();

        // Deliberately silent about whether the address exists. The endpoint
        // returns 200 either way so it cannot be used to test for accounts.
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (!user.isActive()) {
                return;
            }
            passwordResetTokenRepository.invalidateOutstandingForUser(user.getId(), Instant.now());

            String rawToken = TokenHasher.generateToken();
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(TokenHasher.hash(rawToken));
            token.setExpiresAt(Instant.now().plus(
                    securityProperties.passwordResetTokenValidityMinutes(), ChronoUnit.MINUTES));
            token.setRequestedIp(IpAddressUtils.resolve(currentRequest()));
            token.setCreatedAt(Instant.now());
            passwordResetTokenRepository.save(token);

            auditLogService.recordAuthEvent(AuditAction.PASSWORD_RESET_REQUEST, user.getId(),
                    user.getUsername(), "Password reset link requested");

            // Email delivery arrives with the notification module in Phase 9.
            // Until then the token is logged so the flow is testable end to end.
            log.info("Password reset token for {} (valid {} minutes): {}",
                    user.getUsername(), securityProperties.passwordResetTokenValidityMinutes(), rawToken);
        });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Passwords do not match");
        }

        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(TokenHasher.hash(request.token()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));

        if (token.isUsed()) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        if (token.isExpired()) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_EXPIRED);
        }

        User user = token.getUser();
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED);
        }

        applyNewPassword(user, request.newPassword());
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        // Any session opened with the old password is no longer trustworthy.
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now(), "PASSWORD_RESET");

        auditLogService.recordAuthEvent(AuditAction.PASSWORD_RESET_COMPLETE, user.getId(),
                user.getUsername(), "Password reset completed; all sessions revoked");
    }

    @Override
    @Transactional
    public void changePassword(UserPrincipal principal, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Passwords do not match");
        }

        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_REUSED);
        }

        applyNewPassword(user, request.newPassword());
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now(), "PASSWORD_CHANGE");

        auditLogService.recordAuthEvent(AuditAction.PASSWORD_CHANGE, user.getId(),
                user.getUsername(), "Password changed; all sessions revoked");
    }

    private void applyNewPassword(User user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts((short) 0);
        user.setLocked(false);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    // -----------------------------------------------------------------
    // Current user
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(Long userId) {
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return buildCurrentUser(user, UserPrincipal.from(user));
    }

    private CurrentUserResponse buildCurrentUser(User user, UserPrincipal principal) {
        Employee employee = user.getEmployee();

        String managerName = null;
        if (employee != null) {
            managerName = employeeManagerRepository
                    .findPrimaryManagerOn(employee.getId(), LocalDate.now())
                    .map(EmployeeManager::getManager)
                    .map(Employee::getFullName)
                    .orElse(null);
        }

        return new CurrentUserResponse(
                user.getId(),
                employee != null ? employee.getId() : null,
                employee != null ? employee.getEmployeeCode() : null,
                user.getUsername(),
                user.getEmail(),
                employee != null ? employee.getFullName() : user.getUsername(),
                employee != null && employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee != null && employee.getDesignation() != null ? employee.getDesignation().getName() : null,
                employee != null && employee.getOffice() != null ? employee.getOffice().getName() : null,
                managerName,
                employee != null ? employee.getProfilePhotoUrl() : null,
                principal.getRoles(),
                principal.getPermissions(),
                user.isMustChangePassword());
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }
}
