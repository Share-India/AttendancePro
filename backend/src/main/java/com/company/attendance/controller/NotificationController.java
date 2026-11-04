package com.company.attendance.controller;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.notification.NotificationResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Your own notifications, and only ever your own.
 *
 * <p>There is no permission to guard here and no employee id in any path: the
 * recipient is the authenticated user, taken from the token. That is why every
 * query is scoped by recipient inside the repository rather than filtered after
 * loading.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notifications for the signed-in user")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Your notifications, newest first")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> list(
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                notificationService.list(principal, unreadOnly, pageable)));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "How many you have not read")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("unread", notificationService.unreadCount(principal))));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark one as read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @CurrentUser UserPrincipal principal, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(notificationService.markRead(principal, id)));
    }

    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark everything as read")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead(
            @CurrentUser UserPrincipal principal) {
        int updated = notificationService.markAllRead(principal);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("marked", updated), updated + " marked as read"));
    }
}
