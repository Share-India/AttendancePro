package com.company.attendance.integration;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Permission;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.User;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.PasswordResetTokenRepository;
import com.company.attendance.repository.PermissionRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIT {

    private static final String PASSWORD = "Str0ng!Pass";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DatabaseCleaner databaseCleaner;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Permission punch = new Permission();
        punch.setCode("ATTENDANCE_PUNCH");
        punch.setName("Punch in and out");
        punch.setModule("ATTENDANCE");
        permissionRepository.save(punch);

        Role employeeRole = new Role();
        employeeRole.setCode("EMPLOYEE");
        employeeRole.setName("Employee");
        employeeRole.setSystem(true);
        employeeRole.getPermissions().add(punch);
        roleRepository.save(employeeRole);

        Employee employee = new Employee();
        employee.setEmployeeCode("EMP1001");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.rao@company.com");
        employee.setJoiningDate(LocalDate.now().minusYears(2));
        employeeRepository.save(employee);

        User user = new User();
        user.setEmployee(employee);
        user.setUsername("EMP1001");
        user.setEmail("asha.rao@company.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.addRole(employeeRole);
        userRepository.save(user);
    }

    @Test
    @DisplayName("signs in with the employee code and returns the refresh token only as an HttpOnly cookie")
    void loginIssuesTokenAndCookie() throws Exception {
        MvcResult result = login("EMP1001", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.employeeCode").value("EMP1001"))
                .andExpect(jsonPath("$.data.user.permissions[0]").value("ATTENDANCE_PUNCH"))
                // The refresh token must never appear in the body.
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("signs in with the email address as well as the employee code")
    void loginAcceptsEmail() throws Exception {
        login("asha.rao@company.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.username").value("EMP1001"));
    }

    @Test
    @DisplayName("returns the same generic failure for a wrong password and an unknown account")
    void loginDoesNotEnumerateAccounts() throws Exception {
        login("EMP1001", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));

        login("nobody@company.com", "whatever")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("locks the account after the configured number of failures")
    void locksAccountAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("EMP1001", "wrong-password").andExpect(status().isUnauthorized());
        }

        // The correct password must now be refused too - the lockout is real.
        login("EMP1001", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));

        User locked = userRepository.findByUsernameIgnoreCase("EMP1001").orElseThrow();
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getFailedLoginAttempts()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("rotates the refresh token and revokes the whole family when an old one is replayed")
    void refreshRotatesAndDetectsReuse() throws Exception {
        Cookie first = login("EMP1001", PASSWORD).andReturn().getResponse().getCookie("refresh_token");
        assertThat(first).isNotNull();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        Cookie second = refreshed.getResponse().getCookie("refresh_token");
        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Replaying the rotated token is treated as theft.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_REUSED"));

        // ...and it takes the replacement down with it.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a protected endpoint without a token and allows it with one")
    void protectsEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"));

        String accessToken = extractAccessToken(login("EMP1001", PASSWORD).andReturn());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.data.roles[0]").value("EMPLOYEE"));
    }

    @Test
    @DisplayName("reports a malformed token distinctly from a missing one")
    void rejectsGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("answers forgot-password identically for known and unknown addresses")
    void forgotPasswordDoesNotLeakAccounts() throws Exception {
        String knownBody = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"asha.rao@company.com"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String unknownBody = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ghost@company.com"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(messageOf(knownBody)).isEqualTo(messageOf(unknownBody));
    }

    @Test
    @DisplayName("rejects an invalid request body with field-level detail")
    void validatesRequestBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usernameOrEmail":"","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.fieldErrors").isArray());
    }

    // -----------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions login(String identifier, String password)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("usernameOrEmail", identifier, "password", password, "rememberMe", false));
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @SuppressWarnings("unchecked")
    private String extractAccessToken(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (String) data.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private String messageOf(String json) throws Exception {
        Map<String, Object> body = objectMapper.readValue(json, Map.class);
        return (String) body.get("message");
    }
}
