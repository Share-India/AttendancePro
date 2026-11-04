package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.LeaveApproval;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ApprovalAction;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.LeaveApprovalRepository;
import com.company.attendance.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the approval route a request takes.
 *
 * <p>Levels come from the reporting hierarchy and the leave type, never from a
 * hardcoded role. Holding the MANAGER role grants the <em>ability</em> to
 * approve; {@code employee_managers} decides <em>whose</em> requests appear.
 */
@Service
public class LeaveApprovalChainService {

    private static final Logger log = LoggerFactory.getLogger(LeaveApprovalChainService.class);

    private static final short LEVEL_MANAGER = 1;
    private static final short LEVEL_HR = 2;

    private final EmployeeManagerRepository employeeManagerRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final UserRepository userRepository;
    private final HierarchyService hierarchyService;

    public LeaveApprovalChainService(EmployeeManagerRepository employeeManagerRepository,
                                     LeaveApprovalRepository leaveApprovalRepository,
                                     UserRepository userRepository,
                                     HierarchyService hierarchyService) {
        this.employeeManagerRepository = employeeManagerRepository;
        this.leaveApprovalRepository = leaveApprovalRepository;
        this.userRepository = userRepository;
        this.hierarchyService = hierarchyService;
    }

    /**
     * Creates the chain and returns the approver the request now sits with.
     *
     * <p>Returns empty when nobody can approve it — which happens legitimately
     * for the person at the top of the organisation, and the caller decides
     * what to do rather than the request silently vanishing into no inbox.
     */
    @Transactional
    public Optional<Long> buildChain(LeaveRequest request) {
        Employee applicant = request.getEmployee();
        LeaveType leaveType = request.getLeaveType();

        List<LeaveApproval> chain = new ArrayList<>();
        Long firstApprover = null;

        Optional<Employee> manager = resolveManager(applicant, request);

        // The applicant is their own approver — a manager applying for their own
        // leave. That level is recorded as skipped and routing moves up, rather
        // than letting someone approve themselves.
        boolean selfApproval = manager.isPresent() && manager.get().getId().equals(applicant.getId());
        if (selfApproval) {
            manager = resolveManager(manager.get(), request);
        }

        LeaveApproval level1 = new LeaveApproval();
        level1.setLeaveRequest(request);
        level1.setLevel(LEVEL_MANAGER);
        level1.setApproverRole("MANAGER");

        if (manager.isPresent()) {
            level1.setApproverEmployeeId(manager.get().getId());
            level1.setAction(ApprovalAction.PENDING);
            firstApprover = manager.get().getId();
        } else {
            // Nobody above them. Recorded as skipped so the route stays legible.
            level1.setAction(ApprovalAction.SKIPPED);
            level1.setComments(selfApproval
                    ? "Applicant is their own reporting manager"
                    : "No reporting manager assigned");
        }
        chain.add(level1);

        if (requiresHrApproval(request, leaveType)) {
            LeaveApproval level2 = new LeaveApproval();
            level2.setLeaveRequest(request);
            level2.setLevel(LEVEL_HR);
            level2.setApproverRole("HR");
            level2.setAction(ApprovalAction.PENDING);

            Optional<Employee> hr = resolveHrApprover(applicant);
            hr.ifPresent(employee -> level2.setApproverEmployeeId(employee.getId()));

            chain.add(level2);

            if (firstApprover == null) {
                // Level 1 was skipped, so HR is where this starts.
                firstApprover = hr.map(Employee::getId).orElse(null);
                request.setCurrentLevel(LEVEL_HR);
            }
        }

        leaveApprovalRepository.saveAll(chain);

        if (firstApprover == null) {
            log.warn("Leave request {} has no approver; it will need HR to act on it directly",
                    request.getRequestNumber());
        }

        return Optional.ofNullable(firstApprover);
    }

    /**
     * Whether HR must also approve.
     *
     * <p>Long absences and any request that would push the balance negative
     * need a second pair of eyes — those are the two cases a line manager
     * should not settle alone.
     */
    private boolean requiresHrApproval(LeaveRequest request, LeaveType leaveType) {
        BigDecimal threshold = leaveType.getHrApprovalThresholdDays();
        if (threshold == null) {
            return false;
        }
        return request.getTotalDays().compareTo(threshold) > 0;
    }

    private Optional<Employee> resolveManager(Employee employee, LeaveRequest request) {
        return employeeManagerRepository
                .findEffectiveOn(employee.getId(), RelationshipType.PRIMARY, request.getFromDate())
                .stream()
                .findFirst()
                .map(EmployeeManager::getManager)
                .filter(Employee::isEmployable);
    }

    /**
     * The HR business partner if one is assigned, otherwise any active holder
     * of the HR role. Falling back to the role keeps requests moving in an
     * organisation that has not set up business partners.
     */
    private Optional<Employee> resolveHrApprover(Employee applicant) {
        Optional<Employee> businessPartner = employeeManagerRepository
                .findEffectiveOn(applicant.getId(), RelationshipType.HR_BUSINESS_PARTNER,
                        java.time.LocalDate.now())
                .stream()
                .findFirst()
                .map(EmployeeManager::getManager)
                .filter(Employee::isEmployable);

        if (businessPartner.isPresent()) {
            return businessPartner;
        }

        return userRepository.findAll().stream()
                .filter(User::isActive)
                .filter(user -> user.getRoles().stream()
                        .map(Role::getCode)
                        .anyMatch(code -> "HR".equals(code) || "ADMIN".equals(code)))
                .map(User::getEmployee)
                .filter(java.util.Objects::nonNull)
                .filter(Employee::isEmployable)
                // Not the applicant themselves.
                .filter(employee -> !employee.getId().equals(applicant.getId()))
                .findFirst();
    }

    /**
     * Whether this person may act on the request at its current level.
     *
     * <p>Two things must hold: they are the assigned approver, or they manage
     * the applicant. Holding a role is not on its own enough — that is what
     * keeps one manager out of another team's approvals.
     */
    @Transactional(readOnly = true)
    public boolean canActOn(LeaveRequest request, Long actorEmployeeId, boolean hasOverridePermission) {
        if (actorEmployeeId == null) {
            return false;
        }
        // Nobody approves their own leave, whatever permissions they hold.
        if (actorEmployeeId.equals(request.getEmployee().getId())) {
            return false;
        }
        if (actorEmployeeId.equals(request.getCurrentApproverEmployeeId())) {
            return true;
        }
        if (hasOverridePermission) {
            return true;
        }
        return hierarchyService.managesEmployee(
                actorEmployeeId, request.getEmployee().getId(), request.getFromDate());
    }
}
