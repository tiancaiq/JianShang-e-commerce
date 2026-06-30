package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.CurrentUserResponse;
import com.msb.ecom.auth_service.dto.UpdateCurrentUserRequest;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.common.web.http.IfMatchVersion;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AuthController {

    private static final String PROFILE_VERSION_REQUIRED = "If-Match must contain the current profile version";

    private final AuthService authService;

    @GetMapping("/me")
    public ApiDataResponse<CurrentUserResponse> me() {
        return new ApiDataResponse<>(authService.ensureCurrentUser());
    }

    @PatchMapping("/me")
    public ApiDataResponse<CurrentUserResponse> updateMe(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateCurrentUserRequest request) {
        return new ApiDataResponse<>(authService.updateCurrentUser(
                request,
                IfMatchVersion.parseRequired(ifMatch, PROFILE_VERSION_REQUIRED)));
    }
}
