package com.company.attendance.service;

import com.company.attendance.dto.request.leave.LeaveApplyRequest;
import com.company.attendance.dto.request.leave.LeaveCancelRequest;
import com.company.attendance.dto.request.leave.LeaveDecisionRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.leave.LeaveApplyResultResponse;
import com.company.attendance.dto.response.leave.LeaveBalanceResponse;
import com.company.attendance.dto.response.leave.LeaveRequestResponse;
import com.company.attendance.dto.response.leave.LeaveTypeResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface LeaveService {

    List<LeaveTypeResponse> findLeaveTypes();

    List<LeaveBalanceResponse> getMyBalances(UserPrincipal principal);

    LeaveApplyResultResponse apply(UserPrincipal principal, LeaveApplyRequest request);

    PageResponse<LeaveRequestResponse> getMyRequests(UserPrincipal principal, String status, Pageable pageable);

    LeaveRequestResponse getById(UserPrincipal principal, Long requestId);

    /** The approver's inbox, scoped to requests actually routed to them. */
    PageResponse<LeaveRequestResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable);

    LeaveRequestResponse approve(UserPrincipal principal, Long requestId, LeaveDecisionRequest decision);

    LeaveRequestResponse reject(UserPrincipal principal, Long requestId, LeaveDecisionRequest decision);

    /** Withdrawn by the applicant before anyone has acted. */
    LeaveRequestResponse withdraw(UserPrincipal principal, Long requestId, LeaveCancelRequest request);

    /** Cancelled after approval. Only future days are refunded. */
    LeaveRequestResponse cancel(UserPrincipal principal, Long requestId, LeaveCancelRequest request);
}
