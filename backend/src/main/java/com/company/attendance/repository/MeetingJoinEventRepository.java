package com.company.attendance.repository;

import com.company.attendance.entity.MeetingJoinEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingJoinEventRepository extends JpaRepository<MeetingJoinEvent, Long> {

    @Query("""
            SELECT e FROM MeetingJoinEvent e
             JOIN FETCH e.employee emp
             WHERE e.meeting.id = :meetingId
             ORDER BY e.clickedAt ASC
            """)
    List<MeetingJoinEvent> findByMeeting(@Param("meetingId") Long meetingId);

    long countByMeetingIdAndEmployeeId(Long meetingId, Long employeeId);
}
