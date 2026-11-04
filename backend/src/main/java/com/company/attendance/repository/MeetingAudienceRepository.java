package com.company.attendance.repository;

import com.company.attendance.entity.MeetingAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeetingAudienceRepository extends JpaRepository<MeetingAudience, Long> {

    List<MeetingAudience> findByMeetingId(Long meetingId);

    void deleteByMeetingId(Long meetingId);
}
