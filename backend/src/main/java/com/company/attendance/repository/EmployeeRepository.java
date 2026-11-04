package com.company.attendance.repository;

import com.company.attendance.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {

    Optional<Employee> findByEmployeeCode(String employeeCode);

    Optional<Employee> findByOfficialEmailIgnoreCase(String officialEmail);

    boolean existsByEmployeeCode(String employeeCode);

    boolean existsByOfficialEmailIgnoreCase(String officialEmail);

    long countByDepartmentIdAndActiveTrue(Long departmentId);

    long countByDesignationIdAndActiveTrue(Long designationId);

    long countByOfficeIdAndActiveTrue(Long officeId);
}
