package com.company.attendance.repository;

import com.company.attendance.entity.AttendanceRegularization;
import com.company.attendance.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRegularizationRepository extends JpaRepository<AttendanceRegularization, Long> {

    Page<AttendanceRegularization> findByEmployeeIdOrderByAppliedAtDesc(Long employeeId, Pageable pageable);

    Page<AttendanceRegularization> findByEmployeeIdAndStatusOrderByAppliedAtDesc(
            Long employeeId, RequestStatus status, Pageable pageable);

    /** One open request per day: a second would race the first on approval. */
    @Query("""
            SELECT r FROM AttendanceRegularization r
             WHERE r.employee.id = :employeeId
               AND r.attendanceDate = :date
               AND r.status = 'PENDING'
            """)
    Optional<AttendanceRegularization> findPendingForDate(@Param("employeeId") Long employeeId,
                                                          @Param("date") LocalDate date);

    /** Counts against the monthly cap. Only decided-or-pending ones count. */
    @Query("""
            SELECT COUNT(r) FROM AttendanceRegularization r
             WHERE r.employee.id = :employeeId
               AND r.attendanceDate BETWEEN :from AND :to
               AND r.status IN ('PENDING', 'APPROVED')
            """)
    long countInPeriod(@Param("employeeId") Long employeeId,
                       @Param("from") LocalDate from,
                       @Param("to") LocalDate to);

    /**
     * Corrections waiting on this approver.
     *
     * <p>Matches the decision path condition for condition — assigned to them,
     * raised by someone they manage, or (for a caller who may act on any
     * correction) routed to nobody at all. Their own request never appears.
     * See the fuller note on the work-from-home repository.
     */
    @Query("""
            SELECT r FROM AttendanceRegularization r
             JOIN FETCH r.employee e
             WHERE r.status = 'PENDING'
               AND e.id <> :approverId
               AND (r.currentApproverEmployeeId = :approverId
                    OR e.id IN :subordinateIds
                    OR (:includeUnassigned = TRUE AND r.currentApproverEmployeeId IS NULL))
             ORDER BY r.attendanceDate ASC
            """)
    Page<AttendanceRegularization> findPendingForApprover(@Param("approverId") Long approverId,
                                                          @Param("subordinateIds") Collection<Long> subordinateIds,
                                                          @Param("includeUnassigned") boolean includeUnassigned,
                                                          Pageable pageable);

    /** Corrections raised for dates in a range, for the regularization report. */
    @Query("""
            SELECT r FROM AttendanceRegularization r
             JOIN FETCH r.employee
             WHERE r.employee.id IN :employeeIds
               AND r.attendanceDate BETWEEN :from AND :to
             ORDER BY r.attendanceDate DESC
            """)
    List<AttendanceRegularization> findForReport(@Param("employeeIds") Collection<Long> employeeIds,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);
}
