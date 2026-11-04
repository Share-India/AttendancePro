package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.MeetingProvider;
import com.company.attendance.enums.MeetingStatus;
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
 * A meeting held somewhere else.
 *
 * <p>The link is the whole point and also the whole limitation: the
 * application hands people a URL and watches them take it. It has no view into
 * the call itself, so actualStartedAt and actualEndedAt are what the host told
 * us, not what the platform did.
 */
@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
public class Meeting extends BaseEntity {

    @Column(name = "meeting_code", nullable = false, unique = true, length = 30)
    private String meetingCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "agenda", columnDefinition = "TEXT")
    private String agenda;

    /** Validated on the way in: https only, parseable host, no credentials. */
    @Column(name = "meeting_url", nullable = false, length = 1000)
    private String meetingUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private MeetingProvider provider = MeetingProvider.OTHER;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_employee_id", nullable = false)
    private Employee host;

    @Column(name = "scheduled_start_at", nullable = false)
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    private Instant scheduledEndAt;

    @Column(name = "actual_started_at")
    private Instant actualStartedAt;

    @Column(name = "actual_ended_at")
    private Instant actualEndedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MeetingStatus status = MeetingStatus.DRAFT;

    /** Null means the company default applies. Lateness is policy. */
    @Column(name = "late_after_minutes")
    private Short lateAfterMinutes;

    /** Set once every participant has a terminal status. Guards re-running. */
    @Column(name = "finalised_at")
    private Instant finalisedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;
}
