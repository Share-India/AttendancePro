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
     * The temporary password is returned once, to the administrator who created
     * the account, and never stored in readable form or logged.
     */
    record EmployeeCreationResult(EmployeeDetailResponse employee, String temporaryPassword) {
    }
}
