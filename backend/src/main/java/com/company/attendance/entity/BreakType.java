package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "break_types")
@Getter
@Setter
@NoArgsConstructor
public class BreakType extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "max_minutes", nullable = false)
    private short maxMinutes = 60;

    @Column(name = "is_paid", nullable = false)
    private boolean paid = true;

    /** A paid break still counts as working time; an unpaid one is deducted. */
    @Column(name = "is_deducted_from_working_hours", nullable = false)
    private boolean deductedFromWorkingHours = true;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
