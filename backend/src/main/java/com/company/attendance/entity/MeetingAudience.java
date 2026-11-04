package com.company.attendance.entity;

import com.company.attendance.enums.MeetingScopeType;
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

import java.time.Instant;

/**
 * One rule describing who is expected at a meeting, before the roster exists.
 *
 * <p>Read exactly once, when the meeting is published. After that the
 * {@link MeetingParticipant} rows are the record, so moving someone between
 * departments next month cannot change who was supposed to attend last week.
 */
@Entity
@Table(name = "meeting_audience")
@Getter
@Setter
@NoArgsConstructor
public class MeetingAudience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private MeetingScopeType scopeType;

    /** Null for GLOBAL, and for TEAM, which means the host's own subtree. */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
