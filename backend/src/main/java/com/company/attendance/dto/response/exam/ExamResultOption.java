package com.company.attendance.dto.response.exam;

/**
 * One option after the paper is in: now with the key, because the attempt is
 * over and the explanation is the point of the exercise.
 */
public record ExamResultOption(String label, String text, boolean correct, boolean chosen) {
}
