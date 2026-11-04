package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.DesignationRequest;
import com.company.attendance.dto.response.master.DesignationResponse;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Designation;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.DuplicateResourceException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.DesignationRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.service.DesignationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DesignationServiceImpl implements DesignationService {

    private final DesignationRepository designationRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditLogService auditLogService;

    public DesignationServiceImpl(DesignationRepository designationRepository,
                                  DepartmentRepository departmentRepository,
                                  EmployeeRepository employeeRepository,
                                  AuditLogService auditLogService) {
        this.designationRepository = designationRepository;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DesignationResponse> findAll(boolean activeOnly) {
        List<Designation> designations = activeOnly
                ? designationRepository.findByActiveTrueOrderByNameAsc()
                : designationRepository.findAll();
        return designations.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DesignationResponse findById(Long id) {
        return toResponse(require(id));
    }

    @Override
    @Transactional
    public DesignationResponse create(DesignationRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (designationRepository.findByCode(code).isPresent()) {
            throw new DuplicateResourceException("Designation", "code", code);
        }

        Designation designation = new Designation();
        designation.setCode(code);
        apply(designation, request);

        Designation saved = designationRepository.save(designation);
        auditLogService.record(AuditAction.CREATE, "Designation", saved.getId(),
                "Created designation " + saved.getName(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DesignationResponse update(Long id, DesignationRequest request) {
        Designation designation = require(id);
        Object before = snapshot(designation);

        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(designation.getCode())) {
            designationRepository.findByCode(code).ifPresent(existing -> {
                throw new DuplicateResourceException("Designation", "code", code);
            });
            designation.setCode(code);
        }

        apply(designation, request);

        Designation saved = designationRepository.save(designation);
        auditLogService.record(AuditAction.UPDATE, "Designation", saved.getId(),
                "Updated designation " + saved.getName(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Designation designation = require(id);

        long headcount = employeeRepository.countByDesignationIdAndActiveTrue(id);
        if (headcount > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Reassign the " + headcount + " active employee(s) holding this designation first");
        }

        designation.setActive(false);
        designationRepository.save(designation);
        auditLogService.record(AuditAction.DEACTIVATE, "Designation", id,
                "Deactivated designation " + designation.getName());
    }

    // -----------------------------------------------------------------

    private void apply(Designation designation, DesignationRequest request) {
        designation.setName(request.name().trim());
        designation.setDescription(request.description());

        if (request.level() != null) {
            designation.setLevel(request.level().shortValue());
        }

        if (request.departmentId() != null) {
            Department department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Department", request.departmentId()));
            designation.setDepartment(department);
        } else {
            designation.setDepartment(null);
        }

        if (request.active() != null) {
            designation.setActive(request.active());
        }
    }

    private Designation require(Long id) {
        return designationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Designation", id));
    }

    private DesignationResponse toResponse(Designation designation) {
        return new DesignationResponse(
                designation.getId(),
                designation.getCode(),
                designation.getName(),
                designation.getLevel(),
                designation.getDepartment() != null ? designation.getDepartment().getId() : null,
                designation.getDepartment() != null ? designation.getDepartment().getName() : null,
                designation.getDescription(),
                designation.isActive(),
                designation.getId() != null
                        ? employeeRepository.countByDesignationIdAndActiveTrue(designation.getId())
                        : 0);
    }

    private Object snapshot(Designation designation) {
        return Map.of(
                "code", designation.getCode(),
                "name", designation.getName(),
                "level", designation.getLevel(),
                "active", designation.isActive());
    }
}
