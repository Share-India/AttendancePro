package com.company.attendance.service;

import com.company.attendance.dto.request.master.DepartmentRequest;
import com.company.attendance.dto.response.master.DepartmentResponse;

import java.util.List;

public interface DepartmentService {

    List<DepartmentResponse> findAll(boolean activeOnly);

    DepartmentResponse findById(Long id);

    DepartmentResponse create(DepartmentRequest request);

    DepartmentResponse update(Long id, DepartmentRequest request);

    /** Masters are deactivated, never deleted — history keeps referencing them. */
    void deactivate(Long id);
}
