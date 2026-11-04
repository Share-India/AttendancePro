package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "location_policies")
@Getter
@Setter
@NoArgsConstructor
public class LocationPolicy extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enforce_geofence", nullable = false)
    private boolean enforceGeofence = true;

    /** A fix vaguer than this cannot be placed inside a radius with confidence. */
    @Column(name = "max_accuracy_meters", nullable = false)
    private short maxAccuracyMeters = 100;

    @Column(name = "allow_wfh_without_geofence", nullable = false)
    private boolean allowWfhWithoutGeofence = true;

    @Column(name = "allow_field_punch", nullable = false)
    private boolean allowFieldPunch = false;

    /** Off by default: continuous location history is a deliberate decision. */
    @Column(name = "track_field_movement", nullable = false)
    private boolean trackFieldMovement = false;

    /** Whether a suspicion flag rejects the punch or merely marks it. */
    @Column(name = "block_on_suspicious", nullable = false)
    private boolean blockOnSuspicious = false;

    @Column(name = "allowed_countries", length = 255)
    private String allowedCountries;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
