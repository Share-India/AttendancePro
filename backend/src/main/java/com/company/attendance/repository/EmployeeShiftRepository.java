package com.company.attendance.repository;

import com.company.attendance.entity.EmployeeShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeShiftRepository extends JpaRepository<EmployeeShift, Long> {

    /**
     * The assignment in force on a date. Ordered newest-first so a later
     * correction supersedes an earlier row covering the same day.
     */
    @Query("SELECT es FROM EmployeeShift es JOIN FETCH es.shift WHERE es.employee.id = :employeeId AND es.effectiveFrom <= :onDate AND (es.effectiveTo IS NULL OR es.effectiveTo >= :onDate) ORDER BY es.effectiveFrom DESC, es.id DESC")
    List<EmployeeShift> findEffectiveOn(@Param("employeeId") Long employeeId, @Param("onDate") LocalDate onDate);

    default Optional<EmployeeShift> findCurrentFor(Long employeeId, LocalDate onDate) {
        return findEffectiveOn(employeeId, onDate).stream().findFirst();
    }

    @Query("SELECT es FROM EmployeeShift es JOIN FETCH es.shift WHERE es.employee.id = :employeeId ORDER BY es.effectiveFrom DESC")
    List<EmployeeShift> findHistoryFor(@Param("employeeId") Long employeeId);

    /** Open-ended assignments that a new one must close off. */
    @Query("SELECT es FROM EmployeeShift es WHERE es.employee.id = :employeeId AND es.effectiveTo IS NULL AND es.effectiveFrom < :newFrom")
    List<EmployeeShift> findOpenEndedBefore(@Param("employeeId") Long employeeId, @Param("newFrom") LocalDate newFrom);

    long countByShiftId(Long shiftId);
}
