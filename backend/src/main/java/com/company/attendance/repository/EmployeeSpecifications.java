package com.company.attendance.repository;

import com.company.attendance.dto.filter.EmployeeFilter;
import com.company.attendance.entity.Employee;
import com.company.attendance.enums.EmploymentStatus;
import com.company.attendance.enums.EmploymentType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dynamic filters for the employee directory, built with the Criteria API so
 * nothing is ever concatenated into a query string.
 */
public final class EmployeeSpecifications {

    private EmployeeSpecifications() {
    }

    public static Specification<Employee> from(EmployeeFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.search() != null && !filter.search().isBlank()) {
                String term = "%" + filter.search().trim().toLowerCase(Locale.ROOT) + "%";
                // Concatenating first and last covers "Asha Rao" typed in full,
                // which matching the columns separately would miss.
                Predicate fullName = cb.like(
                        cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))), term);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), term),
                        cb.like(cb.lower(root.get("lastName")), term),
                        cb.like(cb.lower(root.get("employeeCode")), term),
                        cb.like(cb.lower(root.get("officialEmail")), term),
                        fullName));
            }

            if (filter.departmentId() != null) {
                predicates.add(cb.equal(root.get("department").get("id"), filter.departmentId()));
            }
            if (filter.designationId() != null) {
                predicates.add(cb.equal(root.get("designation").get("id"), filter.designationId()));
            }
            if (filter.officeId() != null) {
                predicates.add(cb.equal(root.get("office").get("id"), filter.officeId()));
            }
            if (filter.employmentType() != null && !filter.employmentType().isBlank()) {
                predicates.add(cb.equal(root.get("employmentType"),
                        EmploymentType.valueOf(filter.employmentType().toUpperCase(Locale.ROOT))));
            }
            if (filter.employmentStatus() != null && !filter.employmentStatus().isBlank()) {
                predicates.add(cb.equal(root.get("employmentStatus"),
                        EmploymentStatus.valueOf(filter.employmentStatus().toUpperCase(Locale.ROOT))));
            }
            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Restricts results to a set of employee ids — how TEAM and SELF scope are
     * applied. An empty set matches nothing, which is the correct reading of
     * "a manager with no reports", not "show everything".
     */
    public static Specification<Employee> restrictedTo(Set<Long> employeeIds) {
        return (root, query, cb) -> {
            if (employeeIds.isEmpty()) {
                return cb.disjunction();
            }
            return root.get("id").in(employeeIds);
        };
    }
}
