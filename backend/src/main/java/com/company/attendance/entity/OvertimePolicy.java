package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "overtime_policies")
@Getter
@Setter
@NoArgsConstructor
public class OvertimePolicy extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Below this, extra minutes are noise rather than overtime. */
    @Column(name = "min_overtime_minutes", nullable = false)
    private short minOvertimeMinutes = 30;

    @Column(name = "max_daily_overtime_minutes", nullable = false)
    private short maxDailyOvertimeMinutes = 240;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = true;

    @Column(name = "applicable_on_weekly_off", nullable = false)
    private boolean applicableOnWeeklyOff = true;

    @Column(name = "applicable_on_holiday", nullable = false)
    private boolean applicableOnHoliday = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
