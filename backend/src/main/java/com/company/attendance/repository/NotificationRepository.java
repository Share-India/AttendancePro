package com.company.attendance.repository;

import com.company.attendance.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadFalseOrderByCreatedAtDesc(
            Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(Long recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    /**
     * Marks everything unread as read in one statement.
     *
     * <p>A bulk update rather than a read-modify-write loop: someone coming
     * back from leave can easily have hundreds, and loading them all to flip a
     * boolean would be pure waste.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.read = true, n.readAt = :readAt
             WHERE n.recipientUserId = :userId
               AND n.read = false
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") Instant readAt);
}
