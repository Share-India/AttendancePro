package com.company.attendance.entity;

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
 * One working day covered by a work-from-home request.
 *
 * <p>Punch-in queries this by employee and date on every punch, so it is a
 * single indexed lookup rather than a range comparison.
 */
@Entity
@Table(name = "wfh_request_days")
@Getter
@Setter
@NoArgsConstructor
public class WfhRequestDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wfh_request_id", nullable = false)
    private WorkFromHomeRequest wfhRequest;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "wfh_date", nullable = false)
    private LocalDate wfhDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType = DayType.WORKING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
