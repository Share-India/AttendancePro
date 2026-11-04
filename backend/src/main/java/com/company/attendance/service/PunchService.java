package com.company.attendance.service;

import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.security.UserPrincipal;

/**
 * Punch in and out. Identity always comes from the principal, never from the
 * request body.
 */
public interface PunchService {

    Attendance punchIn(UserPrincipal principal, PunchRequest request);

    Attendance punchOut(UserPrincipal principal, PunchRequest request);
}
