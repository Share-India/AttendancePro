package com.company.attendance.repository;

import com.company.attendance.entity.MeetingParticipant;
import com.company.attendance.enums.MeetingAttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    @Query("""
            SELECT p FROM MeetingParticipant p
             JOIN FETCH p.employee e
             WHERE p.meeting.id = :meetingId
             ORDER BY e.employeeCode ASC
            """)
    List<MeetingParticipant> findRoster(@Param("meetingId") Long meetingId);

    Optional<MeetingParticipant> findByMeetingIdAndEmployeeId(Long meetingId, Long employeeId);

    boolean existsByMeetingIdAndEmployeeId(Long meetingId, Long employeeId);

    long countByMeetingIdAndStatusIn(Long meetingId, Collection<MeetingAttendanceStatus> statuses);

    /**
     * Rows for the participation report: one per person per meeting across a
     * date range, already narrowed to the employees the caller may see.
     */
    @Query("""
            SELECT p FROM MeetingParticipant p
             JOIN FETCH p.employee e
             JOIN FETCH p.meeting m
             WHERE (:unrestricted = TRUE OR e.id IN :employeeIds)
               AND m.scheduledStartAt >= :from
               AND m.scheduledStartAt < :to
               AND m.status IN ('ENDED', 'FINALISED')
             ORDER BY m.scheduledStartAt DESC, e.employeeCode ASC
            """)
    List<MeetingParticipant> findForReport(@Param("unrestricted") boolean unrestricted,
                                           @Param("employeeIds") Collection<Long> employeeIds,
                                           @Param("from") java.time.Instant from,
                                           @Param("to") java.time.Instant to);

    /** Whether this person already has a row — used to keep publish idempotent. */
    @Query("SELECT p.employee.id FROM MeetingParticipant p WHERE p.meeting.id = :meetingId")
    List<Long> findEmployeeIds(@Param("meetingId") Long meetingId);

    /** Unused today; kept for the per-employee history screen. */
    @Query("""
            SELECT COUNT(p) FROM MeetingParticipant p
             WHERE p.employee.id = :employeeId
               AND p.status = :status
            """)
    long countByEmployeeAndStatus(@Param("employeeId") Long employeeId,
                                  @Param("status") MeetingAttendanceStatus status);
}
