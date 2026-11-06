package com.company.attendance.service;

import com.company.attendance.dto.filter.EmployeeFilter;
import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.request.employee.EmployeeRoleAssignmentRequest;
import com.company.attendance.dto.request.employee.EmployeeSelfUpdateRequest;
import com.company.attendance.dto.request.employee.EmployeeUpdateRequest;
import com.company.attendance.dto.request.master.ManagerAssignmentRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.employee.EmployeeDetailResponse;
import com.company.attendance.dto.response.employee.EmployeeSummaryResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;


public interface EmployeeService {

    /** Results are always narrowed to the caller's data scope. */
    PageResponse<EmployeeSummaryResponse> search(UserPrincipal principal, EmployeeFilter filter, Pageable pageable);

    EmployeeDetailResponse findById(UserPrincipal principal, Long employeeId);

    EmployeeDetailResponse findSelf(UserPrincipal principal);

    /** Also provisions the login with a generated temporary password. */
    EmployeeCreationResult create(EmployeeCreateRequest request);

    EmployeeDetailResponse update(Long employeeId, EmployeeUpdateRequest request);

    EmployeeDetailResponse updateSelf(UserPrincipal principal, EmployeeSelfUpdateRequest request);

    void deactivate(Long employeeId, String reason);

    void reactivate(Long employeeId);

    EmployeeDetailResponse assignManager(Long employeeId, ManagerAssignmentRequest request);

    EmployeeDetailResponse assignRoles(Long employeeId, EmployeeRoleAssignmentRequest request);

    /**
     * Issues a fresh random first-time password for one account and returns it.
     *
     * <p>This is how an administrator comes to know somebody's first password.
     * The stored value is a hash, so the old password cannot be read back by
     * anyone, including whoever holds every permission in the system — asking
     * to see it issues a new one instead. That is a deliberate trade: HR can
     * always get somebody signed in, and a database dump never yields a
     * working password.
     *
     * <p>Forces a change on the next sign-in and revokes every live session, so
     * somebody already signed in on another device cannot carry on past it.
     *
     * <p>One account at a time, by design. There is deliberately no "reset
     * everybody" counterpart: it would sign the whole company out at once, and
     * the first-day case it would serve is already covered by the bulk import,
     * which returns a password for every person it creates.
     */
    PasswordResetResult resetPassword(Long employeeId);

    /**
     * The temporary password is returned once, to the administrator who created
     * the account, and never stored in readable form or logged.
     */
    record EmployeeCreationResult(EmployeeDetailResponse employee, String temporaryPassword) {
    }

    /**
     * What a reset did.
     *
     * @param reset      whether a password was actually issued. False when the
     *                   employee has no login — reported rather than ignored,
     *                   because that case otherwise looks identical to success
     *                   right up until they try to sign in.
     * @param credential the new password and who it is for, or null when
     *                   {@code reset} is false
     */
    record PasswordResetResult(boolean reset, IssuedCredential credential) {

        public static PasswordResetResult issued(IssuedCredential credential) {
            return new PasswordResetResult(true, credential);
        }

        public static PasswordResetResult noLogin() {
            return new PasswordResetResult(false, null);
        }
    }

    /**
     * A first-time password and who it belongs to.
     *
     * <p>Carries the name as well as the code because this is read by a person
     * about to hand a password over, and an employee code alone is not enough
     * to be sure it is going to the right person.
     */
    record IssuedCredential(String employeeCode, String employeeName, String password) {
    }
}
