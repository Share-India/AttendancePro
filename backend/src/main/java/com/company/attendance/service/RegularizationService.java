package com.company.attendance.service;

import com.company.attendance.dto.request.request.RegularizationApplyRequest;
import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.RegularizationResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

public interface RegularizationService {

    RegularizationResponse apply(UserPrincipal principal, RegularizationApplyRequest request);

    PageResponse<RegularizationResponse> getMyRequests(UserPrincipal principal, String status, Pageable pageable);

    RegularizationResponse getById(UserPrincipal principal, Long id);

    PageResponse<RegularizationResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable);

    /** Rewrites the day and records what it looked like before. */
    RegularizationResponse approve(UserPrincipal principal, Long id, RequestDecisionRequest decision);

    RegularizationResponse reject(UserPrincipal principal, Long id, RequestDecisionRequest decision);

    RegularizationResponse withdraw(UserPrincipal principal, Long id, String reason);
}
