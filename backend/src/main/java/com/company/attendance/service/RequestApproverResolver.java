package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.User;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Works out who should approve a request.
 *
 * <p>Regularization and work from home both route to the applicant's own
 * manager and neither has a second level, so the logic is small enough to
 * share directly rather than through another abstraction. Leave keeps its own
 * chain builder because it alone has an HR threshold.
 *
 * <p>The rule that matters is the same everywhere: the route comes from the
 * reporting hierarchy, not from who holds a role, and nobody ever approves
 * their own request.
 */
@Service
public class RequestApproverResolver {

    private final EmployeeManagerRepository employeeManagerRepository;
    private final UserRepository userRepository;
    private final HierarchyService hierarchyService;

    public RequestApproverResolver(EmployeeManagerRepository employeeManagerRepository,
                                   UserRepository userRepository,
                                   HierarchyService hierarchyService) {
        this.employeeManagerRepository = employeeManagerRepository;
        this.userRepository = userRepository;
        this.hierarchyService = hierarchyService;
    }

    /**
     * The manager a request should go to.
     *
     * <p>When the applicant is their own manager — a manager raising their own
     * request — routing moves one level up rather than letting them approve
     * themselves. Empty means nobody is above them, and the caller decides
     * what that means rather than the request vanishing into no inbox.
     */
    @Transactional(readOnly = true)
    public Optional<Employee> resolveApprover(Employee applicant, LocalDate onDate) {
        Optional<Employee> manager = primaryManager(applicant, onDate);

        if (manager.isPresent() && manager.get().getId().equals(applicant.getId())) {
            manager = primaryManager(manager.get(), onDate);
        }

        // Still themselves, or nobody: fall back to HR so the request is not
        // stranded with no one able to act on it.
        if (manager.isEmpty() || manager.get().getId().equals(applicant.getId())) {
            return anyHrApprover(applicant);
        }
        return manager;
    }

    private Optional<Employee> primaryManager(Employee employee, LocalDate onDate) {
        return employeeManagerRepository
                .findEffectiveOn(employee.getId(), RelationshipType.PRIMARY, onDate)
                .stream()
                .findFirst()
                .map(EmployeeManager::getManager)
                .filter(Employee::isEmployable);
    }

    private Optional<Employee> anyHrApprover(Employee applicant) {
        return userRepository.findAll().stream()
                .filter(User::isActive)
                .filter(user -> user.getRoles().stream()
                        .map(Role::getCode)
                        .anyMatch(code -> "HR".equals(code) || "ADMIN".equals(code)))
                .map(User::getEmployee)
                .filter(Objects::nonNull)
                .filter(Employee::isEmployable)
                .filter(employee -> !employee.getId().equals(applicant.getId()))
                .findFirst();
    }

    /**
     * Everyone below this person in the reporting tree.
     *
     * <p>The inbox queries need the set form of the same rule {@link
     * #canActOn} applies one request at a time. Never empty when passed to a
     * JPQL {@code IN}: an id that matches nothing stands in, because an empty
     * list there is a syntax error rather than a no-op.
     */
    @Transactional(readOnly = true)
    public Collection<Long> subordinateIdsOf(Long actorEmployeeId, LocalDate onDate) {
        Set<Long> ids = hierarchyService.getSubordinateIds(actorEmployeeId, onDate);
        return ids.isEmpty() ? Set.of(-1L) : ids;
    }

    /**
     * Whether this person may act on a request.
     *
     * <p>Being the assigned approver is enough; so is managing the applicant,
     * which covers a stand-in. An override permission covers HR. Approving
     * one's own request is refused in every case, whatever is held.
     */
    @Transactional(readOnly = true)
    public boolean canActOn(Long applicantEmployeeId,
                            Long currentApproverEmployeeId,
                            Long actorEmployeeId,
                            LocalDate onDate,
                            boolean hasOverridePermission) {

        if (actorEmployeeId == null) {
            return false;
        }
        if (actorEmployeeId.equals(applicantEmployeeId)) {
            return false;
        }
        if (actorEmployeeId.equals(currentApproverEmployeeId)) {
            return true;
        }
        if (hasOverridePermission) {
            return true;
        }
        return hierarchyService.managesEmployee(actorEmployeeId, applicantEmployeeId, onDate);
    }
}
