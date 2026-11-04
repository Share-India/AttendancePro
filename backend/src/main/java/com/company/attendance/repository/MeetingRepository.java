package com.company.attendance.repository;

import com.company.attendance.entity.Meeting;
import com.company.attendance.enums.MeetingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    boolean existsByMeetingCode(String meetingCode);

    /**
     * The host's own list, or everyone's when the caller's scope is ORG.
     *
     * <p>{@code hostIds} carries the reporting subtree for a manager. An empty
     * collection in a JPQL {@code IN} is a syntax error, so callers pass a
     * sentinel rather than an empty set — see RequestApproverResolver, which
     * has the same problem and solves it the same way.
     */
    @Query("""
            SELECT m FROM Meeting m
             JOIN FETCH m.host h
             WHERE (:unrestricted = TRUE OR h.id IN :hostIds)
               AND (:status IS NULL OR m.status = :status)
             ORDER BY m.scheduledStartAt DESC
            """)
    Page<Meeting> findForHosts(@Param("unrestricted") boolean unrestricted,
                               @Param("hostIds") Collection<Long> hostIds,
                               @Param("status") MeetingStatus status,
                               Pageable pageable);

    /**
     * Meetings this employee is on the roster for, newest first.
     *
     * <p>Driven from the participant rows rather than from the audience rules,
     * because the roster is the record of who was expected once a meeting has
     * been published.
     */
    @Query("""
            SELECT m FROM MeetingParticipant p
             JOIN p.meeting m
             JOIN FETCH m.host h
             WHERE p.employee.id = :employeeId
               AND m.status <> 'DRAFT'
             ORDER BY m.scheduledStartAt DESC
            """)
    Page<Meeting> findForParticipant(@Param("employeeId") Long employeeId, Pageable pageable);

    /**
     * Open or scheduled meetings whose window has run out.
     *
     * <p>Used by the lazy finalisation path: there is no scheduler in this
     * application, so a meeting the host forgot to end closes itself the next
     * time anybody looks at the list.
     */
    @Query("""
            SELECT m FROM Meeting m
             WHERE m.status IN ('SCHEDULED', 'OPEN')
               AND m.scheduledEndAt < :cutoff
            """)
    List<Meeting> findStale(@Param("cutoff") Instant cutoff);
}
