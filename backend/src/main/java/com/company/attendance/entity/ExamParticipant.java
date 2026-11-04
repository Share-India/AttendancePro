package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ExamParticipantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One person on the roster, and the paper they were dealt.
 *
 * <p>Written at publish rather than worked out on read. Somebody who joins on
 * Wednesday must not appear in Monday's participation figure, and Monday's
 * figure must not move when the org chart does — the same reasoning as
 * {@code wasEmployedOn} in the dashboard.
 */
@Entity
@Table(name = "exam_participants")
@Getter
@Setter
@NoArgsConstructor
public class ExamParticipant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_set_id", nullable = false)
    private ExamQuestionSet assignedSet;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_kind", nullable = false, length = 20)
    private ExamParticipantKind participantKind = ExamParticipantKind.CANDIDATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExamParticipantStatus status = ExamParticipantStatus.ASSIGNED;
}
