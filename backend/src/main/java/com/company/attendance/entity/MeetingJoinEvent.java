package com.company.attendance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One click on a join link, appended and never changed.
 *
 * <p>This is the evidence; the summary columns on {@link MeetingParticipant}
 * are the derivation, exactly as attendance_punch_events sits beneath
 * attendance. A second click is a fact rather than a duplicate, so there is no
 * unique constraint — somebody who dropped out and came back leaves two rows,
 * and that is information worth keeping.
 */
@Entity
@Table(name = "meeting_join_events")
@Getter
@Setter
@NoArgsConstructor
public class MeetingJoinEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt = Instant.now();

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;
}
