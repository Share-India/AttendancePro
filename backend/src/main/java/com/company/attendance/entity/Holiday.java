package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "holidays")
@Getter
@Setter
@NoArgsConstructor
public class Holiday extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    /** NATIONAL, STATE, COMPANY, OPTIONAL or RESTRICTED. */
    @Column(name = "holiday_type", nullable = false, length = 30)
    private String holidayType = "COMPANY";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "holiday_year", nullable = false)
    private short holidayYear;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
