package com.company.attendance.repository;

import com.company.attendance.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * The entity types that actually appear in the log.
     *
     * <p>A DISTINCT projection rather than loading every row and reducing it in
     * memory: this table only ever grows, and it is the one table nothing is
     * allowed to prune.
     */
    @Query("SELECT DISTINCT a.entityType FROM AuditLog a WHERE a.entityType IS NOT NULL ORDER BY a.entityType")
    List<String> findDistinctEntityTypes();
}
