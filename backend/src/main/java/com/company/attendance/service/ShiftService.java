package com.company.attendance.service;

import com.company.attendance.dto.request.master.ShiftAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftRequest;
import com.company.attendance.dto.response.master.ShiftAssignmentResponse;
import com.company.attendance.dto.response.master.ShiftResponse;

import java.time.LocalDate;
import java.util.List;

public interface ShiftService {

    List<ShiftResponse> findAll(boolean activeOnly);

    ShiftResponse findById(Long id);

    ShiftResponse create(ShiftRequest request);

    ShiftResponse update(Long id, ShiftRequest request);

    void deactivate(Long id);

    // --- Assignment ---

    /** Closes any open-ended assignment that would otherwise overlap. */
    ShiftAssignmentResponse assign(Long employeeId, ShiftAssignmentRequest request);

    List<ShiftAssignmentResponse> findAssignmentHistory(Long employeeId);

    /** The shift in force on a date, or empty when none is assigned. */
    java.util.Optional<ShiftAssignmentResponse> findEffectiveShift(Long employeeId, LocalDate onDate);
}
