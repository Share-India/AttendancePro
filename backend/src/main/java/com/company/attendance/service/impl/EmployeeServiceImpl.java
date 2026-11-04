package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.filter.EmployeeFilter;
import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.request.employee.EmployeeRoleAssignmentRequest;
import com.company.attendance.dto.request.employee.EmployeeSelfUpdateRequest;
import com.company.attendance.dto.request.employee.EmployeeUpdateRequest;
import com.company.attendance.dto.request.master.ManagerAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftAssignmentRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.employee.EmployeeDetailResponse;
import com.company.attendance.dto.response.employee.EmployeeSummaryResponse;
import com.company.attendance.dto.response.master.ShiftAssignmentResponse;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Designation;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.User;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.EmploymentStatus;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.DuplicateResourceException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.DesignationRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeSpecifications;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.EmployeeService;
import com.company.attendance.service.HierarchyService;
import com.company.attendance.service.ShiftService;
import com.company.attendance.service.TokenRevocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeServiceImpl.class);

    private static final String PERM_VIEW_ALL = "EMPLOYEE_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "EMPLOYEE_VIEW_TEAM";

    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerRepository employeeManagerRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final OfficeRepository officeRepository;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AccessScopeResolver accessScopeResolver;
    private final HierarchyService hierarchyService;
    private final ShiftService shiftService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository,
                               EmployeeManagerRepository employeeManagerRepository,
                               DepartmentRepository departmentRepository,
                               DesignationRepository designationRepository,
                               OfficeRepository officeRepository,
                               ShiftRepository shiftRepository,
                               UserRepository userRepository,
                               RoleRepository roleRepository,
                               AccessScopeResolver accessScopeResolver,
                               HierarchyService hierarchyService,
                               ShiftService shiftService,
                               TokenRevocationService tokenRevocationService,
                               PasswordEncoder passwordEncoder,
                               AuditLogService auditLogService) {
        this.employeeRepository = employeeRepository;
        this.employeeManagerRepository = employeeManagerRepository;
        this.departmentRepository = departmentRepository;
        this.designationRepository = designationRepository;
        this.officeRepository = officeRepository;
        this.shiftRepository = shiftRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.accessScopeResolver = accessScopeResolver;
        this.hierarchyService = hierarchyService;
        this.shiftService = shiftService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // Reads — always scope-narrowed
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummaryResponse> search(UserPrincipal principal,
                                                        EmployeeFilter filter,
                                                        Pageable pageable) {
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        Specification<Employee> spec = EmployeeSpecifications.from(filter);

        // The scope restriction is ANDed onto the filter rather than applied
        // afterwards, so paging counts reflect what the caller may actually see.
        Optional<Set<Long>> visibleIds = accessScopeResolver.visibleEmployeeIds(principal, scope);
        if (visibleIds.isPresent()) {
            spec = spec.and(EmployeeSpecifications.restrictedTo(visibleIds.get()));
        }

        Page<Employee> page = employeeRepository.findAll(spec, pageable);
        return PageResponse.of(page, this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeDetailResponse findById(UserPrincipal principal, Long employeeId) {
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        accessScopeResolver.assertCanAccess(principal, scope, employeeId);
        return toDetail(requireEmployee(employeeId));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeDetailResponse findSelf(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record");
        }
        return toDetail(requireEmployee(principal.getEmployeeId()));
    }

    // -----------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public EmployeeCreationResult create(EmployeeCreateRequest request) {
        String employeeCode = request.employeeCode().trim().toUpperCase(Locale.ROOT);
        String officialEmail = request.officialEmail().trim().toLowerCase(Locale.ROOT);

        if (employeeRepository.existsByEmployeeCode(employeeCode)) {
            throw new DuplicateResourceException("Employee", "code", employeeCode);
        }
        if (employeeRepository.existsByOfficialEmailIgnoreCase(officialEmail)) {
            throw new DuplicateResourceException("Employee", "official email", officialEmail);
        }
        if (userRepository.existsByUsernameIgnoreCase(employeeCode)
                || userRepository.existsByEmailIgnoreCase(officialEmail)) {
            throw new DuplicateResourceException("Login", "identifier", employeeCode);
        }

        Employee employee = new Employee();
        employee.setEmployeeCode(employeeCode);
        employee.setOfficialEmail(officialEmail);
        employee.setFirstName(request.firstName().trim());
        employee.setMiddleName(blankToNull(request.middleName()));
        employee.setLastName(request.lastName().trim());
        employee.setPersonalEmail(blankToNull(request.personalEmail()));
        employee.setMobile(blankToNull(request.mobile()));
        employee.setAlternateMobile(blankToNull(request.alternateMobile()));
        employee.setDateOfBirth(request.dateOfBirth());
        employee.setGender(blankToNull(request.gender()));
        employee.setBloodGroup(blankToNull(request.bloodGroup()));
        employee.setJoiningDate(request.joiningDate());
        employee.setConfirmationDate(request.confirmationDate());
        employee.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        employee.setEmergencyContactNumber(blankToNull(request.emergencyContactNumber()));
        employee.setEmploymentType(parseEmploymentType(request.employmentType()));
        employee.setEmploymentStatus(EmploymentStatus.ACTIVE);
        employee.setActive(true);

        applyOrgAssignments(employee, request.departmentId(), request.designationId(), request.officeId());

        Employee saved = employeeRepository.save(employee);

        if (request.reportingManagerId() != null) {
            assignManagerInternal(saved, request.reportingManagerId(),
                    RelationshipType.PRIMARY, saved.getJoiningDate(), null);
        }

        assignInitialShift(saved, request.shiftId());
        String temporaryPassword = provisionUser(saved, request.roleCodes());

        auditLogService.record(AuditAction.CREATE, "Employee", saved.getId(),
                "Created employee " + saved.getEmployeeCode() + " (" + saved.getFullName() + ")",
                null, snapshot(saved));

        return new EmployeeCreationResult(toDetail(saved), temporaryPassword);
    }

    /**
     * Creates the login. The password is generated, not supplied: no
     * administrator should ever choose or see another person's working
     * password, and {@code mustChangePassword} forces a rotation on first use.
     */
    private String provisionUser(Employee employee, Set<String> requestedRoles) {
        String temporaryPassword = generateTemporaryPassword();

        User user = new User();
        user.setEmployee(employee);
        user.setUsername(employee.getEmployeeCode());
        user.setEmail(employee.getOfficialEmail());
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setActive(true);
        user.setMustChangePassword(true);

        Set<String> roleCodes = (requestedRoles == null || requestedRoles.isEmpty())
                ? Set.of("EMPLOYEE")
                : requestedRoles;
        user.setRoles(resolveRoles(roleCodes));

        userRepository.save(user);
        return temporaryPassword;
    }

    /**
     * Ambiguous characters are excluded because this password gets read aloud
     * or copied off a screen, and 0/O or 1/l confusion turns into a support
     * ticket that looks like a broken account.
     */
    private String generateTemporaryPassword() {
        final String upper = "ABCDEFGHJKMNPQRSTUVWXYZ";
        final String lower = "abcdefghijkmnpqrstuvwxyz";
        final String digits = "23456789";
        final String symbols = "!@#$%*";
        final String all = upper + lower + digits + symbols;

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        // One of each class up front guarantees the policy is met.
        password.append(upper.charAt(random.nextInt(upper.length())));
        password.append(lower.charAt(random.nextInt(lower.length())));
        password.append(digits.charAt(random.nextInt(digits.length())));
        password.append(symbols.charAt(random.nextInt(symbols.length())));
        for (int i = 4; i < 14; i++) {
            password.append(all.charAt(random.nextInt(all.length())));
        }

        // Shuffle so the class order is not predictable.
        List<Character> characters = new java.util.ArrayList<>();
        for (char c : password.toString().toCharArray()) {
            characters.add(c);
        }
        java.util.Collections.shuffle(characters, random);

        StringBuilder shuffled = new StringBuilder();
        characters.forEach(shuffled::append);
        return shuffled.toString();
    }

    // -----------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public EmployeeDetailResponse update(Long employeeId, EmployeeUpdateRequest request) {
        Employee employee = requireEmployee(employeeId);
        Object before = snapshot(employee);

        String officialEmail = request.officialEmail().trim().toLowerCase(Locale.ROOT);
        if (!officialEmail.equalsIgnoreCase(employee.getOfficialEmail())
                && employeeRepository.existsByOfficialEmailIgnoreCase(officialEmail)) {
            throw new DuplicateResourceException("Employee", "official email", officialEmail);
        }

        employee.setFirstName(request.firstName().trim());
        employee.setMiddleName(blankToNull(request.middleName()));
        employee.setLastName(request.lastName().trim());
        employee.setOfficialEmail(officialEmail);
        employee.setPersonalEmail(blankToNull(request.personalEmail()));
        employee.setMobile(blankToNull(request.mobile()));
        employee.setAlternateMobile(blankToNull(request.alternateMobile()));
        employee.setDateOfBirth(request.dateOfBirth());
        employee.setGender(blankToNull(request.gender()));
        employee.setBloodGroup(blankToNull(request.bloodGroup()));
        employee.setJoiningDate(request.joiningDate());
        employee.setConfirmationDate(request.confirmationDate());
        employee.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        employee.setEmergencyContactNumber(blankToNull(request.emergencyContactNumber()));
        employee.setProfilePhotoUrl(blankToNull(request.profilePhotoUrl()));
        employee.setEmploymentType(parseEmploymentType(request.employmentType()));

        if (request.employmentStatus() != null && !request.employmentStatus().isBlank()) {
            employee.setEmploymentStatus(parseEmploymentStatus(request.employmentStatus()));
        }

        applyOrgAssignments(employee, request.departmentId(), request.designationId(), request.officeId());

        Employee saved = employeeRepository.save(employee);

        // The login mirrors the official email, or sign-in by email breaks.
        syncUserEmail(saved);

        auditLogService.record(AuditAction.UPDATE, "Employee", saved.getId(),
                "Updated employee " + saved.getEmployeeCode(), before, snapshot(saved));
        return toDetail(saved);
    }

    @Override
    @Transactional
    public EmployeeDetailResponse updateSelf(UserPrincipal principal, EmployeeSelfUpdateRequest request) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record");
        }
        Employee employee = requireEmployee(principal.getEmployeeId());
        Object before = snapshot(employee);

        // Only the contact fields an employee owns. Everything that affects
        // attendance or entitlement stays HR-controlled.
        employee.setPersonalEmail(blankToNull(request.personalEmail()));
        employee.setMobile(blankToNull(request.mobile()));
        employee.setAlternateMobile(blankToNull(request.alternateMobile()));
        employee.setBloodGroup(blankToNull(request.bloodGroup()));
        employee.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        employee.setEmergencyContactNumber(blankToNull(request.emergencyContactNumber()));

        Employee saved = employeeRepository.save(employee);
        auditLogService.record(AuditAction.UPDATE, "Employee", saved.getId(),
                "Employee updated their own contact details", before, snapshot(saved));
        return toDetail(saved);
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public void deactivate(Long employeeId, String reason) {
        Employee employee = requireEmployee(employeeId);

        // Someone still has reports; deactivating them would orphan approvals.
        Set<Long> reports = hierarchyService.getSubordinateIds(employeeId, LocalDate.now());
        if (!reports.isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This employee still has " + reports.size()
                            + " report(s). Reassign them to another manager first.");
        }

        Object before = snapshot(employee);
        employee.setActive(false);
        employee.setEmploymentStatus(EmploymentStatus.EXITED);
        if (employee.getExitDate() == null) {
            employee.setExitDate(LocalDate.now());
        }
        employeeRepository.save(employee);

        // Disable the login and kill live sessions, or a deactivated employee
        // keeps working until their access token happens to expire.
        userRepository.findByUsernameIgnoreCase(employee.getEmployeeCode()).ifPresent(user -> {
            user.setActive(false);
            userRepository.save(user);
            tokenRevocationService.revokeAllForUser(user.getId(), "EMPLOYEE_DEACTIVATED");
        });

        auditLogService.record(AuditAction.DEACTIVATE, "Employee", employeeId,
                "Deactivated employee " + employee.getEmployeeCode()
                        + (reason != null && !reason.isBlank() ? " — " + reason : ""),
                before, snapshot(employee));
    }

    @Override
    @Transactional
    public void reactivate(Long employeeId) {
        Employee employee = requireEmployee(employeeId);
        Object before = snapshot(employee);

        employee.setActive(true);
        employee.setEmploymentStatus(EmploymentStatus.ACTIVE);
        employee.setExitDate(null);
        employeeRepository.save(employee);

        userRepository.findByUsernameIgnoreCase(employee.getEmployeeCode()).ifPresent(user -> {
            user.setActive(true);
            user.setFailedLoginAttempts((short) 0);
            user.setLocked(false);
            user.setLockedUntil(null);
            userRepository.save(user);
        });

        auditLogService.record(AuditAction.ACTIVATE, "Employee", employeeId,
                "Reactivated employee " + employee.getEmployeeCode(), before, snapshot(employee));
    }

    // -----------------------------------------------------------------
    // Assignments
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public EmployeeDetailResponse assignManager(Long employeeId, ManagerAssignmentRequest request) {
        Employee employee = requireEmployee(employeeId);
        RelationshipType type = parseRelationshipType(request.relationshipType());

        assignManagerInternal(employee, request.managerEmployeeId(), type,
                request.effectiveFrom(), request.effectiveTo());

        return toDetail(employee);
    }

    private void assignManagerInternal(Employee employee, Long managerId, RelationshipType type,
                                       LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (managerId.equals(employee.getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "An employee cannot report to themselves");
        }

        Employee manager = employeeRepository.findById(managerId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Manager not found: " + managerId));

        if (!manager.isEmployable()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Cannot assign an inactive employee as a manager");
        }

        // Without this the subtree walk would loop on the next TEAM-scoped query.
        if (type == RelationshipType.PRIMARY
                && hierarchyService.wouldCreateCycle(employee.getId(), managerId, LocalDate.now())) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    manager.getFullName() + " reports to " + employee.getFullName()
                            + " (directly or indirectly), so this would create a loop");
        }

        // Close the outgoing relationship of the same type rather than leaving
        // two rows both claiming to be current.
        employeeManagerRepository.findEffectiveOn(employee.getId(), type, effectiveFrom)
                .forEach(existing -> {
                    if (existing.getEffectiveTo() == null) {
                        existing.setEffectiveTo(effectiveFrom.minusDays(1));
                    }
                    existing.setActive(false);
                    employeeManagerRepository.save(existing);
                });

        EmployeeManager assignment = new EmployeeManager();
        assignment.setEmployee(employee);
        assignment.setManager(manager);
        assignment.setRelationshipType(type);
        assignment.setEffectiveFrom(effectiveFrom);
        assignment.setEffectiveTo(effectiveTo);
        assignment.setActive(true);
        employeeManagerRepository.save(assignment);

        auditLogService.record(AuditAction.UPDATE, "EmployeeManager", employee.getId(),
                "Set " + type + " manager of " + employee.getEmployeeCode()
                        + " to " + manager.getEmployeeCode() + " from " + effectiveFrom,
                null, Map.of("manager", manager.getEmployeeCode(), "type", type.name()));
    }

    @Override
    @Transactional
    public EmployeeDetailResponse assignRoles(Long employeeId, EmployeeRoleAssignmentRequest request) {
        Employee employee = requireEmployee(employeeId);
        User user = userRepository.findByUsernameIgnoreCase(employee.getEmployeeCode())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND,
                        "No login exists for " + employee.getEmployeeCode()));

        Set<String> previous = user.getRoles().stream().map(Role::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        user.setRoles(resolveRoles(request.roleCodes()));
        userRepository.save(user);

        // Permissions live in the JWT, so an existing token keeps the old set
        // until it expires. Revoking sessions makes the change take effect now.
        tokenRevocationService.revokeAllForUser(user.getId(), "ROLE_CHANGE");

        auditLogService.record(AuditAction.ROLE_ASSIGN, "User", user.getId(),
                "Changed roles for " + employee.getEmployeeCode(),
                Map.of("roles", previous), Map.of("roles", request.roleCodes()));

        return toDetail(employee);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void applyOrgAssignments(Employee employee, Long departmentId, Long designationId, Long officeId) {
        if (departmentId != null) {
            Department department = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department", departmentId));
            if (!department.isActive()) {
                throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                        "Department " + department.getName() + " is inactive");
            }
            employee.setDepartment(department);
        } else {
            employee.setDepartment(null);
        }

        if (designationId != null) {
            Designation designation = designationRepository.findById(designationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Designation", designationId));
            employee.setDesignation(designation);
        } else {
            employee.setDesignation(null);
        }

        Office office = officeRepository.findById(officeId)
                .orElseThrow(() -> new ResourceNotFoundException("Office", officeId));
        if (!office.isActive()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Office " + office.getName() + " is inactive");
        }
        employee.setOffice(office);
    }

    /**
     * Every employee needs a shift from day one — attendance cannot be computed
     * without one. An explicit shift wins; otherwise the default is used.
     */
    private void assignInitialShift(Employee employee, Long requestedShiftId) {
        Long shiftId = requestedShiftId;
        if (shiftId == null) {
            shiftId = shiftRepository.findFirstByDefaultShiftTrueAndActiveTrue()
                    .map(shift -> shift.getId())
                    .orElse(null);
        }

        if (shiftId == null) {
            log.warn("Employee {} created with no shift: none supplied and no default is configured. "
                    + "Attendance cannot be computed until one is assigned.", employee.getEmployeeCode());
            return;
        }

        shiftService.assign(employee.getId(),
                new ShiftAssignmentRequest(shiftId, employee.getJoiningDate(), null, "Assigned on creation"));
    }

    private void syncUserEmail(Employee employee) {
        userRepository.findByUsernameIgnoreCase(employee.getEmployeeCode()).ifPresent(user -> {
            if (!user.getEmail().equalsIgnoreCase(employee.getOfficialEmail())) {
                user.setEmail(employee.getOfficialEmail());
                userRepository.save(user);
            }
        });
    }

    private Set<Role> resolveRoles(Set<String> roleCodes) {
        Set<String> normalised = roleCodes.stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<Role> roles = roleRepository.findByCodeIn(normalised);
        if (roles.size() != normalised.size()) {
            Set<String> found = roles.stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet());
            Set<String> missing = new LinkedHashSet<>(normalised);
            missing.removeAll(found);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown role(s): " + missing);
        }
        return new LinkedHashSet<>(roles);
    }

    private Employee requireEmployee(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee not found: " + id));
    }

    private EmploymentType parseEmploymentType(String value) {
        try {
            return EmploymentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Employment type must be one of PERMANENT, CONTRACT, INTERN or CONSULTANT");
        }
    }

    private EmploymentStatus parseEmploymentStatus(String value) {
        try {
            return EmploymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Employment status must be one of ACTIVE, ON_NOTICE, EXITED or SUSPENDED");
        }
    }

    private RelationshipType parseRelationshipType(String value) {
        if (value == null || value.isBlank()) {
            return RelationshipType.PRIMARY;
        }
        try {
            return RelationshipType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Relationship type must be PRIMARY, DOTTED_LINE or HR_BUSINESS_PARTNER");
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private EmployeeSummaryResponse toSummary(Employee employee) {
        return new EmployeeSummaryResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getOfficialEmail(),
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getDesignation() != null ? employee.getDesignation().getName() : null,
                employee.getOffice() != null ? employee.getOffice().getName() : null,
                employee.getEmploymentType().name(),
                employee.getEmploymentStatus().name(),
                employee.getJoiningDate(),
                employee.getProfilePhotoUrl(),
                employee.isActive());
    }

    private EmployeeDetailResponse toDetail(Employee employee) {
        Optional<EmployeeManager> manager =
                employeeManagerRepository.findPrimaryManagerOn(employee.getId(), LocalDate.now());

        List<ShiftAssignmentResponse> shiftHistory = shiftService.findAssignmentHistory(employee.getId());
        ShiftAssignmentResponse currentShift = shiftHistory.stream()
                .filter(ShiftAssignmentResponse::current)
                .findFirst()
                .orElse(null);

        Optional<User> user = userRepository.findByUsernameIgnoreCase(employee.getEmployeeCode());

        return new EmployeeDetailResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFirstName(),
                employee.getMiddleName(),
                employee.getLastName(),
                employee.getFullName(),
                employee.getOfficialEmail(),
                employee.getPersonalEmail(),
                employee.getMobile(),
                employee.getAlternateMobile(),
                employee.getDateOfBirth(),
                employee.getGender(),
                employee.getBloodGroup(),
                employee.getJoiningDate(),
                employee.getConfirmationDate(),
                employee.getExitDate(),
                employee.getDepartment() != null ? employee.getDepartment().getId() : null,
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getDesignation() != null ? employee.getDesignation().getId() : null,
                employee.getDesignation() != null ? employee.getDesignation().getName() : null,
                employee.getOffice() != null ? employee.getOffice().getId() : null,
                employee.getOffice() != null ? employee.getOffice().getName() : null,
                employee.getOffice() != null ? employee.getOffice().getTimezone() : null,
                employee.getEmploymentType().name(),
                employee.getEmploymentStatus().name(),
                employee.getProfilePhotoUrl(),
                employee.getEmergencyContactName(),
                employee.getEmergencyContactNumber(),
                employee.isActive(),
                manager.map(m -> m.getManager().getId()).orElse(null),
                manager.map(m -> m.getManager().getFullName()).orElse(null),
                currentShift,
                shiftHistory,
                user.map(User::getId).orElse(null),
                user.map(User::getUsername).orElse(null),
                user.map(u -> u.getRoles().stream().map(Role::getCode)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                        .orElse(new LinkedHashSet<>()),
                user.map(User::isActive).orElse(false),
                user.map(User::isCurrentlyLocked).orElse(false));
    }

    private Object snapshot(Employee employee) {
        Map<String, Object> values = new HashMap<>();
        values.put("employeeCode", employee.getEmployeeCode());
        values.put("fullName", employee.getFullName());
        values.put("officialEmail", employee.getOfficialEmail());
        values.put("department", employee.getDepartment() != null ? employee.getDepartment().getCode() : null);
        values.put("designation", employee.getDesignation() != null ? employee.getDesignation().getCode() : null);
        values.put("office", employee.getOffice() != null ? employee.getOffice().getCode() : null);
        values.put("employmentType", employee.getEmploymentType().name());
        values.put("employmentStatus", employee.getEmploymentStatus().name());
        values.put("active", employee.isActive());
        return values;
    }
}
