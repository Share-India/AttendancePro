package com.company.attendance.security;

import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.service.HierarchyService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * Tier two of the three-tier access check.
 *
 * <p>The endpoint annotation decides whether the caller may use a feature at
 * all; this decides <em>whose rows</em> they may see. Both run, always. A path
 * variable is never trusted on its own — that is exactly how one employee ends
 * up reading another's attendance.
 */
@Component
public class AccessScopeResolver {

    private final HierarchyService hierarchyService;

    public AccessScopeResolver(HierarchyService hierarchyService) {
        this.hierarchyService = hierarchyService;
    }

    /**
     * Widest scope the principal's permissions justify for a resource family.
     * Callers pass the three permission codes for that family.
     */
    public AccessScope resolve(UserPrincipal principal, String viewAllPermission, String viewTeamPermission) {
        if (principal.hasPermission(viewAllPermission)) {
            return AccessScope.ORG;
        }
        if (principal.hasPermission(viewTeamPermission)) {
            return AccessScope.TEAM;
        }
        return AccessScope.SELF;
    }

    /**
     * The employee ids a principal may read, or empty for unrestricted.
     * An empty Optional means ORG scope — do not confuse it with "no access".
     */
    public java.util.Optional<Set<Long>> visibleEmployeeIds(UserPrincipal principal, AccessScope scope) {
        return switch (scope) {
            case ORG -> java.util.Optional.empty();
            case TEAM -> {
                requireEmployee(principal);
                yield java.util.Optional.of(
                        hierarchyService.getTeamScopeIds(principal.getEmployeeId(), LocalDate.now()));
            }
            case SELF -> {
                requireEmployee(principal);
                yield java.util.Optional.of(Set.of(principal.getEmployeeId()));
            }
        };
    }

    /**
     * Asserts the principal may act on one specific employee. Throws rather
     * than returning a boolean so a caller cannot forget to check the result.
     */
    public void assertCanAccess(UserPrincipal principal, AccessScope scope, Long targetEmployeeId) {
        if (targetEmployeeId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Employee id is required");
        }

        switch (scope) {
            case ORG -> {
                // Unrestricted.
            }
            case TEAM -> {
                requireEmployee(principal);
                boolean isSelf = targetEmployeeId.equals(principal.getEmployeeId());
                boolean managesTarget = hierarchyService.managesEmployee(
                        principal.getEmployeeId(), targetEmployeeId, LocalDate.now());
                if (!isSelf && !managesTarget) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED,
                            "That employee is not in your team");
                }
            }
            case SELF -> {
                requireEmployee(principal);
                if (!targetEmployeeId.equals(principal.getEmployeeId())) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED,
                            "You can only access your own records");
                }
            }
        }
    }

    private void requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            // A system or integration account has no employee record, so it has
            // nothing to be "self" or "team" relative to.
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This account is not linked to an employee record");
        }
    }
}
