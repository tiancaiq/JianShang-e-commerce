package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.CurrentUserResponse;
import com.msb.ecom.auth_service.dto.UpdateCurrentUserRequest;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public CurrentUserResponse ensureCurrentUser(Jwt jwt) {
        return CurrentUserResponse.from(ensureUserEntity(jwt));
    }

    @Transactional
    public CurrentUserResponse updateCurrentUser(Jwt jwt, UpdateCurrentUserRequest request, Long expectedVersion) {
        User user = ensureUserEntity(jwt);

        if (expectedVersion != null && user.getVersion() != expectedVersion) {
            throw new ProfileVersionConflictException();
        }

        user.updateProfile(
                normalizedDisplayName(request.displayName()),
                normalizedPhone(request.phone()),
                normalizedAvatarUrl(request.avatarUrl()));

        return CurrentUserResponse.from(userRepository.saveAndFlush(user));
    }

    public User ensureUserEntity(Jwt jwt) {
        String subject = requiredSubject(jwt);
        String email = normalizedEmail(jwt);
        boolean emailVerified = Boolean.TRUE.equals(jwt.getClaim("email_verified"));
        String displayName = displayName(jwt, email);

        return userRepository.findByKeycloakSub(subject)
                .map(existing -> {
                    existing.applyKeycloakProjection(email, emailVerified, displayName);
                    return existing;
                })
                .orElseGet(() -> {
                    User created = User.create(ulidGenerator.next(), subject, email, emailVerified, displayName);
                    log.info("Creating identity user mapping for keycloakSub={}", subject);
                    return userRepository.save(created);
                });
    }

    private String requiredSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("Authenticated token is missing subject");
        }
        if (subject.length() > 64) {
            throw new IllegalArgumentException("Authenticated subject is too long");
        }
        return subject;
    }

    private String normalizedEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.length() > 320) {
            throw new IllegalArgumentException("Authenticated email is too long");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String displayName(Jwt jwt, String email) {
        String name = firstPresent(
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("preferred_username"),
                email);
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

    private String firstPresent(String... values) {
        for (String value : values) {
            if (Objects.nonNull(value) && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
