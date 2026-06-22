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
@Table(name = "individual_seller_profiles")
public class IndividualSellerProfile {

    @Id
    @Column(length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String id;

    @Column(name = "user_id", length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String userId;

    @Column(name = "public_city", length = 120, nullable = false)
    private String publicCity;

    @Column(name = "public_region", length = 120, nullable = false)
    private String publicRegion;

    @Column(name = "terms_version", length = 32, nullable = false, updatable = false)
    private String termsVersion;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "completed_sales_count", nullable = false)
    private long completedSalesCount;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected IndividualSellerProfile() {
    }

    private IndividualSellerProfile(
            String id,
            String userId,
            String publicCity,
            String publicRegion,
            String termsVersion) {
        this.id = id;
        this.userId = userId;
        this.publicCity = publicCity;
        this.publicRegion = publicRegion;
        this.termsVersion = termsVersion;
        this.status = "ACTIVE";
        this.completedSalesCount = 0;
    }

    public static IndividualSellerProfile activate(
            String id,
            String userId,
            String publicCity,
            String publicRegion,
            String termsVersion) {
        return new IndividualSellerProfile(id, userId, publicCity, publicRegion, termsVersion);
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

    public String getUserId() {
        return userId;
    }

    public String getPublicCity() {
        return publicCity;
    }

    public String getPublicRegion() {
        return publicRegion;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getStatus() {
        return status;
    }

    public long getCompletedSalesCount() {
        return completedSalesCount;
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
