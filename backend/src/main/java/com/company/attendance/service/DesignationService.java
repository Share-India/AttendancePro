package com.company.attendance.service;

import com.company.attendance.dto.request.master.DesignationRequest;
import com.company.attendance.dto.response.master.DesignationResponse;

import java.util.List;

public interface DesignationService {

    List<DesignationResponse> findAll(boolean activeOnly);

    DesignationResponse findById(Long id);

    DesignationResponse create(DesignationRequest request);

    DesignationResponse update(Long id, DesignationRequest request);

    void deactivate(Long id);
}
