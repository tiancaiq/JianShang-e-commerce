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
@Table(name = "addresses")
public class Address {

    @Id
    @Column(length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String id;

    @Column(name = "user_id", length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String userId;

    @Column(length = 40)
    private String label;

    @Column(name = "recipient_name", length = 120, nullable = false)
    private String recipientName;

    @Column(length = 32, nullable = false)
    private String phone;

    @Column(length = 200, nullable = false)
    private String line1;

    @Column(length = 200)
    private String line2;

    @Column(length = 100, nullable = false)
    private String city;

    @Column(length = 100, nullable = false)
    private String region;

    @Column(name = "postal_code", length = 32, nullable = false)
    private String postalCode;

    @Column(name = "country_code", length = 2, nullable = false, columnDefinition = "char(2)")
    private String countryCode;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected Address() {
    }

    private Address(
            String id,
            String userId,
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode,
            boolean defaultAddress) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.region = region;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
        this.defaultAddress = defaultAddress;
    }

    public static Address create(
            String id,
            String userId,
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode,
            boolean defaultAddress) {
        return new Address(
                id,
                userId,
                label,
                recipientName,
                phone,
                line1,
                line2,
                city,
                region,
                postalCode,
                countryCode,
                defaultAddress);
    }

    public void update(
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.region = region;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }

    public void setDefaultAddress(boolean defaultAddress) {
        this.defaultAddress = defaultAddress;
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

    public String getLabel() {
        return label;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getPhone() {
        return phone;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getRegion() {
        return region;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
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
