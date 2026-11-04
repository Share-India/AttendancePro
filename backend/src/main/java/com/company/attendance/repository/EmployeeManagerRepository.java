package com.company.attendance.repository;

import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.enums.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeManagerRepository extends JpaRepository<EmployeeManager, Long> {

    /** The manager in force on a given date - point-in-time, not "current". */
    @Query("SELECT em FROM EmployeeManager em WHERE em.employee.id = :employeeId AND em.relationshipType = :type AND em.effectiveFrom <= :onDate AND (em.effectiveTo IS NULL OR em.effectiveTo >= :onDate) ORDER BY em.effectiveFrom DESC")
    List<EmployeeManager> findEffectiveOn(@Param("employeeId") Long employeeId,
                                          @Param("type") RelationshipType type,
                                          @Param("onDate") LocalDate onDate);

    default Optional<EmployeeManager> findPrimaryManagerOn(Long employeeId, LocalDate onDate) {
        return findEffectiveOn(employeeId, RelationshipType.PRIMARY, onDate).stream().findFirst();
    }

    @Query("SELECT em.employee.id FROM EmployeeManager em WHERE em.manager.id = :managerId AND em.active = true AND em.effectiveFrom <= :onDate AND (em.effectiveTo IS NULL OR em.effectiveTo >= :onDate)")
    List<Long> findDirectReportIds(@Param("managerId") Long managerId, @Param("onDate") LocalDate onDate);
}
