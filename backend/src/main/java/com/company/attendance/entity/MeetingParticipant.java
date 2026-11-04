package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.MeetingAttendanceSource;
import com.company.attendance.enums.MeetingAttendanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One person's place on one meeting's roster, and what became of it.
 *
 * <p>Written when the meeting is published, so the denominator is frozen:
 * somebody hired on Wednesday is not retrospectively absent from Monday.
 *
 * <p>The summary fields here are derived from {@link MeetingJoinEvent} rows
 * and can be rebuilt from them. firstJoinedAt is set once and never moved —
 * a reconnect must not make an early arrival look late.
 */
@Entity
@Table(name = "meeting_participants")
@Getter
@Setter
@NoArgsConstructor
public class MeetingParticipant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "first_joined_at")
    private Instant firstJoinedAt;

    @Column(name = "last_clicked_at")
    private Instant lastClickedAt;

    @Column(name = "click_count", nullable = false)
    private int clickCount;

    /**
     * Always null today. Opening a link tells us nothing about leaving one,
     * and guessing would be worse than admitting it. Only a provider export
     * can fill this in.
     */
    @Column(name = "left_at")
    private Instant leftAt;

    /** End of meeting minus join time. An estimate — never shown unqualified. */
    @Column(name = "attended_minutes_estimate")
    private Integer attendedMinutesEstimate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MeetingAttendanceStatus status = MeetingAttendanceStatus.INVITED;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_source", length = 20)
    private MeetingAttendanceSource attendanceSource;

    /** Why they were excused: ON_LEAVE, WEEKLY_OFF, HOLIDAY, NOT_YET_JOINED. */
    @Column(name = "excuse_reason", length = 100)
    private String excuseReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;
}
