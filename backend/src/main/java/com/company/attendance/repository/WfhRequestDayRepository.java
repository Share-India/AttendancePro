package com.company.attendance.repository;

import com.company.attendance.entity.WfhRequestDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface WfhRequestDayRepository extends JpaRepository<WfhRequestDay, Long> {

    List<WfhRequestDay> findByWfhRequestIdOrderByWfhDateAsc(Long wfhRequestId);

    /**
     * Whether an approved work-from-home request covers a date.
     *
     * <p>Read by punch-in on every punch, so it is deliberately a single
     * indexed existence check rather than loading the request.
     */
    @Query("""
            SELECT COUNT(d) > 0 FROM WfhRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.wfhDate = :date
               AND d.wfhRequest.status = 'APPROVED'
            """)
    boolean isApprovedOn(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /** Dates already committed, for overlap detection on a new request. */
    @Query("""
            SELECT DISTINCT d.wfhDate FROM WfhRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.wfhDate IN :dates
               AND d.wfhRequest.status IN ('PENDING', 'APPROVED')
            """)
    List<LocalDate> findConflictingDates(@Param("employeeId") Long employeeId,
                                         @Param("dates") Collection<LocalDate> dates);

    @Query("""
            SELECT d FROM WfhRequestDay d
             WHERE d.employeeId = :employeeId
               AND d.wfhDate BETWEEN :from AND :to
               AND d.wfhRequest.status = 'APPROVED'
            """)
    List<WfhRequestDay> findApprovedBetween(@Param("employeeId") Long employeeId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    /** Approved work-from-home days for a set of people on one date. */
    @Query("""
            SELECT d.employeeId FROM WfhRequestDay d
             WHERE d.wfhDate = :date
               AND d.employeeId IN :employeeIds
               AND d.wfhRequest.status = 'APPROVED'
            """)
    List<Long> findApprovedEmployeeIdsOn(@Param("employeeIds") Collection<Long> employeeIds,
                                         @Param("date") LocalDate date);
}
