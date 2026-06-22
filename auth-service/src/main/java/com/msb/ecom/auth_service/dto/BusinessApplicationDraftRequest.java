package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class BusinessApplicationDraftRequest {

    @NotBlank
    @Size(max = 200)
    private String legalName;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[A-Z_]+$")
    private String businessType;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{2}$")
    private String country;

    @NotBlank
    @Email
    @Size(max = 320)
    private String contactEmail;

    @Size(max = 32)
    @Pattern(regexp = "^$|^\\+[1-9][0-9]{7,14}$")
    private String contactPhone;

    @NotBlank
    @Size(max = 120)
    @Pattern(regexp = "^[\\p{L}\\p{M} .'-]+$")
    private String publicCity;

    @NotBlank
    @Size(max = 120)
    @Pattern(regexp = "^[\\p{L}\\p{M}0-9 .'-]+$")
    private String publicRegion;

    @Size(max = 2048)
    @Pattern(regexp = "^$|^https?://.+")
    private String websiteUrl;

    @Size(max = 1000)
    private String description;

    public String legalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String businessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String country() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String contactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String contactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String publicCity() {
        return publicCity;
    }

    public void setPublicCity(String publicCity) {
        this.publicCity = publicCity;
    }

    public String publicRegion() {
        return publicRegion;
    }

    public void setPublicRegion(String publicRegion) {
        this.publicRegion = publicRegion;
    }

    public String websiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported business application field: " + fieldName);
    }
}
