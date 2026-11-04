package com.company.attendance.repository;

import com.company.attendance.entity.WeeklyOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WeeklyOffRepository extends JpaRepository<WeeklyOff, Long> {

    /**
     * All active rules. The set is tiny (a handful of rows for a whole company),
     * so the scope filtering happens in the service where the precedence rules
     * live, rather than as several near-identical queries.
     */
    @Query("SELECT w FROM WeeklyOff w WHERE w.active = true")
    List<WeeklyOff> findAllActive();
}
