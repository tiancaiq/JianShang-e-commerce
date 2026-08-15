package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.auth_service.dto.AvatarUploadConfirmRequest;
import com.msb.ecom.auth_service.dto.AvatarUploadRequest;
import com.msb.ecom.auth_service.dto.AvatarUploadResponse;
import com.msb.ecom.auth_service.dto.CurrentUserResponse;
import com.msb.ecom.auth_service.dto.UpdateCurrentUserRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.UserMarketplaceCapabilities;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UserCapabilityService;
import com.msb.ecom.common.web.http.IfMatchVersion;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AuthController {

    private static final String PROFILE_VERSION_REQUIRED = "If-Match must contain the current profile version";

    private final AuthService authService;
    private final UserCapabilityService userCapabilityService;

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

    @GetMapping("/me/marketplace-capabilities")
    public ApiDataResponse<UserMarketplaceCapabilities> marketplaceCapabilities() {
        return new ApiDataResponse<>(userCapabilityService.currentUserCapabilities());
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiDataResponse<CurrentUserResponse> uploadAvatar(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestPart("file") MultipartFile file) {
        return new ApiDataResponse<>(authService.uploadCurrentUserAvatar(
                file,
                IfMatchVersion.parseRequired(ifMatch, PROFILE_VERSION_REQUIRED)));
    }

    @PostMapping("/me/avatar/upload-request")
    public ApiDataResponse<AvatarUploadResponse> requestAvatarUpload(
            @Valid @RequestBody AvatarUploadRequest request) {
        return new ApiDataResponse<>(authService.requestCurrentUserAvatarUpload(request));
    }

    @PutMapping(
            value = "/me/avatar/content",
            consumes = {"image/jpeg", "image/png", "image/webp"})
    public void uploadAvatarContent(
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            @RequestBody byte[] bytes) {
        authService.uploadCurrentUserAvatarContent(contentType, bytes);
    }

    @PostMapping("/me/avatar/confirm")
    public ApiDataResponse<CurrentUserResponse> confirmAvatarUpload(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AvatarUploadConfirmRequest request) {
        return new ApiDataResponse<>(authService.confirmCurrentUserAvatarUpload(
                request,
                IfMatchVersion.parseRequired(ifMatch, PROFILE_VERSION_REQUIRED)));
    }

    @DeleteMapping("/me/avatar")
    public ApiDataResponse<CurrentUserResponse> deleteAvatar(
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return new ApiDataResponse<>(authService.deleteCurrentUserAvatar(
                IfMatchVersion.parseRequired(ifMatch, PROFILE_VERSION_REQUIRED)));
    }

}
