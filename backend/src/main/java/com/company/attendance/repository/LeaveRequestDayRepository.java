package com.company.attendance.repository;

import com.company.attendance.entity.LeaveRequestDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface LeaveRequestDayRepository extends JpaRepository<LeaveRequestDay, Long> {

    List<LeaveRequestDay> findByLeaveRequestIdOrderByLeaveDateAsc(Long leaveRequestId);

    /** Deductible days from a given date onward — what a cancellation refunds. */
    @Query("SELECT d FROM LeaveRequestDay d WHERE d.leaveRequest.id = :requestId AND d.leaveDate >= :from AND d.deductible = true")
    List<LeaveRequestDay> findDeductibleFrom(@Param("requestId") Long requestId, @Param("from") LocalDate from);

    /** Approved leave days in a range, for the attendance calendar. */
    @Query("""
            SELECT d FROM LeaveRequestDay d
             JOIN FETCH d.leaveRequest r
             JOIN FETCH r.leaveType
             WHERE d.employeeId = :employeeId
               AND d.leaveDate BETWEEN :from AND :to
               AND r.status = 'APPROVED'
            """)
    List<LeaveRequestDay> findApprovedBetween(@Param("employeeId") Long employeeId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /** Approved leave days for a set of people on one date. */
    @Query("""
            SELECT d FROM LeaveRequestDay d
             JOIN FETCH d.leaveRequest r
             JOIN FETCH r.leaveType
             WHERE d.leaveDate = :date
               AND d.employeeId IN :employeeIds
               AND r.status = 'APPROVED'
            """)
    List<LeaveRequestDay> findApprovedForEmployeesOn(@Param("employeeIds") Collection<Long> employeeIds,
                                                     @Param("date") LocalDate date);

    /** The same over a range, for the trend chart. */
    @Query("""
            SELECT d FROM LeaveRequestDay d
             WHERE d.leaveDate BETWEEN :from AND :to
               AND d.employeeId IN :employeeIds
               AND d.leaveRequest.status = 'APPROVED'
            """)
    List<LeaveRequestDay> findApprovedForEmployeesBetween(@Param("employeeIds") Collection<Long> employeeIds,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);
}
