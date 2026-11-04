package com.company.attendance.dto.request.exam;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Opening the one retake somebody gets.
 *
 * <p>Only the window is asked for. Everything about how the paper is marked is
 * copied from the exam being retaken, because a retake scored differently from
 * the exam it retakes is not a retake of it.
 */
public record RetakeRequest(

        @Size(max = 200, message = "That title is too long")
        String title,

        @NotNull(message = "When does the retake open?")
        Instant windowStart,

        @NotNull(message = "When does the retake close?")
        Instant windowEnd,

        /** Null keeps the original's duration. */
        Integer durationMinutes) {
}
