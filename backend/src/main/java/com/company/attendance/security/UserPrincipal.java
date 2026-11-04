package com.company.attendance.security;

import com.company.attendance.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The authenticated principal. Every secured operation reads identity from here
 * — never from a request body — which is what stops a client from acting as
 * another employee.
 *
 * <p>Authorities carry both {@code ROLE_*} entries (for {@code hasRole}) and
 * bare permission codes (for {@code hasAuthority}).
 */
public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final Long employeeId;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final boolean active;
    private final boolean locked;
    private final boolean mustChangePassword;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Collection<GrantedAuthority> authorities;

    private UserPrincipal(Long userId, Long employeeId, String username, String email, String passwordHash,
                          boolean active, boolean locked, boolean mustChangePassword,
                          Set<String> roles, Set<String> permissions) {
        this.userId = userId;
        this.employeeId = employeeId;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        this.locked = locked;
        this.mustChangePassword = mustChangePassword;
        this.roles = roles;
        this.permissions = permissions;

        Collection<GrantedAuthority> granted = new LinkedHashSet<>();
        roles.forEach(role -> granted.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> granted.add(new SimpleGrantedAuthority(permission)));
        this.authorities = List.copyOf(granted);
    }

    public static UserPrincipal from(User user) {
        Set<String> roleCodes = new LinkedHashSet<>();
        Set<String> permissionCodes = new LinkedHashSet<>();
        user.getRoles().stream()
                .filter(role -> role.isActive())
                .forEach(role -> {
                    roleCodes.add(role.getCode());
                    role.getPermissions().forEach(permission -> permissionCodes.add(permission.getCode()));
                });

        return new UserPrincipal(
                user.getId(),
                user.getEmployee() != null ? user.getEmployee().getId() : null,
                user.getUsername(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isActive(),
                user.isCurrentlyLocked(),
                user.isMustChangePassword(),
                roleCodes,
                permissionCodes
        );
    }

    /** Rebuilt from JWT claims on every request — no database read in the filter. */
    public static UserPrincipal fromClaims(Long userId, Long employeeId, String username,
                                           Set<String> roles, Set<String> permissions) {
        return new UserPrincipal(userId, employeeId, username, null, null,
                true, false, false, roles, permissions);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEmail() {
        return email;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
