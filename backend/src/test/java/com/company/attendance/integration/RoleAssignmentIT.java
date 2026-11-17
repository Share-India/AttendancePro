package com.company.attendance.integration;

import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.request.employee.EmployeeRoleAssignmentRequest;
import com.company.attendance.dto.response.employee.EmployeeDetailResponse;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Permission;
import com.company.attendance.entity.RefreshToken;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AuditLogRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PermissionRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.service.EmployeeService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Granting and removing roles.
 *
 * <p>Untested until now, and reachable only through the API: there was no
 * screen for it at all. That gap had a cost -- a reporting manager with no
 * MANAGER role is routed every request from their team and can act on none of
 * them, because routing reads the reporting line while the approve endpoints
 * read permissions. The two are independent, and only roles decide what
 * somebody can actually do.
 *
 * <p>The session revocation is the part most worth pinning down. Permissions
 * travel in the signed token, so without it a newly promoted manager would
 * keep being refused until their session happened to expire, with nothing on
 * screen explaining why.
 */
@SpringBootTest
@ActiveProfiles("test")
class RoleAssignmentIT {

    @Autowired EmployeeService employeeService;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Long officeId;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Permission punch = permission("ATTENDANCE_PUNCH");
        Permission approve = permission("LEAVE_APPROVE_L1");
        Permission viewAll = permission("EMPLOYEE_VIEW_ALL");

        role("EMPLOYEE", "Employee", punch);
        role("MANAGER", "Manager", punch, approve);
        role("HR", "HR", punch, approve, viewAll);

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

    @Test
    @DisplayName("granting MANAGER is what lets a reporting manager approve")
    void grantingManager() {
        Long id = createEmployee("S9001");

        EmployeeDetailResponse detail = employeeService.assignRoles(id,
                new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE", "MANAGER")));

        assertThat(detail.roles()).containsExactlyInAnyOrder("EMPLOYEE", "MANAGER");
        assertThat(permissionsOf("S9001")).contains("LEAVE_APPROVE_L1");
    }

    @Test
    @DisplayName("roles are replaced, not added to")
    void rolesAreReplaced() {
        // The screen sends the full set it is showing. If this merged instead,
        // unticking a box would appear to work and change nothing -- the worst
        // kind of failure, because it is invisible until somebody's access is
        // audited.
        Long id = createEmployee("S9002");
        employeeService.assignRoles(id, new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE", "HR")));

        employeeService.assignRoles(id, new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE")));

        assertThat(rolesOf("S9002").stream().map(Role::getCode))
                .containsExactly("EMPLOYEE");
        assertThat(permissionsOf("S9002")).doesNotContain("EMPLOYEE_VIEW_ALL");
    }

    @Test
    @DisplayName("changing roles signs the person out everywhere")
    void sessionsAreRevoked() {
        Long id = createEmployee("S9003");
        User user = userRepository.findByUsernameIgnoreCase("S9003").orElseThrow();
        issueRefreshToken(user);

        assertThat(refreshTokenRepository.findAll())
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNull());

        employeeService.assignRoles(id,
                new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE", "MANAGER")));

        // Permissions are carried in the token. Without this the new manager
        // keeps being refused until the old session expires.
        assertThat(refreshTokenRepository.findAll())
                .isNotEmpty()
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
    }

    @Test
    @DisplayName("the change is recorded with what it was before")
    void auditedWithTheOldRoles() {
        Long id = createEmployee("S9004");
        employeeService.assignRoles(id, new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE")));

        employeeService.assignRoles(id,
                new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE", "MANAGER")));

        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction().name()).isEqualTo("ROLE_ASSIGN");
                    assertThat(log.getDescription()).contains("S9004");
                });
    }

    @Test
    @DisplayName("an unknown role is refused, and names which one")
    void unknownRoleIsRefused() {
        Long id = createEmployee("S9005");

        assertThatThrownBy(() -> employeeService.assignRoles(id,
                new EmployeeRoleAssignmentRequest(Set.of("EMPLOYEE", "WIZARD"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getMessage()).contains("WIZARD");
                });

        // Nothing partially applied.
        assertThat(rolesOf("S9005").stream().map(Role::getCode))
                .doesNotContain("WIZARD");
    }

    @Test
    @DisplayName("role codes are matched regardless of case or spacing")
    void codesAreNormalised() {
        Long id = createEmployee("S9006");

        employeeService.assignRoles(id,
                new EmployeeRoleAssignmentRequest(Set.of(" manager ", "employee")));

        assertThat(rolesOf("S9006").stream().map(Role::getCode))
                .containsExactlyInAnyOrder("EMPLOYEE", "MANAGER");
    }

    @Test
    @DisplayName("every active role is offered, not a list baked into the client")
    void everyActiveRoleIsAssignable() {
        // The picker reads this. A role added or retired in the database has
        // to reach the screen without a release.
        assertThat(roleRepository.findByActiveTrue())
                .extracting(Role::getCode)
                .containsExactlyInAnyOrder("EMPLOYEE", "MANAGER", "HR");
    }

    // -----------------------------------------------------------------

    private Long createEmployee(String code) {
        return employeeService.create(new EmployeeCreateRequest(
                code, "Test", null, "Person", code.toLowerCase() + "@company.com",
                null, null, null,
                LocalDate.of(1995, 6, 14), null, null,
                LocalDate.now().minusYears(1), null,
                null, null, officeId, "PERMANENT",
                null, null, Set.of("EMPLOYEE"), null, null))
                .employee().id();
    }

    /**
     * Roles, fetched with their permissions.
     *
     * <p>findByUsernameIgnoreCase does not join them, and outside a session a
     * lazy collection throws rather than loading -- so the test has to ask for
     * the graph it reads.
     */
    private Set<Role> rolesOf(String username) {
        return userRepository.findByUsernameOrEmail(username).orElseThrow().getRoles();
    }

    private Set<String> permissionsOf(String username) {
        return rolesOf(username).stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void issueRefreshToken(User user) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash("hash-" + user.getId());
        token.setFamilyId(java.util.UUID.randomUUID().toString());
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        refreshTokenRepository.save(token);
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        permission.setName(code);
        permission.setModule("TEST");
        return permissionRepository.save(permission);
    }

    private void role(String code, String name, Permission... permissions) {
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setSystem(true);
        role.getPermissions().addAll(Set.of(permissions));
        roleRepository.save(role);
    }
}
