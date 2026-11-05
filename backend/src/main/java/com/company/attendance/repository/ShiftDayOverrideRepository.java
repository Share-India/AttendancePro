package com.company.attendance.repository;

import com.company.attendance.entity.ShiftDayOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShiftDayOverrideRepository extends JpaRepository<ShiftDayOverride, Long> {

    /**
     * All active overrides, shift fetched.
     *
     * <p>The set is tiny — one row today — and it is consulted on every punch
     * and on every dashboard cell, so it is read whole and filtered in memory
     * rather than queried per employee. The JOIN FETCH matters: without it the
     * dashboard's per-employee loop would lazy-load the shift once per row.
     */
    @Query("SELECT o FROM ShiftDayOverride o JOIN FETCH o.shift WHERE o.active = true")
    List<ShiftDayOverride> findAllActive();
}
