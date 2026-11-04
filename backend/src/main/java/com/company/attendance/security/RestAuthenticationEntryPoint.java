package com.company.attendance.security;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.enums.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders a 401 in the same envelope as every other response. The specific code
 * comes from the JWT filter when a token was present but rejected, so the client
 * can tell "expired, refresh and retry" from "invalid, sign in again".
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        ErrorCode errorCode = attribute instanceof ErrorCode code ? code : ErrorCode.TOKEN_MISSING;

        String traceId = (String) request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Void> body = ApiResponse.error(
                errorCode, errorCode.getDefaultMessage(), null, request.getRequestURI(), traceId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
