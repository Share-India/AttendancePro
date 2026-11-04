package com.company.attendance.dto.response;

import com.company.attendance.enums.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The single response shape for every endpoint, success or failure, so the
 * frontend has exactly one unwrapping path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        String errorCode,
        Instant timestamp,
        String path,
        String traceId
) {

    public static <T> ApiResponse<T> success(T data) {
        return success(data, "Success");
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, null, Instant.now(), null, null);
    }

    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(true, message, null, null, Instant.now(), null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T data, String path, String traceId) {
        return new ApiResponse<>(false, message, data, errorCode.name(), Instant.now(), path, traceId);
    }
}
