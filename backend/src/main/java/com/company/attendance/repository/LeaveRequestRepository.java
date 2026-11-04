package com.company.attendance.repository;

import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long>,
        JpaSpecificationExecutor<LeaveRequest> {

    Optional<LeaveRequest> findByRequestNumber(String requestNumber);

    Page<LeaveRequest> findByEmployeeIdOrderByAppliedAtDesc(Long employeeId, Pageable pageable);

    Page<LeaveRequest> findByEmployeeIdAndStatusOrderByAppliedAtDesc(
            Long employeeId, RequestStatus status, Pageable pageable);

    /**
     * Dates the employee has already committed to.
     *
     * <p>Joins through the exploded day rows rather than comparing ranges: a
     * range comparison has to reason about overlap in four directions and gets
     * half-days wrong, while a date-by-date match cannot.
     *
     * <p>Only PENDING and APPROVED block — a rejected or cancelled request
     * leaves its dates free again.
     */
    @Query("""
            SELECT DISTINCT d.leaveDate FROM LeaveRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.leaveDate IN :dates
               AND d.leaveRequest.status IN ('PENDING', 'APPROVED')
               AND (:excludeRequestId IS NULL OR d.leaveRequest.id <> :excludeRequestId)
            """)
    List<LocalDate> findConflictingDates(@Param("employeeId") Long employeeId,
                                         @Param("dates") Collection<LocalDate> dates,
                                         @Param("excludeRequestId") Long excludeRequestId);

    /** An approver's inbox: one indexed lookup on the denormalised approver id. */
    @Query("""
            SELECT r FROM LeaveRequest r
             JOIN FETCH r.employee e
             JOIN FETCH r.leaveType t
             WHERE r.currentApproverEmployeeId = :approverId
               AND r.status = 'PENDING'
             ORDER BY r.fromDate ASC
            """)
    Page<LeaveRequest> findPendingForApprover(@Param("approverId") Long approverId, Pageable pageable);

    @Query("SELECT COUNT(r) FROM LeaveRequest r WHERE r.currentApproverEmployeeId = :approverId AND r.status = 'PENDING'")
    long countPendingForApprover(@Param("approverId") Long approverId);

    /**
     * Approved leave covering a date. Punch-in uses this to refuse a punch on a
     * day the employee is already on full-day leave.
     */
    @Query("""
            SELECT d.dayPortion FROM LeaveRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.leaveDate = :date
               AND d.deductible = true
               AND d.leaveRequest.status = 'APPROVED'
            """)
    List<DayPortion> findApprovedPortionsOn(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    @Query("""
            SELECT r FROM LeaveRequest r
             JOIN FETCH r.leaveType
             WHERE r.employee.id = :employeeId
               AND r.status = 'APPROVED'
               AND r.toDate >= :from AND r.fromDate <= :to
             ORDER BY r.fromDate
            """)
    List<LeaveRequest> findApprovedBetween(@Param("employeeId") Long employeeId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    /** Leave requests overlapping a range, for the leave register report. */
    @Query("""
            SELECT r FROM LeaveRequest r
             JOIN FETCH r.employee
             JOIN FETCH r.leaveType
             WHERE r.employee.id IN :employeeIds
               AND r.fromDate <= :to
               AND r.toDate >= :from
             ORDER BY r.fromDate DESC
            """)
    List<LeaveRequest> findForReport(@Param("employeeIds") Collection<Long> employeeIds,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}
