package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.WorkMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The derived daily summary — one row per employee per business day, guarded by
 * a unique constraint on that pair.
 *
 * <p>This is not the source of truth. {@link AttendancePunchEvent} is: an
 * append-only log of what actually happened. This row is what the log adds up
 * to, and it can always be recomputed from those events.
 */
@Entity
// The unique constraint is declared here as well as in the migration. It is
// not decoration: the punch-in path relies on the database refusing a second
// row, so the entity must state the contract it depends on. Declaring it only
// in the DDL left Hibernate-generated schemas without the guard entirely.
@Table(
        name = "attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attendance_employee_date",
                columnNames = {"employee_id", "attendance_date"}))
@Getter
@Setter
@NoArgsConstructor
public class Attendance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** Business date in the office's timezone; the shift start date for night shifts. */
    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    /** The shift in force that day, snapshotted so later reassignment cannot rewrite history. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "punch_in_time")
    private Instant punchInTime;

    @Column(name = "punch_out_time")
    private Instant punchOutTime;

    @Column(name = "punch_in_latitude", precision = 10, scale = 7)
    private BigDecimal punchInLatitude;

    @Column(name = "punch_in_longitude", precision = 10, scale = 7)
    private BigDecimal punchInLongitude;

    @Column(name = "punch_out_latitude", precision = 10, scale = 7)
    private BigDecimal punchOutLatitude;

    @Column(name = "punch_out_longitude", precision = 10, scale = 7)
    private BigDecimal punchOutLongitude;

    @Column(name = "punch_in_accuracy", precision = 7, scale = 2)
    private BigDecimal punchInAccuracy;

    @Column(name = "punch_out_accuracy", precision = 7, scale = 2)
    private BigDecimal punchOutAccuracy;

    @Column(name = "punch_in_location", length = 255)
    private String punchInLocation;

    @Column(name = "punch_out_location", length = 255)
    private String punchOutLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "punch_in_office_location_id")
    private OfficeLocation punchInOfficeLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "punch_out_office_location_id")
    private OfficeLocation punchOutOfficeLocation;

    @Column(name = "punch_in_distance_meters", precision = 10, scale = 2)
    private BigDecimal punchInDistanceMeters;

    @Column(name = "punch_out_distance_meters", precision = 10, scale = 2)
    private BigDecimal punchOutDistanceMeters;

    @Column(name = "punch_in_ip", length = 45)
    private String punchInIp;

    @Column(name = "punch_out_ip", length = 45)
    private String punchOutIp;

    @Column(name = "punch_in_device", length = 255)
    private String punchInDevice;

    @Column(name = "punch_out_device", length = 255)
    private String punchOutDevice;

    @Column(name = "working_minutes", nullable = false)
    private int workingMinutes;

    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    @Column(name = "early_exit_minutes", nullable = false)
    private int earlyExitMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", nullable = false, length = 30)
    private AttendanceStatus attendanceStatus = AttendanceStatus.PUNCHED_IN;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 20)
    private WorkMode workMode = WorkMode.OFFICE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AttendanceSource source = AttendanceSource.WEB;

    @Column(name = "is_regularized", nullable = false)
    private boolean regularized = false;

    @Column(name = "is_suspicious", nullable = false)
    private boolean suspicious = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suspicion_reasons")
    private String suspicionReasons;

    /** Set at payroll cut-off; a locked day rejects further changes. */
    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private Long lockedBy;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    public boolean isPunchedIn() {
        return punchInTime != null;
    }

    public boolean isPunchedOut() {
        return punchOutTime != null;
    }

    /** Elapsed working time so far, for a day that is still open. */
    public int elapsedWorkingMinutes(Instant now) {
        if (punchInTime == null) {
            return 0;
        }
        Instant end = punchOutTime != null ? punchOutTime : now;
        int gross = (int) java.time.Duration.between(punchInTime, end).toMinutes();
        return Math.max(0, gross - breakMinutes);
    }
}
