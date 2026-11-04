package com.company.attendance.repository;

import com.company.attendance.entity.OfficeLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OfficeLocationRepository extends JpaRepository<OfficeLocation, Long> {

    List<OfficeLocation> findByOfficeIdAndActiveTrue(Long officeId);

    List<OfficeLocation> findByOfficeIdOrderByLocationNameAsc(Long officeId);

    long countByOfficeIdAndActiveTrue(Long officeId);

    /**
     * Bounding-box prefilter. Narrows candidates in SQL before any trigonometry
     * runs, which matters once an organisation has hundreds of sites.
     */
    @Query("SELECT ol FROM OfficeLocation ol WHERE ol.active = true AND (ol.office.id = :officeId OR ol.openToAll = true) AND ol.latitude BETWEEN :minLat AND :maxLat AND ol.longitude BETWEEN :minLng AND :maxLng")
    List<OfficeLocation> findCandidatesInBoundingBox(@Param("officeId") Long officeId,
                                                     @Param("minLat") BigDecimal minLat,
                                                     @Param("maxLat") BigDecimal maxLat,
                                                     @Param("minLng") BigDecimal minLng,
                                                     @Param("maxLng") BigDecimal maxLng);
}
