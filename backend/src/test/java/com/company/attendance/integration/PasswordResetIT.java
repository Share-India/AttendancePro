package com.company.attendance.integration;

import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.service.EmployeeService.PasswordResetResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Permission;
import com.company.attendance.entity.RefreshToken;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PermissionRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.service.EmployeeService;
import com.company.attendance.service.EmployeeService.IssuedCredential;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-time passwords, and the administrator reset that issues a new one.
 *
 * <p>Two properties hold this together and each is easy to lose by accident.
 *
 * <p><strong>Passwords are per person, not shared.</strong> A single starting
 * password for everybody would mean an employee code — printed on everything —
 * is enough to sign in as somebody until they rotate. The tests here assert
 * that two accounts never come out the same, which no amount of reading the
 * generator proves.
 *
 * <p><strong>Nobody can read a password back, including an administrator who
 * holds every permission.</strong> The row keeps a BCrypt hash, so "let HR see
 * everybody's first password" is served by issuing a fresh one, not by
 * retrieving the old one. That is a deliberate trade: HR can always get
 * somebody signed in, and a stolen database never yields a working password.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetIT {

    @Autowired EmployeeService employeeService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired DatabaseCleaner databaseCleaner;

    private Long officeId;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Permission viewSelf = permission("EMPLOYEE_VIEW_SELF");
        role("EMPLOYEE", viewSelf);

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        officeId = officeRepository.save(office).getId();

        Shift shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift.setDefaultShift(true);
        shiftRepository.save(shift);
    }

    // -----------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every new account gets its own password, never a shared one")
    void createsDistinctPasswords() {
        var first = employeeService.create(request("EMP100", "Asha", "Rao"));
        var second = employeeService.create(request("EMP101", "Bikram", "Sen"));
        var third = employeeService.create(request("EMP102", "Chitra", "Das"));

        assertThat(List.of(first.temporaryPassword(), second.temporaryPassword(),
                        third.temporaryPassword()))
                .doesNotHaveDuplicates();

        // Stored hashed. The readable value exists only in the response above.
        User user = userRepository.findByUsernameIgnoreCase("EMP100").orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(first.temporaryPassword());
        assertThat(passwordEncoder.matches(first.temporaryPassword(),
                user.getPasswordHash())).isTrue();
        // And one person's password is no use for anybody else's account.
        assertThat(passwordEncoder.matches(second.temporaryPassword(),
                user.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("every new account must change its password at first sign-in")
    void createdAccountsMustChange() {
        employeeService.create(request("EMP103", "Deepa", "Nair"));
        employeeService.create(request("EMP104", "Esha", "Pillai"));

        assertThat(userRepository.findByUsernameIgnoreCase("EMP103").orElseThrow()
                .isMustChangePassword()).isTrue();
        assertThat(userRepository.findByUsernameIgnoreCase("EMP104").orElseThrow()
                .isMustChangePassword()).isTrue();
    }

    // -----------------------------------------------------------------
    // Resetting one account
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a reset issues a new password, returns it once, and forces a change again")
    void resetIssuesANewPassword() {
        var created = employeeService.create(request("EMP200", "Farid", "Khan"));
        Long employeeId = created.employee().id();

        // The employee has signed in and chosen their own password.
        User user = userRepository.findByUsernameIgnoreCase("EMP200").orElseThrow();
        user.setPasswordHash(passwordEncoder.encode("TheirOwn@2026"));
        user.setMustChangePassword(false);
        userRepository.save(user);

        var result = employeeService.resetPassword(employeeId);

        assertThat(result.reset()).isTrue();

        IssuedCredential issued = result.credential();
        assertThat(issued.employeeCode()).isEqualTo("EMP200");
        assertThat(issued.employeeName()).isEqualTo("Farid Khan");
        assertThat(issued.password()).hasSize(14);

        User after = userRepository.findByUsernameIgnoreCase("EMP200").orElseThrow();
        assertThat(passwordEncoder.matches(issued.password(), after.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("TheirOwn@2026", after.getPasswordHash())).isFalse();
        // Also not the password they were originally created with.
        assertThat(passwordEncoder.matches(created.temporaryPassword(),
                after.getPasswordHash())).isFalse();
        assertThat(after.isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("resetting twice gives two different passwords, so the old one is truly dead")
    void resetsAreNotRepeatable() {
        var created = employeeService.create(request("EMP201", "Gita", "Bose"));
        Long employeeId = created.employee().id();

        String firstReset = employeeService.resetPassword(employeeId).credential().password();
        String secondReset = employeeService.resetPassword(employeeId).credential().password();

        assertThat(firstReset).isNotEqualTo(secondReset);

        User after = userRepository.findByUsernameIgnoreCase("EMP201").orElseThrow();
        assertThat(passwordEncoder.matches(secondReset, after.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(firstReset, after.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("a reset clears a lockout, or the new password would still refuse to sign in")
    void resetClearsLockout() {
        var created = employeeService.create(request("EMP202", "Hari", "Menon"));

        User user = userRepository.findByUsernameIgnoreCase("EMP202").orElseThrow();
        user.setLocked(true);
        user.setLockedUntil(Instant.now().plusSeconds(3600));
        user.setFailedLoginAttempts((short) 5);
        userRepository.save(user);

        employeeService.resetPassword(created.employee().id());

        User after = userRepository.findByUsernameIgnoreCase("EMP202").orElseThrow();
        assertThat(after.isLocked()).isFalse();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedLoginAttempts()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("a reset revokes live sessions, so an open login cannot sail past the forced change")
    void resetRevokesSessions() {
        var created = employeeService.create(request("EMP203", "Ira", "Shah"));
        User user = userRepository.findByUsernameIgnoreCase("EMP203").orElseThrow();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash("a".repeat(64));
        token.setFamilyId("family-203");
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(86_400));
        refreshTokenRepository.save(token);

        employeeService.resetPassword(created.employee().id());

        RefreshToken after = refreshTokenRepository.findByTokenHash("a".repeat(64)).orElseThrow();
        assertThat(after.getRevokedAt()).isNotNull();
        assertThat(after.getRevokedReason()).isEqualTo("ADMIN_PASSWORD_RESET");
    }

    @Test
    @DisplayName("an employee with no login is reported, not reported as reset")
    void resetReportsEmployeesWithoutLogin() {
        var created = employeeService.create(request("EMP204", "Jay", "Thomas"));
        Long employeeId = created.employee().id();

        // An employee record can outlive its login — a row created straight in
        // the database, or a login removed by hand.
        userRepository.delete(userRepository.findByUsernameIgnoreCase("EMP204").orElseThrow());

        var result = employeeService.resetPassword(employeeId);

        assertThat(result.reset()).isFalse();
        assertThat(result.credential()).isNull();
    }

    @Test
    @DisplayName("the result serialises to the shape the reset dialog reads")
    void serialisesForTheUi() throws Exception {
        var created = employeeService.create(request("EMP205", "Kiran", "Iyer"));
        var result = employeeService.resetPassword(created.employee().id());

        // The dialog renders the password from `credential.password` and
        // decides between the password panel and the no-login warning on
        // `reset`. A rename on either side leaves the dialog showing nothing,
        // which is exactly the failure that cannot be noticed from the server.
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertThat(json.get("reset").asBoolean()).isTrue();
        assertThat(json.get("credential").get("employeeCode").asText()).isEqualTo("EMP205");
        assertThat(json.get("credential").get("employeeName").asText()).isEqualTo("Kiran Iyer");
        assertThat(json.get("credential").get("password").asText())
                .isEqualTo(result.credential().password());

        // No credential when there was no login. The mapper is configured to
        // omit nulls, so the field is absent rather than present-and-null —
        // which the dialog treats the same way, but the TypeScript type has to
        // admit, hence `credential?:` and not `credential:`.
        JsonNode none = objectMapper.readTree(
                objectMapper.writeValueAsString(PasswordResetResult.noLogin()));
        assertThat(none.get("reset").asBoolean()).isFalse();
        assertThat(none.hasNonNull("credential")).isFalse();
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private EmployeeCreateRequest request(String code, String first, String last) {
        return new EmployeeCreateRequest(
                code, first, null, last, code.toLowerCase() + "@company.com", null, null, null,
                LocalDate.of(1995, 6, 14), null, null,
                LocalDate.now().minusYears(1), null,
                null, null, officeId, "PERMANENT",
                null, null, null, null, null);
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        permission.setName(code);
        permission.setModule("EMPLOYEE");
        return permissionRepository.save(permission);
    }

    private void role(String code, Permission... permissions) {
        Role role = new Role();
        role.setCode(code);
        role.setName(code);
        role.setPermissions(Set.of(permissions));
        roleRepository.save(role);
    }
}
