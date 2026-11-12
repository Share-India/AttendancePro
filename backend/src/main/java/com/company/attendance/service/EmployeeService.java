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
    PasswordResetResult resetPassword(Long employeeId, ResetMethod method);

    /**
     * How an administrator wants somebody helped back into their account.
     *
     * <p>The two differ in more than delivery, and the difference is the thing
     * to understand before choosing:
     *
     * <ul>
     *   <li>{@link #EMAIL_LINK} changes nothing yet. The person's current
     *       password keeps working until they use the link and choose a new
     *       one. That is deliberate — if the mail never arrives, they are no
     *       worse off than before.
     *   <li>{@link #ONE_TIME_PASSWORD} changes the password immediately and
     *       ends every open session. Use it when the old password must stop
     *       working now, or when the person has no working mailbox.
     * </ul>
     */
    enum ResetMethod {
        EMAIL_LINK,
        ONE_TIME_PASSWORD
    }

    /**
     * The temporary password is returned once, to the administrator who created
     * the account, and never stored in readable form or logged.
     */
    record EmployeeCreationResult(EmployeeDetailResponse employee, String temporaryPassword) {
    }

    /**
     * What a reset did.
     *
     * <p>Every outcome is reported rather than thrown, because the two that
     * are not success are both things an administrator can act on — and a
     * silent failure here looks exactly like success right up until the person
     * tries to sign in.
     *
     * @param outcome    what happened
     * @param credential the one-time password and who it is for, set only for
     *                   {@link Outcome#PASSWORD_ISSUED}
     * @param sentTo     the address the link went to, set only for
     *                   {@link Outcome#LINK_SENT}
     */
    record PasswordResetResult(Outcome outcome, IssuedCredential credential, String sentTo) {

        public static PasswordResetResult linkSent(String address) {
            return new PasswordResetResult(Outcome.LINK_SENT, null, address);
        }

        public static PasswordResetResult issued(IssuedCredential credential) {
            return new PasswordResetResult(Outcome.PASSWORD_ISSUED, credential, null);
        }

        public static PasswordResetResult noLogin() {
            return new PasswordResetResult(Outcome.NO_LOGIN, null, null);
        }

        public static PasswordResetResult noEmail() {
            return new PasswordResetResult(Outcome.NO_EMAIL, null, null);
        }
    }

    enum Outcome {
        /** A setup link was mailed. Their current password still works until they use it. */
        LINK_SENT,
        /** A one-time password was issued and is in the result. Sessions were revoked. */
        PASSWORD_ISSUED,
        /** The employee has no login at all, so there was nothing to reset. */
        NO_LOGIN,
        /**
         * A link was asked for and the account has no address. Nothing was
         * changed — invalidating a password we have no way to tell somebody
         * about would lock them out to no purpose. Issue a one-time password
         * instead.
         */
        NO_EMAIL
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
