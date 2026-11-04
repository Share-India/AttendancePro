package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.DepartmentRequest;
import com.company.attendance.dto.response.master.DepartmentResponse;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Employee;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.DuplicateResourceException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.service.DepartmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditLogService auditLogService;

    public DepartmentServiceImpl(DepartmentRepository departmentRepository,
                                 EmployeeRepository employeeRepository,
                                 AuditLogService auditLogService) {
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentResponse> findAll(boolean activeOnly) {
        List<Department> departments = activeOnly
                ? departmentRepository.findByActiveTrueOrderByNameAsc()
                : departmentRepository.findAll();
        return departments.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentResponse findById(Long id) {
        return toResponse(require(id));
    }

    @Override
    @Transactional
    public DepartmentResponse create(DepartmentRequest request) {
        String code = request.code().trim().toUpperCase();
        if (departmentRepository.findByCode(code).isPresent()) {
            throw new DuplicateResourceException("Department", "code", code);
        }

        Department department = new Department();
        department.setCode(code);
        apply(department, request);

        Department saved = departmentRepository.save(department);
        auditLogService.record(AuditAction.CREATE, "Department", saved.getId(),
                "Created department " + saved.getName(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public DepartmentResponse update(Long id, DepartmentRequest request) {
        Department department = require(id);
        Object before = snapshot(department);

        String code = request.code().trim().toUpperCase();
        if (!code.equals(department.getCode())) {
            departmentRepository.findByCode(code).ifPresent(existing -> {
                throw new DuplicateResourceException("Department", "code", code);
            });
            department.setCode(code);
        }

        apply(department, request);

        Department saved = departmentRepository.save(department);
        auditLogService.record(AuditAction.UPDATE, "Department", saved.getId(),
                "Updated department " + saved.getName(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Department department = require(id);

        // Masters are never hard-deleted: attendance, leave and audit rows keep
        // referencing them. Deactivating hides it from pickers instead.
        long headcount = employeeRepository.countByDepartmentIdAndActiveTrue(id);
        if (headcount > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Move the " + headcount + " active employee(s) in this department before deactivating it");
        }

        department.setActive(false);
        departmentRepository.save(department);
        auditLogService.record(AuditAction.DEACTIVATE, "Department", id,
                "Deactivated department " + department.getName());
    }

    // -----------------------------------------------------------------

    private void apply(Department department, DepartmentRequest request) {
        department.setName(request.name().trim());
        department.setDescription(request.description());

        if (request.parentDepartmentId() != null) {
            if (request.parentDepartmentId().equals(department.getId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "A department cannot be its own parent");
            }
            Department parent = require(request.parentDepartmentId());
            assertNoCycle(department, parent);
            department.setParentDepartment(parent);
        } else {
            department.setParentDepartment(null);
        }

        if (request.departmentHeadEmployeeId() != null) {
            Employee head = employeeRepository.findById(request.departmentHeadEmployeeId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            ErrorCode.EMPLOYEE_NOT_FOUND, "Department head not found"));
            department.setDepartmentHead(head);
        } else {
            department.setDepartmentHead(null);
        }

        if (request.active() != null) {
            department.setActive(request.active());
        }
    }

    /** Walking up from the proposed parent must never arrive back at this department. */
    private void assertNoCycle(Department department, Department proposedParent) {
        if (department.getId() == null) {
            return;
        }
        Department cursor = proposedParent;
        int guard = 0;
        while (cursor != null && guard++ < 20) {
            if (department.getId().equals(cursor.getId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "That parent would create a loop in the department hierarchy");
            }
            cursor = cursor.getParentDepartment();
        }
    }

    private Department require(Long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", id));
    }

    private DepartmentResponse toResponse(Department department) {
        return new DepartmentResponse(
                department.getId(),
                department.getCode(),
                department.getName(),
                department.getParentDepartment() != null ? department.getParentDepartment().getId() : null,
                department.getParentDepartment() != null ? department.getParentDepartment().getName() : null,
                department.getDepartmentHead() != null ? department.getDepartmentHead().getId() : null,
                department.getDepartmentHead() != null ? department.getDepartmentHead().getFullName() : null,
                department.getDescription(),
                department.isActive(),
                department.getId() != null
                        ? employeeRepository.countByDepartmentIdAndActiveTrue(department.getId())
                        : 0);
    }

    private Object snapshot(Department department) {
        return java.util.Map.of(
                "code", department.getCode(),
                "name", department.getName(),
                "active", department.isActive());
    }
}
