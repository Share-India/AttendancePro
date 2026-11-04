package com.company.attendance.repository;

import com.company.attendance.entity.AttendancePunchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Append-only. This interface deliberately exposes no update or delete beyond
 * what JpaRepository inherits, and nothing in the application calls those.
 */
@Repository
public interface AttendancePunchEventRepository extends JpaRepository<AttendancePunchEvent, Long> {

    List<AttendancePunchEvent> findByAttendanceIdOrderByEventTimeAsc(Long attendanceId);

    @Query("SELECT e FROM AttendancePunchEvent e WHERE e.employeeId = :employeeId ORDER BY e.eventTime DESC LIMIT :limit")
    List<AttendancePunchEvent> findRecentForEmployee(@Param("employeeId") Long employeeId, @Param("limit") int limit);
}
