package com.company.attendance.repository;

import com.company.attendance.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    @Query("SELECT COUNT(a) FROM LoginAttempt a WHERE a.ipAddress = :ip AND a.successful = false AND a.attemptedAt > :since")
    long countRecentFailuresByIp(@Param("ip") String ip, @Param("since") Instant since);
}
