package com.company.attendance.entity;

import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.DayType;
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
import java.time.LocalDate;

/**
 * One calendar date within a leave request.
 *
 * <p>Non-deductible days are stored too, not filtered out. Keeping the holiday
 * that fell inside the range lets the UI explain why a Friday-to-Monday leave
 * cost two days rather than four, instead of leaving the employee to guess.
 */
@Entity
@Table(name = "leave_request_days")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequestDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_request_id", nullable = false)
    private LeaveRequest leaveRequest;

    /** Denormalised: the overlap check queries this directly, without a join. */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "leave_date", nullable = false)
    private LocalDate leaveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_portion", nullable = false, length = 20)
    private DayPortion dayPortion = DayPortion.FULL_DAY;

    /** False for holidays and weekly offs the leave type does not charge for. */
    @Column(name = "is_deductible", nullable = false)
    private boolean deductible = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType = DayType.WORKING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
