package com.company.attendance.service;

import com.company.attendance.dto.request.master.OfficeLocationRequest;
import com.company.attendance.dto.request.master.OfficeRequest;
import com.company.attendance.dto.response.master.OfficeLocationResponse;
import com.company.attendance.dto.response.master.OfficeResponse;

import java.util.List;

public interface OfficeService {

    List<OfficeResponse> findAll(boolean activeOnly);

    OfficeResponse findById(Long id);

    OfficeResponse create(OfficeRequest request);

    OfficeResponse update(Long id, OfficeRequest request);

    void deactivate(Long id);

    // --- Geofence circles ---

    List<OfficeLocationResponse> findLocations(Long officeId);

    OfficeLocationResponse addLocation(Long officeId, OfficeLocationRequest request);

    OfficeLocationResponse updateLocation(Long officeId, Long locationId, OfficeLocationRequest request);

    void deactivateLocation(Long officeId, Long locationId);
}
