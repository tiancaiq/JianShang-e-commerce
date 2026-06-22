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
@Table(name = "business_applications")
public class BusinessApplication {

    @Id
    @Column(length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String id;

    @Column(name = "applicant_user_id", length = 26, nullable = false, updatable = false, columnDefinition = "char(26)")
    private String applicantUserId;

    @Column(name = "legal_name", length = 200, nullable = false)
    private String legalName;

    @Column(name = "business_type", length = 64, nullable = false)
    private String businessType;

    @Column(length = 2, nullable = false, columnDefinition = "char(2)")
    private String country;

    @Column(name = "contact_email", length = 320, nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "public_city", length = 120, nullable = false)
    private String publicCity;

    @Column(name = "public_region", length = 120, nullable = false)
    private String publicRegion;

    @Column(name = "website_url", length = 2048)
    private String websiteUrl;

    @Column(length = 1000)
    private String description;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(name = "submitted_at", columnDefinition = "datetime(6)")
    private Instant submittedAt;

    @Column(name = "reviewer_user_id", length = 26, columnDefinition = "char(26)")
    private String reviewerUserId;

    @Column(name = "approved_business_id", length = 26, columnDefinition = "char(26)")
    private String approvedBusinessId;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "decided_at", columnDefinition = "datetime(6)")
    private Instant decidedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected BusinessApplication() {
    }

    private BusinessApplication(
            String id,
            String applicantUserId,
            String legalName,
            String businessType,
            String country,
            String contactEmail,
            String contactPhone,
            String publicCity,
            String publicRegion,
            String websiteUrl,
            String description) {
        this.id = id;
        this.applicantUserId = applicantUserId;
        this.status = "DRAFT";
        updateDraft(legalName, businessType, country, contactEmail, contactPhone, publicCity, publicRegion, websiteUrl, description);
    }

    public static BusinessApplication draft(
            String id,
            String applicantUserId,
            String legalName,
            String businessType,
            String country,
            String contactEmail,
            String contactPhone,
            String publicCity,
            String publicRegion,
            String websiteUrl,
            String description) {
        return new BusinessApplication(
                id,
                applicantUserId,
                legalName,
                businessType,
                country,
                contactEmail,
                contactPhone,
                publicCity,
                publicRegion,
                websiteUrl,
                description);
    }

    public void updateDraft(
            String legalName,
            String businessType,
            String country,
            String contactEmail,
            String contactPhone,
            String publicCity,
            String publicRegion,
            String websiteUrl,
            String description) {
        this.legalName = legalName;
        this.businessType = businessType;
        this.country = country;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.publicCity = publicCity;
        this.publicRegion = publicRegion;
        this.websiteUrl = websiteUrl;
        this.description = description;
    }

    public void submit(Instant submittedAt) {
        this.status = "PENDING_VERIFICATION";
        this.submittedAt = submittedAt;
    }

    public void applyProviderOutcome(String status) {
        this.status = status;
    }

    public void approve(String reviewerUserId, String approvedBusinessId, String reason, Instant decidedAt) {
        this.status = "APPROVED";
        this.reviewerUserId = reviewerUserId;
        this.approvedBusinessId = approvedBusinessId;
        this.decisionReason = reason;
        this.decidedAt = decidedAt;
    }

    public void reject(String reviewerUserId, String reason, Instant decidedAt) {
        this.status = "REJECTED";
        this.reviewerUserId = reviewerUserId;
        this.decisionReason = reason;
        this.decidedAt = decidedAt;
    }

    public void requestInformation(String reviewerUserId, String reason, Instant decidedAt) {
        this.status = "INFORMATION_REQUESTED";
        this.reviewerUserId = reviewerUserId;
        this.decisionReason = reason;
        this.decidedAt = decidedAt;
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

    public String getApplicantUserId() {
        return applicantUserId;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getBusinessType() {
        return businessType;
    }

    public String getCountry() {
        return country;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getPublicCity() {
        return publicCity;
    }

    public String getPublicRegion() {
        return publicRegion;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getReviewerUserId() {
        return reviewerUserId;
    }

    public String getApprovedBusinessId() {
        return approvedBusinessId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
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
