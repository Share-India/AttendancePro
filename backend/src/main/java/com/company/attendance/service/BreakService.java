package com.company.attendance.service;

import com.company.attendance.dto.request.attendance.BreakRequest;
import com.company.attendance.entity.AttendanceBreak;
import com.company.attendance.entity.BreakType;
import com.company.attendance.security.UserPrincipal;

import java.util.List;

public interface BreakService {

    AttendanceBreak startBreak(UserPrincipal principal, BreakRequest request);

    AttendanceBreak endBreak(UserPrincipal principal);

    List<BreakType> findBreakTypes();
}
