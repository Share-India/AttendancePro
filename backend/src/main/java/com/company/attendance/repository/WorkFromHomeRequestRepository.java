package com.company.attendance.repository;

import com.company.attendance.entity.WorkFromHomeRequest;
import com.company.attendance.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;

@Repository
public interface WorkFromHomeRequestRepository extends JpaRepository<WorkFromHomeRequest, Long> {

    Page<WorkFromHomeRequest> findByEmployeeIdOrderByAppliedAtDesc(Long employeeId, Pageable pageable);

    Page<WorkFromHomeRequest> findByEmployeeIdAndStatusOrderByAppliedAtDesc(
            Long employeeId, RequestStatus status, Pageable pageable);

    /**
     * Requests waiting on this approver.
     *
     * <p>Deliberately the same three conditions the decision path allows, so
     * the inbox never shows a request the approver would then be refused, and
     * never hides one they could act on:
     *
     * <ul>
     *   <li>assigned to them;
     *   <li>raised by someone they manage — which also covers a request routed
     *       before the reporting line existed, and a stand-in;
     *   <li>{@code includeUnassigned}: routed to nobody at all. Those exist —
     *       when an applicant has no manager above them and no other HR account
     *       is active, the resolver finds no one. Without this they would sit
     *       PENDING in no inbox, the one outcome an approval queue must never
     *       produce. Only a caller who may act on any request is shown them.
     * </ul>
     *
     * <p>Their own request is excluded whatever else matches: nobody approves
     * themselves, so showing it would only offer an action that is refused.
     */
    @Query("""
            SELECT w FROM WorkFromHomeRequest w
             JOIN FETCH w.employee e
             WHERE w.status = 'PENDING'
               AND e.id <> :approverId
               AND (w.currentApproverEmployeeId = :approverId
                    OR e.id IN :subordinateIds
                    OR (:includeUnassigned = TRUE AND w.currentApproverEmployeeId IS NULL))
             ORDER BY w.fromDate ASC
            """)
    Page<WorkFromHomeRequest> findPendingForApprover(@Param("approverId") Long approverId,
                                                     @Param("subordinateIds") Collection<Long> subordinateIds,
                                                     @Param("includeUnassigned") boolean includeUnassigned,
                                                     Pageable pageable);

    /** Days already committed in a month, for the monthly cap. */
    @Query("""
            SELECT COALESCE(COUNT(d), 0) FROM WfhRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.wfhDate BETWEEN :from AND :to
               AND d.wfhRequest.status IN ('PENDING', 'APPROVED')
            """)
    long countCommittedDaysInPeriod(@Param("employeeId") Long employeeId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);
}
