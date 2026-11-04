package com.company.attendance.repository;

import com.company.attendance.entity.OvertimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OvertimeRecordRepository extends JpaRepository<OvertimeRecord, Long> {

    Optional<OvertimeRecord> findByAttendanceId(Long attendanceId);
}
