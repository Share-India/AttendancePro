package com.company.attendance.service;

import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.request.request.WorkFromHomeApplyRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.WorkFromHomeResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface WorkFromHomeService {

    WorkFromHomeResponse apply(UserPrincipal principal, WorkFromHomeApplyRequest request);

    PageResponse<WorkFromHomeResponse> getMyRequests(UserPrincipal principal, String status, Pageable pageable);

    WorkFromHomeResponse getById(UserPrincipal principal, Long id);

    PageResponse<WorkFromHomeResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable);

    WorkFromHomeResponse approve(UserPrincipal principal, Long id, RequestDecisionRequest decision);

    WorkFromHomeResponse reject(UserPrincipal principal, Long id, RequestDecisionRequest decision);

    WorkFromHomeResponse withdraw(UserPrincipal principal, Long id, String reason);

    WorkFromHomeResponse cancel(UserPrincipal principal, Long id, String reason);

    /**
     * Whether an approved request covers this date.
     *
     * <p>Called by punch-in on every punch, which is why it exists on the
     * interface rather than being reached through a request lookup.
     */
    boolean isApprovedOn(Long employeeId, LocalDate date);
}
