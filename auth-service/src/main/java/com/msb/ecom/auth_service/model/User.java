package com.msb.ecom.auth_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String id;

    @Column(name = "keycloak_sub", length = 64, nullable = false, unique = true, updatable = false)
    private String keycloakSub;

    @Column(length = 320)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "public_handle", length = 64, nullable = false, unique = true, updatable = false)
    private String publicHandle;

    @Column(length = 32)
    private String phone;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

    @Column(length = 32, nullable = false)
    private String status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected User() {
    }

    private User(
            String id,
            String keycloakSub,
            String email,
            boolean emailVerified,
            String displayName,
            String publicHandle) {
        this.id = id;
        this.keycloakSub = keycloakSub;
        this.email = email;
        this.emailVerified = emailVerified;
        this.displayName = displayName;
        this.publicHandle = publicHandle;
        this.phoneVerified = false;
        this.status = "ACTIVE";
    }

    public static User create(
            String id,
            String keycloakSub,
            String email,
            boolean emailVerified,
            String displayName,
            String publicHandle) {
        return new User(id, keycloakSub, email, emailVerified, displayName, publicHandle);
    }

    public void applyKeycloakProjection(String email, boolean emailVerified, String displayName) {
        this.email = email;
        this.emailVerified = emailVerified;
        if (this.displayName == null || this.displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    public void updateProfile(String displayName, String phone, String avatarUrl) {
        this.displayName = displayName;
        this.phone = phone;
        this.avatarUrl = avatarUrl;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getKeycloakSub() {
        return keycloakSub;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPublicHandle() {
        return publicHandle;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
