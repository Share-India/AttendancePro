package com.company.attendance.repository;

import com.company.attendance.entity.AttendanceBreak;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, Long> {

    List<AttendanceBreak> findByAttendanceIdOrderByBreakStartTimeAsc(Long attendanceId);

    /** The open break, if the employee is on one right now. */
    @Query("SELECT b FROM AttendanceBreak b WHERE b.attendance.id = :attendanceId AND b.breakEndTime IS NULL")
    Optional<AttendanceBreak> findOpenBreak(@Param("attendanceId") Long attendanceId);

    /** Only breaks their type says to deduct count against working time. */
    @Query("""
            SELECT COALESCE(SUM(b.durationMinutes), 0) FROM AttendanceBreak b
             WHERE b.attendance.id = :attendanceId
               AND b.breakEndTime IS NOT NULL
               AND b.breakType.deductedFromWorkingHours = true
            """)
    int sumDeductibleMinutes(@Param("attendanceId") Long attendanceId);

    @Query("SELECT COALESCE(SUM(b.durationMinutes), 0) FROM AttendanceBreak b WHERE b.attendance.id = :attendanceId AND b.breakEndTime IS NOT NULL")
    int sumAllMinutes(@Param("attendanceId") Long attendanceId);
}
