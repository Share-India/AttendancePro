package com.company.attendance.repository;

import com.company.attendance.entity.Attendance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long>, JpaSpecificationExecutor<Attendance> {

    Optional<Attendance> findByEmployeeIdAndAttendanceDate(Long employeeId, LocalDate attendanceDate);

    /**
     * Pessimistic lock for punch-out and break operations.
     *
     * <p>Punch-in is guarded by the unique constraint instead; here the row
     * already exists, so two concurrent writes would otherwise interleave a
     * read-modify-write and lose one of them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.attendanceDate = :date")
    Optional<Attendance> findForUpdate(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    Page<Attendance> findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(
            Long employeeId, LocalDate from, LocalDate to, Pageable pageable);

    List<Attendance> findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Long employeeId, LocalDate from, LocalDate to);

    /** Most recent day with a punch, used by the impossible-travel check. */
    @Query("SELECT a FROM Attendance a WHERE a.employee.id = :employeeId AND a.punchInTime IS NOT NULL ORDER BY a.attendanceDate DESC LIMIT 1")
    Optional<Attendance> findMostRecentPunched(@Param("employeeId") Long employeeId);

    /** Days whose coordinates exactly repeat a given point — real GPS jitters. */
    @Query("""
            SELECT COUNT(a) FROM Attendance a
             WHERE a.employee.id = :employeeId
               AND a.attendanceDate < :date
               AND a.punchInLatitude = :lat
               AND a.punchInLongitude = :lng
            """)
    long countIdenticalPunchInCoordinates(@Param("employeeId") Long employeeId,
                                          @Param("date") LocalDate date,
                                          @Param("lat") java.math.BigDecimal lat,
                                          @Param("lng") java.math.BigDecimal lng);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.employee.id = :employeeId AND a.attendanceDate BETWEEN :from AND :to AND a.lateMinutes > 0")
    long countLateMarks(@Param("employeeId") Long employeeId,
                        @Param("from") LocalDate from,
                        @Param("to") LocalDate to);

    /** Every row for a set of people on one day — the dashboard's hot query. */
    @Query("""
            SELECT a FROM Attendance a
             JOIN FETCH a.employee
             LEFT JOIN FETCH a.shift
             WHERE a.attendanceDate = :date
               AND a.employee.id IN :employeeIds
            """)
    List<Attendance> findForEmployeesOn(@Param("employeeIds") Collection<Long> employeeIds,
                                        @Param("date") LocalDate date);

    /** The same over a range, for the trend chart. No fetch joins: only counts are read. */
    @Query("""
            SELECT a FROM Attendance a
             WHERE a.attendanceDate BETWEEN :from AND :to
               AND a.employee.id IN :employeeIds
            """)
    List<Attendance> findForEmployeesBetween(@Param("employeeIds") Collection<Long> employeeIds,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    @Query("SELECT a FROM Attendance a WHERE a.attendanceDate = :date AND a.attendanceStatus IN ('PUNCHED_IN','ON_BREAK')")
    List<Attendance> findOpenDays(@Param("date") LocalDate date);
}
