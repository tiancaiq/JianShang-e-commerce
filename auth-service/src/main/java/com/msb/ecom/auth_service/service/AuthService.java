package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.CurrentUserResponse;
import com.msb.ecom.auth_service.dto.AvatarUploadConfirmRequest;
import com.msb.ecom.auth_service.dto.AvatarUploadRequest;
import com.msb.ecom.auth_service.dto.AvatarUploadResponse;
import com.msb.ecom.auth_service.dto.UpdateCurrentUserRequest;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final UlidGenerator ulidGenerator;
    private final CurrentActorProvider currentActorProvider;
    private final AvatarStorageService avatarStorageService;

    @Transactional
    public CurrentUserResponse ensureCurrentUser() {
        return CurrentUserResponse.from(ensureUserEntity());
    }

    @Transactional
    public CurrentUserResponse updateCurrentUser(UpdateCurrentUserRequest request, Long expectedVersion) {
        User user = ensureUserEntity();

        requireCurrentProfileVersion(user, expectedVersion);

        user.updateProfile(
                normalizedDisplayName(request.displayName()),
                normalizedPhone(request.phone()),
                normalizedAvatarUrl(request.avatarUrl()));

        return CurrentUserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public AvatarUploadResponse requestCurrentUserAvatarUpload(AvatarUploadRequest request) {
        User user = ensureUserEntity();
        String contentType = AvatarStorageSupport.normalizedContentType(request.contentType());
        long sizeBytes = request.sizeBytes();
        AvatarUploadTarget target = avatarStorageService.createUploadTarget(user.getId(), contentType, sizeBytes);
        return new AvatarUploadResponse(
                target.bucket(),
                target.objectKey(),
                contentType,
                sizeBytes,
                target.uploadMethod(),
                target.uploadUrl());
    }

    @Transactional
    public CurrentUserResponse uploadCurrentUserAvatar(MultipartFile file, Long expectedVersion) {
        User user = ensureUserEntity();
        requireCurrentProfileVersion(user, expectedVersion);

        avatarStorageService.store(user.getId(), file);
        user.updateProfile(user.getDisplayName(), user.getPhone(), safeAvatarUrl(user.getId(), user.getVersion() + 1));

        return CurrentUserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public void uploadCurrentUserAvatarContent(String contentType, byte[] bytes) {
        User user = ensureUserEntity();
        avatarStorageService.uploadObject(user.getId(), contentType, bytes);
    }

    @Transactional
    public CurrentUserResponse confirmCurrentUserAvatarUpload(
            AvatarUploadConfirmRequest request,
            Long expectedVersion) {
        User user = ensureUserEntity();
        requireCurrentProfileVersion(user, expectedVersion);

        avatarStorageService.verifyUploaded(
                user.getId(),
                request.objectKey(),
                request.contentType(),
                request.sizeBytes());
        avatarStorageService.deleteOtherContentTypes(user.getId(), request.contentType());
        user.updateProfile(user.getDisplayName(), user.getPhone(), safeAvatarUrl(user.getId(), user.getVersion() + 1));

        return CurrentUserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public CurrentUserResponse deleteCurrentUserAvatar(Long expectedVersion) {
        User user = ensureUserEntity();
        requireCurrentProfileVersion(user, expectedVersion);

        avatarStorageService.delete(user.getId());
        user.updateProfile(user.getDisplayName(), user.getPhone(), null);

        return CurrentUserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public AvatarContent publicAvatar(String userId) {
        User user = userRepository.findById(userId)
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .filter(candidate -> candidate.getAvatarUrl() != null && !candidate.getAvatarUrl().isBlank())
                .orElseThrow(AvatarNotFoundException::new);
        return avatarStorageService.read(user.getId()).orElseThrow(AvatarNotFoundException::new);
    }

    public User ensureUserEntity() {
        CurrentActor actor = currentActorProvider.currentActor();
        String subject = requiredSubject(actor.subject());
        String email = normalizedEmail(actor.email());
        String displayName = displayName(actor.displayName(), email);

        return userRepository.findByKeycloakSub(subject)
                .map(existing -> {
                    existing.applyKeycloakProjection(email, actor.emailVerified(), displayName);
                    return existing;
                })
                .orElseGet(() -> {
                    String id = ulidGenerator.next();
                    User created = User.create(
                            id,
                            subject,
                            email,
                            actor.emailVerified(),
                            displayName,
                            publicHandle(id));
                    log.info("Creating identity user mapping for keycloakSub={}", subject);
                    return userRepository.save(created);
                });
    }

    private String requiredSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("Authenticated token is missing subject");
        }
        if (subject.length() > 64) {
            throw new IllegalArgumentException("Authenticated subject is too long");
        }
        return subject;
    }

    private String normalizedEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.length() > 320) {
            throw new IllegalArgumentException("Authenticated email is too long");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String displayName(String actorDisplayName, String email) {
        String name = firstPresent(actorDisplayName, email);
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            return trimmed.substring(0, 200);
        }
        return trimmed;
    }

    private String normalizedDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Display name must not be blank");
        }
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Display name is too long");
        }
        return trimmed;
    }

    private String normalizedPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        if (!trimmed.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("Phone must be E.164 formatted");
        }
        return trimmed;
    }

    private String normalizedAvatarUrl(String avatarUrl) {
        if (avatarUrl == null) {
            return null;
        }
        String trimmed = avatarUrl.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        if (trimmed.length() > 2048) {
            throw new IllegalArgumentException("Avatar URL is too long");
        }
        if (trimmed.matches("^/api/v1/public/user-avatars/[0-7][0-9A-HJKMNP-TV-Z]{25}(\\?v=[0-9]+)?$")) {
            return trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Avatar URL must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Avatar URL must include a host");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Avatar URL is invalid");
        }
        return trimmed;
    }

    // Generates a stable non-PII handle when an identity is first projected into the application database.
    private String publicHandle(String userId) {
        return "member-" + userId.toLowerCase(Locale.ROOT);
    }

    private void requireCurrentProfileVersion(User user, Long expectedVersion) {
        if (expectedVersion != null && user.getVersion() != expectedVersion) {
            throw new ProfileVersionConflictException();
        }
    }

    private String safeAvatarUrl(String userId, long version) {
        return "/api/v1/public/user-avatars/" + userId + "?v=" + version;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (Objects.nonNull(value) && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
