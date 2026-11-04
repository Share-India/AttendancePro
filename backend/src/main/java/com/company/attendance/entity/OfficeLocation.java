package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.LocationType;
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

import java.math.BigDecimal;

/**
 * One geofence circle. An office holds many of these — a campus with three
 * gates is three rows, not one oversized radius.
 */
@Entity
@Table(name = "office_locations")
@Getter
@Setter
@NoArgsConstructor
public class OfficeLocation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id", nullable = false)
    private Office office;

    @Column(name = "location_name", nullable = false, length = 150)
    private String locationName;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "allowed_radius_meters", nullable = false)
    private int allowedRadiusMeters = 200;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 30)
    private LocationType locationType = LocationType.OFFICE;

    /** Client sites usable by any employee, not just those posted to this office. */
    @Column(name = "is_open_to_all", nullable = false)
    private boolean openToAll = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
