package com.company.attendance.service;

import com.company.attendance.repository.EmployeeManagerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the reporting subtree that TEAM-scoped queries are limited to.
 *
 * <p>The walk is breadth-first with two guards. A {@code visited} set makes a
 * cycle in the data ({@code A → B → A}, which a bad re-org can create) terminate
 * instead of hanging, and a depth cap stops a pathological chain from turning
 * one request into hundreds of queries.
 */
@Service
public class HierarchyService {

    private static final Logger log = LoggerFactory.getLogger(HierarchyService.class);

    /** Deeper than any real org chart; a hit here means the data is wrong. */
    private static final int MAX_DEPTH = 12;

    private final EmployeeManagerRepository employeeManagerRepository;

    public HierarchyService(EmployeeManagerRepository employeeManagerRepository) {
        this.employeeManagerRepository = employeeManagerRepository;
    }

    /** Direct reports only. */
    @Transactional(readOnly = true)
    public List<Long> getDirectReportIds(Long managerEmployeeId, LocalDate onDate) {
        return employeeManagerRepository.findDirectReportIds(managerEmployeeId, onDate);
    }

    /**
     * Everyone below this manager, at any depth. Excludes the manager, so a
     * caller that also needs their own rows must add their own id.
     */
    @Transactional(readOnly = true)
    public Set<Long> getSubordinateIds(Long managerEmployeeId, LocalDate onDate) {
        Set<Long> subordinates = new LinkedHashSet<>();
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(managerEmployeeId);

        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(managerEmployeeId);

        int depth = 0;
        while (!frontier.isEmpty() && depth < MAX_DEPTH) {
            int levelSize = frontier.size();
            for (int i = 0; i < levelSize; i++) {
                Long current = frontier.poll();
                for (Long reportId : employeeManagerRepository.findDirectReportIds(current, onDate)) {
                    if (visited.add(reportId)) {
                        subordinates.add(reportId);
                        frontier.add(reportId);
                    }
                }
            }
            depth++;
        }

        if (depth >= MAX_DEPTH && !frontier.isEmpty()) {
            log.warn("Reporting hierarchy below employee {} exceeded depth {}; truncated. "
                    + "Check employee_managers for a cycle.", managerEmployeeId, MAX_DEPTH);
        }

        return subordinates;
    }

    /** The manager's own id plus everyone below them — what TEAM scope means. */
    @Transactional(readOnly = true)
    public Set<Long> getTeamScopeIds(Long managerEmployeeId, LocalDate onDate) {
        Set<Long> team = new LinkedHashSet<>();
        team.add(managerEmployeeId);
        team.addAll(getSubordinateIds(managerEmployeeId, onDate));
        return team;
    }

    /** True when the target sits anywhere below the manager. */
    @Transactional(readOnly = true)
    public boolean managesEmployee(Long managerEmployeeId, Long targetEmployeeId, LocalDate onDate) {
        if (managerEmployeeId == null || targetEmployeeId == null) {
            return false;
        }
        return getSubordinateIds(managerEmployeeId, onDate).contains(targetEmployeeId);
    }

    /**
     * Rejects an assignment that would create a cycle — making someone report to
     * their own subordinate. Without this the subtree walk would loop forever
     * on the next query.
     */
    @Transactional(readOnly = true)
    public boolean wouldCreateCycle(Long employeeId, Long proposedManagerId, LocalDate onDate) {
        if (employeeId == null || proposedManagerId == null) {
            return false;
        }
        if (employeeId.equals(proposedManagerId)) {
            return true;
        }
        return getSubordinateIds(employeeId, onDate).contains(proposedManagerId);
    }
}
