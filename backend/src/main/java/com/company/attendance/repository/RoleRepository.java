package com.company.attendance.repository;

import com.company.attendance.entity.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    @EntityGraph(attributePaths = {"permissions"})
    List<Role> findByCodeIn(Set<String> codes);

    List<Role> findByActiveTrue();
}
