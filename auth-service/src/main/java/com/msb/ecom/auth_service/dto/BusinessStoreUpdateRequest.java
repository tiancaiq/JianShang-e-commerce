package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class BusinessStoreUpdateRequest {

    @NotBlank
    @Size(max = 160)
    private String name;

    @NotBlank
    @Size(min = 3, max = 100)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$")
    private String slug;

    @Size(max = 1000)
    private String description;

    @Size(max = 2048)
    @Pattern(regexp = "^$|^https?://.+|^/api/v1/.+")
    private String logoUrl;

    @Size(max = 2048)
    @Pattern(regexp = "^$|^https?://.+|^/api/v1/.+")
    private String bannerUrl;

    @Size(max = 320)
    @Email
    private String supportEmail;

    @Size(max = 32)
    @Pattern(regexp = "^$|^\\+[1-9][0-9]{7,14}$")
    private String supportPhone;

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String slug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String logoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String bannerUrl() {
        return bannerUrl;
    }

    public void setBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }

    public String supportEmail() {
        return supportEmail;
    }

    public void setSupportEmail(String supportEmail) {
        this.supportEmail = supportEmail;
    }

    public String supportPhone() {
        return supportPhone;
    }

    public void setSupportPhone(String supportPhone) {
        this.supportPhone = supportPhone;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported business store field: " + fieldName);
    }
}
