package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The key/value long tail of configuration. Finite, queryable, hot rules live
 * in typed policy tables instead — pure EAV would make "which employees have a
 * grace period over 15 minutes?" unanswerable in SQL.
 */
@Entity
@Table(name = "company_settings")
@Getter
@Setter
@NoArgsConstructor
public class CompanySetting extends BaseEntity {

    @Column(name = "setting_key", nullable = false, unique = true, length = 120)
    private String settingKey;

    @Column(name = "setting_value", length = 1000)
    private String settingValue;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType = "STRING";

    @Column(name = "category", nullable = false, length = 60)
    private String category = "GENERAL";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_editable", nullable = false)
    private boolean editable = true;
}
