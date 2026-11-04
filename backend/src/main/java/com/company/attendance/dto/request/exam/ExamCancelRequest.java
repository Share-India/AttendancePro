package com.company.attendance.dto.request.exam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Calling an exam off. The reason is shown to everybody who was invited. */
public record ExamCancelRequest(

        @NotBlank(message = "Say why the exam is being cancelled")
        @Size(max = 500, message = "That reason is too long")
        String reason) {
}
