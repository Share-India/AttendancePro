package com.company.attendance.service;

import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.OvertimePolicy;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.LocationPolicyRepository;
import com.company.attendance.repository.OvertimePolicyRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves which policy applies to an employee.
 *
 * <p>Most specific scope wins: EMPLOYEE, then DEPARTMENT, EMPLOYMENT_TYPE,
 * OFFICE, and finally GLOBAL. Ties break on explicit priority, then on the most
 * recently effective assignment.
 *
 * <p>Nothing in the attendance path may read a threshold any other way. If this
 * returns nothing, that is a configuration error and the caller is told so —
 * silently falling back to a hardcoded default is exactly the failure mode the
 * whole policy layer exists to prevent.
 */
@Service
public class PolicyResolutionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyResolutionService.class);

    /** Higher wins. */
    private static final Map<String, Integer> SCOPE_SPECIFICITY = Map.of(
            "EMPLOYEE", 50,
            "DEPARTMENT", 40,
            "EMPLOYMENT_TYPE", 30,
            "OFFICE", 20,
            "GLOBAL", 10);

    private final PolicyAssignmentRepository policyAssignmentRepository;
    private final AttendancePolicyRepository attendancePolicyRepository;
    private final LocationPolicyRepository locationPolicyRepository;
    private final OvertimePolicyRepository overtimePolicyRepository;

    public PolicyResolutionService(PolicyAssignmentRepository policyAssignmentRepository,
                                   AttendancePolicyRepository attendancePolicyRepository,
                                   LocationPolicyRepository locationPolicyRepository,
                                   OvertimePolicyRepository overtimePolicyRepository) {
        this.policyAssignmentRepository = policyAssignmentRepository;
        this.attendancePolicyRepository = attendancePolicyRepository;
        this.locationPolicyRepository = locationPolicyRepository;
        this.overtimePolicyRepository = overtimePolicyRepository;
    }

    @Transactional(readOnly = true)
    public AttendancePolicy resolveAttendancePolicy(Employee employee, LocalDate onDate) {
        Long policyId = resolvePolicyId(PolicyType.ATTENDANCE, employee, onDate);
        return attendancePolicyRepository.findById(policyId)
                .orElseThrow(() -> missing("attendance", policyId));
    }

    @Transactional(readOnly = true)
    public LocationPolicy resolveLocationPolicy(Employee employee, LocalDate onDate) {
        Long policyId = resolvePolicyId(PolicyType.LOCATION, employee, onDate);
        return locationPolicyRepository.findById(policyId)
                .orElseThrow(() -> missing("location", policyId));
    }

    @Transactional(readOnly = true)
    public Optional<OvertimePolicy> resolveOvertimePolicy(Employee employee, LocalDate onDate) {
        try {
            Long policyId = resolvePolicyId(PolicyType.OVERTIME, employee, onDate);
            return overtimePolicyRepository.findById(policyId);
        } catch (BusinessException e) {
            // Overtime is optional: an organisation that does not pay it simply
            // assigns no policy, and no overtime is recorded.
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------

    private Long resolvePolicyId(PolicyType type, Employee employee, LocalDate onDate) {
        List<PolicyAssignment> candidates = policyAssignmentRepository.findActiveByType(type).stream()
                .filter(assignment -> assignment.isEffectiveOn(onDate))
                .filter(assignment -> matchesScope(assignment, employee))
                .sorted(Comparator
                        .comparingInt((PolicyAssignment a) -> specificity(a.getScopeType()))
                        .thenComparingInt(PolicyAssignment::getPriority)
                        .thenComparing(a -> a.getEffectiveFrom() == null ? LocalDate.MIN : a.getEffectiveFrom())
                        .reversed())
                .toList();

        if (candidates.isEmpty()) {
            log.error("No {} policy resolves for employee {} on {}. "
                            + "At minimum a GLOBAL assignment must exist.",
                    type, employee != null ? employee.getEmployeeCode() : "?", onDate);
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "No " + type.name().toLowerCase() + " policy is configured for this employee. "
                            + "Ask an administrator to assign one.");
        }

        return candidates.get(0).getPolicyId();
    }

    private boolean matchesScope(PolicyAssignment assignment, Employee employee) {
        String scope = assignment.getScopeType();

        if ("GLOBAL".equals(scope)) {
            return true;
        }
        if (employee == null) {
            return false;
        }

        return switch (scope) {
            case "EMPLOYEE" -> employee.getId().equals(assignment.getScopeId());
            case "DEPARTMENT" -> employee.getDepartment() != null
                    && employee.getDepartment().getId().equals(assignment.getScopeId());
            case "OFFICE" -> employee.getOffice() != null
                    && employee.getOffice().getId().equals(assignment.getScopeId());
            case "EMPLOYMENT_TYPE" -> employee.getEmploymentType().name().equals(assignment.getScopeValue());
            default -> false;
        };
    }

    private int specificity(String scopeType) {
        return SCOPE_SPECIFICITY.getOrDefault(scopeType, 0);
    }

    private BusinessException missing(String kind, Long policyId) {
        log.error("Policy assignment points at {} policy {} which does not exist", kind, policyId);
        return new BusinessException(ErrorCode.POLICY_VIOLATION,
                "The configured " + kind + " policy is missing. Ask an administrator to review it.");
    }
}
