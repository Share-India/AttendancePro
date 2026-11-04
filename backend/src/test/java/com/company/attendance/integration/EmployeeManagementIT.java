package com.company.attendance.integration;

import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.request.master.ManagerAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftRequest;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Permission;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PasswordResetTokenRepository;
import com.company.attendance.repository.PermissionRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.service.EmployeeService;
import com.company.attendance.service.EmployeeImportService;
import com.company.attendance.service.HierarchyService;
import com.company.attendance.service.ShiftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EmployeeManagementIT {

    @Autowired EmployeeService employeeService;
    @Autowired ShiftService shiftService;
    @Autowired HierarchyService hierarchyService;
    @Autowired EmployeeImportService employeeImportService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DatabaseCleaner databaseCleaner;

    private Long officeId;
    private Long shiftId;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Permission viewAll = permission("EMPLOYEE_VIEW_ALL", "EMPLOYEE");
        Permission viewTeam = permission("EMPLOYEE_VIEW_TEAM", "EMPLOYEE");
        Permission viewSelf = permission("EMPLOYEE_VIEW_SELF", "EMPLOYEE");

        role("EMPLOYEE", viewSelf);
        role("MANAGER", viewSelf, viewTeam);
        role("HR", viewSelf, viewTeam, viewAll);

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
        shiftId = shiftRepository.save(shift).getId();
    }

    // -----------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("creating an employee provisions a login that must change its password")
    void createProvisionsLogin() {
        var result = employeeService.create(request("EMP2001", "Asha", "Rao", "asha.rao@company.com"));

        assertThat(result.temporaryPassword()).hasSize(14);
        assertThat(result.employee().employeeCode()).isEqualTo("EMP2001");
        assertThat(result.employee().roles()).containsExactly("EMPLOYEE");

        var user = userRepository.findByUsernameIgnoreCase("EMP2001").orElseThrow();
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(user.isActive()).isTrue();
        // The password is stored hashed, never in readable form.
        assertThat(user.getPasswordHash()).isNotEqualTo(result.temporaryPassword());
        assertThat(passwordEncoder.matches(result.temporaryPassword(), user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("a new employee gets the default shift from their joining date")
    void createAssignsDefaultShift() {
        var result = employeeService.create(request("EMP2002", "Bilal", "Khan", "bilal.khan@company.com"));

        assertThat(result.employee().currentShift()).isNotNull();
        assertThat(result.employee().currentShift().shiftCode()).isEqualTo("GEN");
        assertThat(result.employee().currentShift().effectiveFrom())
                .isEqualTo(result.employee().joiningDate());
    }

    @Test
    @DisplayName("rejects a duplicate employee code")
    void rejectsDuplicateCode() {
        employeeService.create(request("EMP2003", "Chen", "Wu", "chen.wu@company.com"));

        assertThatThrownBy(() -> employeeService.create(
                request("EMP2003", "Other", "Person", "other.person@company.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    // -----------------------------------------------------------------
    // Hierarchy
    // -----------------------------------------------------------------

    @Test
    @DisplayName("refuses a manager assignment that would create a reporting loop")
    void refusesReportingCycle() {
        Long managerId = employeeService.create(
                request("EMP3001", "Dana", "Iyer", "dana.iyer@company.com")).employee().id();
        Long reportId = employeeService.create(
                request("EMP3002", "Evan", "Sharma", "evan.sharma@company.com")).employee().id();

        employeeService.assignManager(reportId,
                new ManagerAssignmentRequest(managerId, "PRIMARY", LocalDate.now().minusDays(30), null));

        // Making the manager report to their own subordinate would make the
        // subtree walk loop forever on the next team query.
        assertThatThrownBy(() -> employeeService.assignManager(managerId,
                new ManagerAssignmentRequest(reportId, "PRIMARY", LocalDate.now(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("loop");
    }

    @Test
    @DisplayName("refuses to make an employee their own manager")
    void refusesSelfManagement() {
        Long id = employeeService.create(
                request("EMP3003", "Farah", "Ali", "farah.ali@company.com")).employee().id();

        assertThatThrownBy(() -> employeeService.assignManager(id,
                new ManagerAssignmentRequest(id, "PRIMARY", LocalDate.now(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("themselves");
    }

    @Test
    @DisplayName("resolves the whole subtree, not just direct reports")
    void resolvesNestedSubtree() {
        Long head = employeeService.create(
                request("EMP4001", "Head", "One", "head.one@company.com")).employee().id();
        Long middle = employeeService.create(
                request("EMP4002", "Middle", "Two", "middle.two@company.com")).employee().id();
        Long leaf = employeeService.create(
                request("EMP4003", "Leaf", "Three", "leaf.three@company.com")).employee().id();

        employeeService.assignManager(middle,
                new ManagerAssignmentRequest(head, "PRIMARY", LocalDate.now().minusDays(10), null));
        employeeService.assignManager(leaf,
                new ManagerAssignmentRequest(middle, "PRIMARY", LocalDate.now().minusDays(10), null));

        Set<Long> subtree = hierarchyService.getSubordinateIds(head, LocalDate.now());
        assertThat(subtree).containsExactlyInAnyOrder(middle, leaf);
    }

    // -----------------------------------------------------------------
    // Data scope
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an employee cannot read another employee's record")
    void selfScopeBlocksOtherRecords() {
        Long selfId = employeeService.create(
                request("EMP5001", "Self", "User", "self.user@company.com")).employee().id();
        Long otherId = employeeService.create(
                request("EMP5002", "Other", "User", "other.user@company.com")).employee().id();

        UserPrincipal employee = principal(selfId, Set.of("EMPLOYEE"), Set.of("EMPLOYEE_VIEW_SELF"));

        assertThat(employeeService.findById(employee, selfId).id()).isEqualTo(selfId);

        assertThatThrownBy(() -> employeeService.findById(employee, otherId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    @DisplayName("a manager reads their team but not someone outside it")
    void teamScopeIsLimitedToSubtree() {
        Long managerId = employeeService.create(
                request("EMP6001", "Team", "Lead", "team.lead@company.com")).employee().id();
        Long reportId = employeeService.create(
                request("EMP6002", "Team", "Member", "team.member@company.com")).employee().id();
        Long outsiderId = employeeService.create(
                request("EMP6003", "Outside", "Person", "outside.person@company.com")).employee().id();

        employeeService.assignManager(reportId,
                new ManagerAssignmentRequest(managerId, "PRIMARY", LocalDate.now().minusDays(5), null));

        UserPrincipal manager = principal(managerId, Set.of("MANAGER"),
                Set.of("EMPLOYEE_VIEW_SELF", "EMPLOYEE_VIEW_TEAM"));

        assertThat(employeeService.findById(manager, reportId).id()).isEqualTo(reportId);

        assertThatThrownBy(() -> employeeService.findById(manager, outsiderId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not in your team");
    }

    // -----------------------------------------------------------------
    // Shifts
    // -----------------------------------------------------------------

    @Test
    @DisplayName("assigning a new shift closes the previous open-ended one")
    void reassigningShiftClosesPrevious() {
        Long employeeId = employeeService.create(
                request("EMP7001", "Shift", "Mover", "shift.mover@company.com")).employee().id();

        Shift evening = new Shift();
        evening.setCode("EVE");
        evening.setName("Evening Shift");
        evening.setStartTime(LocalTime.of(14, 0));
        evening.setEndTime(LocalTime.of(23, 0));
        Long eveningId = shiftRepository.save(evening).getId();

        LocalDate switchDate = LocalDate.now().plusDays(7);
        shiftService.assign(employeeId, new ShiftAssignmentRequest(eveningId, switchDate, null, "Rotation"));

        var history = shiftService.findAssignmentHistory(employeeId);
        assertThat(history).hasSize(2);

        var previous = history.stream()
                .filter(assignment -> "GEN".equals(assignment.shiftCode()))
                .findFirst().orElseThrow();

        // The two assignments must not both claim the same day, or resolving
        // the shift for that date would be ambiguous.
        assertThat(previous.effectiveTo()).isEqualTo(switchDate.minusDays(1));

        assertThat(shiftService.findEffectiveShift(employeeId, switchDate).orElseThrow().shiftCode())
                .isEqualTo("EVE");
        assertThat(shiftService.findEffectiveShift(employeeId, switchDate.minusDays(1)).orElseThrow().shiftCode())
                .isEqualTo("GEN");
    }

    @Test
    @DisplayName("rejects a shift whose full-day threshold exceeds its own span")
    void rejectsUnreachableFullDay() {
        // 09:00-13:00 is a four-hour shift; requiring eight hours for a full day
        // would mean nobody on it could ever be marked present.
        ShiftRequest request = new ShiftRequest(
                "IMPOSSIBLE", "Impossible Shift",
                LocalTime.of(9, 0), LocalTime.of(13, 0),
                15, 15, 480, 480, 240, 120, 60, 120, 240,
                null, null, false, true);

        assertThatThrownBy(() -> shiftService.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceed the shift span");
    }

    @Test
    @DisplayName("rejects a half-day threshold above the full-day threshold")
    void rejectsInvertedThresholds() {
        ShiftRequest request = new ShiftRequest(
                "INVERTED", "Inverted Shift",
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                15, 15, 480, 300, 400, 120, 60, 120, 240,
                null, null, false, true);

        assertThatThrownBy(() -> shiftService.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Half-day minutes cannot exceed full-day minutes");
    }

    @Test
    @DisplayName("marks a shift crossing midnight as a night shift")
    void detectsNightShift() {
        ShiftRequest request = new ShiftRequest(
                "NIGHT2", "Night Shift",
                LocalTime.of(22, 0), LocalTime.of(7, 0),
                10, 10, 480, 480, 240, 120, 60, 120, 240,
                null, null, false, true);

        assertThat(shiftService.create(request).nightShift()).isTrue();
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Test
    @DisplayName("refuses to deactivate an employee who still has reports")
    void refusesDeactivationWithReports() {
        Long managerId = employeeService.create(
                request("EMP8001", "Busy", "Manager", "busy.manager@company.com")).employee().id();
        Long reportId = employeeService.create(
                request("EMP8002", "Their", "Report", "their.report@company.com")).employee().id();

        employeeService.assignManager(reportId,
                new ManagerAssignmentRequest(managerId, "PRIMARY", LocalDate.now().minusDays(1), null));

        assertThatThrownBy(() -> employeeService.deactivate(managerId, "Resigned"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("still has 1 report");
    }

    @Test
    @DisplayName("deactivating an employee disables their login")
    void deactivationDisablesLogin() {
        Long id = employeeService.create(
                request("EMP8003", "Leaving", "Soon", "leaving.soon@company.com")).employee().id();

        employeeService.deactivate(id, "End of contract");

        var user = userRepository.findByUsernameIgnoreCase("EMP8003").orElseThrow();
        assertThat(user.isActive()).isFalse();

        var employee = employeeRepository.findById(id).orElseThrow();
        assertThat(employee.isActive()).isFalse();
        assertThat(employee.getExitDate()).isNotNull();
    }

    // -----------------------------------------------------------------

    private EmployeeCreateRequest request(String code, String first, String last, String email) {
        return new EmployeeCreateRequest(
                code, first, null, last, email, null, null, null,
                LocalDate.of(1995, 6, 14), null, null,
                LocalDate.now().minusYears(1), null,
                null, null, officeId, "PERMANENT",
                null, null, null, null, null);
    }

    private UserPrincipal principal(Long employeeId, Set<String> roles, Set<String> permissions) {
        return UserPrincipal.fromClaims(employeeId, employeeId, "TEST" + employeeId, roles, permissions);
    }

    private Permission permission(String code, String module) {
        Permission permission = new Permission();
        permission.setCode(code);
        permission.setName(code);
        permission.setModule(module);
        return permissionRepository.save(permission);
    }

    private void role(String code, Permission... permissions) {
        Role role = new Role();
        role.setCode(code);
        role.setName(code);
        role.setSystem(true);
        role.getPermissions().addAll(Set.of(permissions));
        roleRepository.save(role);
    }

    // -----------------------------------------------------------------
    // Bulk import
    //
    // The manager column was accepted, advertised in the template and then
    // silently discarded: an operator got "Imported 8 employees" and no org
    // chart at all, which quietly empties every approval inbox downstream.
    // -----------------------------------------------------------------

    private static final String IMPORT_HEADER =
            "employee_code,first_name,last_name,official_email,joining_date,office_code,"
                    + "employment_type,department_code,designation_code,manager_employee_code,shift_code\n";

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "import.csv", "text/csv",
                (IMPORT_HEADER + body).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the manager named in the file actually becomes the reporting manager")
    void importLinksTheManager() {
        var result = employeeImportService.importCsv(csv(
                "EMP3001,Ravi,Kumar,ravi.kumar@company.com,2024-01-10,HO,PERMANENT,,,,\n"
                        + "EMP3002,Sita,Rao,sita.rao@company.com,2024-02-10,HO,PERMANENT,,,EMP3001,\n"));

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.warnings()).isEmpty();

        Long managerId = employeeRepository.findByEmployeeCode("EMP3001").orElseThrow().getId();
        Long reportId = employeeRepository.findByEmployeeCode("EMP3002").orElseThrow().getId();

        assertThat(hierarchyService.managesEmployee(managerId, reportId, LocalDate.now())).isTrue();
    }

    @Test
    @DisplayName("a manager listed after their own report still resolves")
    void importResolvesForwardReferences() {
        // The whole reason the link is a second pass: on a single pass this row
        // would name somebody who does not exist yet.
        var result = employeeImportService.importCsv(csv(
                "EMP3003,Sita,Rao,sita.rao@company.com,2024-02-10,HO,PERMANENT,,,EMP3004,\n"
                        + "EMP3004,Ravi,Kumar,ravi.kumar@company.com,2024-01-10,HO,PERMANENT,,,,\n"));

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.warnings()).isEmpty();

        Long managerId = employeeRepository.findByEmployeeCode("EMP3004").orElseThrow().getId();
        Long reportId = employeeRepository.findByEmployeeCode("EMP3003").orElseThrow().getId();

        assertThat(hierarchyService.managesEmployee(managerId, reportId, LocalDate.now())).isTrue();
    }

    @Test
    @DisplayName("a manager code matching nobody is a warning, not a silent success")
    void importWarnsAboutAnUnknownManager() {
        var result = employeeImportService.importCsv(csv(
                "EMP3005,Sita,Rao,sita.rao@company.com,2024-02-10,HO,PERMANENT,,,NOBODY,\n"));

        // The employee exists, so this is not a failure...
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(employeeRepository.findByEmployeeCode("EMP3005")).isPresent();

        // ...but the operator is told the reporting line did not happen.
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0).message()).contains("NOBODY");
    }

    @Test
    @DisplayName("an explicit shift code is honoured rather than falling back to the default")
    void importHonoursTheShiftColumn() {
        Shift night = new Shift();
        night.setCode("NIGHT");
        night.setName("Night Shift");
        night.setStartTime(LocalTime.of(22, 0));
        night.setEndTime(LocalTime.of(6, 0));
        night.setNightShift(true);
        shiftRepository.save(night);

        var result = employeeImportService.importCsv(csv(
                "EMP3006,Ravi,Kumar,ravi.kumar@company.com,2024-01-10,HO,PERMANENT,,,,NIGHT\n"));

        assertThat(result.imported()).isEqualTo(1);

        Long employeeId = employeeRepository.findByEmployeeCode("EMP3006").orElseThrow().getId();
        var assigned = employeeShiftRepository.findCurrentFor(employeeId, LocalDate.now()).orElseThrow();
        assertThat(assigned.getShift().getCode()).isEqualTo("NIGHT");
    }

    @Test
    @DisplayName("an unknown shift code fails its row instead of quietly using the default")
    void importRefusesAnUnknownShift() {
        var result = employeeImportService.importCsv(csv(
                "EMP3007,Ravi,Kumar,ravi.kumar@company.com,2024-01-10,HO,PERMANENT,,,,GHOST\n"));

        assertThat(result.imported()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors().get(0).message()).contains("GHOST");
    }

    // -----------------------------------------------------------------
    // Dates as spreadsheets actually write them
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a date Excel rewrote into dd-MM-yyyy is still accepted")
    void importAcceptsDayFirstDates() {
        var result = employeeImportService.importCsv(csv(
                "EMP3101,Sagar,Dagale,sagar.dagale@company.com,02-09-2026,HO,PERMANENT,,,,\n"));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        // Day first, always: the 2nd of September, never the 9th of February.
        assertThat(employeeRepository.findByEmployeeCode("EMP3101").orElseThrow().getJoiningDate())
                .isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("slashes are accepted too, and read day-first as well")
    void importAcceptsSlashedDates() {
        var result = employeeImportService.importCsv(csv(
                "EMP3102,Ravi,Kumar,ravi.kumar@company.com,25/04/2024,HO,PERMANENT,,,,\n"));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(employeeRepository.findByEmployeeCode("EMP3102").orElseThrow().getJoiningDate())
                .isEqualTo(LocalDate.of(2024, 4, 25));
    }

    @Test
    @DisplayName("the unambiguous ISO form still works")
    void importStillAcceptsIsoDates() {
        var result = employeeImportService.importCsv(csv(
                "EMP3103,Asha,Rao,asha.rao@company.com,2024-04-25,HO,PERMANENT,,,,\n"));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(employeeRepository.findByEmployeeCode("EMP3103").orElseThrow().getJoiningDate())
                .isEqualTo(LocalDate.of(2024, 4, 25));
    }

    @Test
    @DisplayName("something that is not a date is still refused, and says what is accepted")
    void importRefusesNonsenseDates() {
        var result = employeeImportService.importCsv(csv(
                "EMP3104,Bad,Date,bad.date@company.com,not-a-date,HO,PERMANENT,,,,\n"));

        assertThat(result.imported()).isZero();
        assertThat(result.errors().get(0).message())
                .contains("not a date I recognise")
                .contains("yyyy-MM-dd");
    }
}
