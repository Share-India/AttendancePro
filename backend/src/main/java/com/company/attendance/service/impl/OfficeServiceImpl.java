package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.OfficeLocationRequest;
import com.company.attendance.dto.request.master.OfficeRequest;
import com.company.attendance.dto.response.master.OfficeLocationResponse;
import com.company.attendance.dto.response.master.OfficeResponse;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.OfficeLocation;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.LocationType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.DuplicateResourceException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeLocationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.service.OfficeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OfficeServiceImpl implements OfficeService {

    private final OfficeRepository officeRepository;
    private final OfficeLocationRepository officeLocationRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditLogService auditLogService;

    public OfficeServiceImpl(OfficeRepository officeRepository,
                             OfficeLocationRepository officeLocationRepository,
                             EmployeeRepository employeeRepository,
                             AuditLogService auditLogService) {
        this.officeRepository = officeRepository;
        this.officeLocationRepository = officeLocationRepository;
        this.employeeRepository = employeeRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OfficeResponse> findAll(boolean activeOnly) {
        List<Office> offices = activeOnly
                ? officeRepository.findByActiveTrueOrderByNameAsc()
                : officeRepository.findAll();
        return offices.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OfficeResponse findById(Long id) {
        return toResponse(requireOffice(id));
    }

    @Override
    @Transactional
    public OfficeResponse create(OfficeRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (officeRepository.findByCode(code).isPresent()) {
            throw new DuplicateResourceException("Office", "code", code);
        }

        Office office = new Office();
        office.setCode(code);
        apply(office, request);

        Office saved = officeRepository.save(office);
        auditLogService.record(AuditAction.CREATE, "Office", saved.getId(),
                "Created office " + saved.getName(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public OfficeResponse update(Long id, OfficeRequest request) {
        Office office = requireOffice(id);
        Object before = snapshot(office);

        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(office.getCode())) {
            officeRepository.findByCode(code).ifPresent(existing -> {
                throw new DuplicateResourceException("Office", "code", code);
            });
            office.setCode(code);
        }

        apply(office, request);

        Office saved = officeRepository.save(office);
        auditLogService.record(AuditAction.UPDATE, "Office", saved.getId(),
                "Updated office " + saved.getName(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Office office = requireOffice(id);

        long headcount = employeeRepository.countByOfficeIdAndActiveTrue(id);
        if (headcount > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Move the " + headcount + " active employee(s) at this office before deactivating it");
        }

        office.setActive(false);
        officeRepository.save(office);
        auditLogService.record(AuditAction.DEACTIVATE, "Office", id,
                "Deactivated office " + office.getName());
    }

    // -----------------------------------------------------------------
    // Geofence circles
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<OfficeLocationResponse> findLocations(Long officeId) {
        requireOffice(officeId);
        return officeLocationRepository.findByOfficeIdOrderByLocationNameAsc(officeId).stream()
                .map(this::toLocationResponse)
                .toList();
    }

    @Override
    @Transactional
    public OfficeLocationResponse addLocation(Long officeId, OfficeLocationRequest request) {
        Office office = requireOffice(officeId);

        OfficeLocation location = new OfficeLocation();
        location.setOffice(office);
        applyLocation(location, request);

        OfficeLocation saved = officeLocationRepository.save(location);
        auditLogService.record(AuditAction.CREATE, "OfficeLocation", saved.getId(),
                "Added geofence " + saved.getLocationName() + " to " + office.getName(),
                null, locationSnapshot(saved));
        return toLocationResponse(saved);
    }

    @Override
    @Transactional
    public OfficeLocationResponse updateLocation(Long officeId, Long locationId, OfficeLocationRequest request) {
        OfficeLocation location = requireLocation(officeId, locationId);
        Object before = locationSnapshot(location);

        applyLocation(location, request);

        OfficeLocation saved = officeLocationRepository.save(location);
        // Geofence changes are security-relevant, so the before/after coordinates
        // and radius are recorded rather than just "updated".
        auditLogService.record(AuditAction.UPDATE, "OfficeLocation", saved.getId(),
                "Updated geofence " + saved.getLocationName(), before, locationSnapshot(saved));
        return toLocationResponse(saved);
    }

    @Override
    @Transactional
    public void deactivateLocation(Long officeId, Long locationId) {
        OfficeLocation location = requireLocation(officeId, locationId);

        // Deactivating the last live circle would leave everyone at this office
        // unable to punch, with a rejection that names no reason.
        long remaining = officeLocationRepository.countByOfficeIdAndActiveTrue(officeId);
        if (location.isActive() && remaining <= 1
                && employeeRepository.countByOfficeIdAndActiveTrue(officeId) > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This is the only active geofence for an office with staff. "
                            + "Add another before deactivating this one.");
        }

        location.setActive(false);
        officeLocationRepository.save(location);
        auditLogService.record(AuditAction.DEACTIVATE, "OfficeLocation", locationId,
                "Deactivated geofence " + location.getLocationName());
    }

    // -----------------------------------------------------------------

    private void apply(Office office, OfficeRequest request) {
        office.setName(request.name().trim());
        office.setAddressLine1(request.addressLine1());
        office.setAddressLine2(request.addressLine2());
        office.setCity(request.city());
        office.setState(request.state());
        office.setCountry(request.country());
        office.setPostalCode(request.postalCode());

        // A bad zone id here would silently shift every business date for
        // everyone posted to this office, so it is checked, not trusted.
        String timezone = request.timezone().trim();
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "'" + timezone + "' is not a recognised IANA timezone, for example Asia/Kolkata");
        }
        office.setTimezone(timezone);

        if (request.active() != null) {
            office.setActive(request.active());
        }
    }

    private void applyLocation(OfficeLocation location, OfficeLocationRequest request) {
        location.setLocationName(request.locationName().trim());
        location.setAddress(request.address());
        location.setLatitude(request.latitude());
        location.setLongitude(request.longitude());
        location.setAllowedRadiusMeters(request.allowedRadiusMeters());

        if (request.locationType() != null && !request.locationType().isBlank()) {
            try {
                location.setLocationType(LocationType.valueOf(
                        request.locationType().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Location type must be one of OFFICE, BRANCH, CLIENT_SITE or WAREHOUSE");
            }
        }
        if (request.openToAll() != null) {
            location.setOpenToAll(request.openToAll());
        }
        if (request.active() != null) {
            location.setActive(request.active());
        }
    }

    private Office requireOffice(Long id) {
        return officeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Office", id));
    }

    private OfficeLocation requireLocation(Long officeId, Long locationId) {
        OfficeLocation location = officeLocationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Office location", locationId));
        // Guards against updating another office's geofence by guessing its id.
        if (!location.getOffice().getId().equals(officeId)) {
            throw new ResourceNotFoundException("Office location", locationId);
        }
        return location;
    }

    private OfficeResponse toResponse(Office office) {
        List<OfficeLocationResponse> locations = office.getId() != null
                ? officeLocationRepository.findByOfficeIdOrderByLocationNameAsc(office.getId()).stream()
                        .map(this::toLocationResponse).toList()
                : List.of();

        return new OfficeResponse(
                office.getId(),
                office.getCode(),
                office.getName(),
                office.getAddressLine1(),
                office.getAddressLine2(),
                office.getCity(),
                office.getState(),
                office.getCountry(),
                office.getPostalCode(),
                office.getTimezone(),
                office.isActive(),
                office.getId() != null ? employeeRepository.countByOfficeIdAndActiveTrue(office.getId()) : 0,
                locations);
    }

    private OfficeLocationResponse toLocationResponse(OfficeLocation location) {
        return new OfficeLocationResponse(
                location.getId(),
                location.getOffice().getId(),
                location.getOffice().getName(),
                location.getLocationName(),
                location.getAddress(),
                location.getLatitude(),
                location.getLongitude(),
                location.getAllowedRadiusMeters(),
                location.getLocationType().name(),
                location.isOpenToAll(),
                location.isActive());
    }

    private Object snapshot(Office office) {
        return Map.of(
                "code", office.getCode(),
                "name", office.getName(),
                "timezone", office.getTimezone(),
                "active", office.isActive());
    }

    private Object locationSnapshot(OfficeLocation location) {
        return Map.of(
                "locationName", location.getLocationName(),
                "latitude", location.getLatitude(),
                "longitude", location.getLongitude(),
                "allowedRadiusMeters", location.getAllowedRadiusMeters(),
                "active", location.isActive());
    }
}
