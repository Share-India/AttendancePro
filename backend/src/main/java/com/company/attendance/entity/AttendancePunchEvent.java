package com.company.attendance.entity;

import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.enums.WorkMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only evidence of a single punch action.
 *
 * <p>Nothing updates or deletes a row here. The daily {@link Attendance} row is
 * derived from these; a correction rewrites that summary and adds a new event
 * with {@code source = REGULARIZATION}, so what the device originally reported
 * is always recoverable. That is what makes the audit trail defensible rather
 * than merely present.
 *
 * <p>This also replaces a separate attendance-locations table: an attendance
 * location <em>is</em> a punch event.
 */
@Entity
@Table(name = "attendance_punch_events")
@Getter
@Setter
@NoArgsConstructor
public class AttendancePunchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private PunchEventType eventType;

    /** Server time. This is the value every calculation uses. */
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    /**
     * What the browser claimed the time was. Recorded next to the server's own
     * timestamp so a skewed or manipulated client clock is visible rather than
     * silently trusted.
     */
    @Column(name = "client_reported_time")
    private Instant clientReportedTime;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "accuracy_meters", precision = 7, scale = 2)
    private BigDecimal accuracyMeters;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "office_location_id")
    private OfficeLocation officeLocation;

    @Column(name = "distance_meters", precision = 10, scale = 2)
    private BigDecimal distanceMeters;

    @Column(name = "within_geofence")
    private Boolean withinGeofence;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 20)
    private WorkMode workMode = WorkMode.OFFICE;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private AttendanceSource source = AttendanceSource.WEB;

    @Column(name = "is_suspicious", nullable = false)
    private boolean suspicious = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suspicion_reasons")
    private String suspicionReasons;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
