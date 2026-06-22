package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ActivateIndividualSellerRequest {

    @NotBlank
    @Size(max = 120)
    @Pattern(regexp = "^[\\p{L}\\p{M} .'-]+$")
    private String publicCity;

    @NotBlank
    @Size(max = 120)
    @Pattern(regexp = "^[\\p{L}\\p{M}0-9 .'-]+$")
    private String publicRegion;

    @NotBlank
    @Size(max = 32)
    @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$")
    private String termsVersion;

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

    public String termsVersion() {
        return termsVersion;
    }

    public void setTermsVersion(String termsVersion) {
        this.termsVersion = termsVersion;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported seller activation field: " + fieldName);
    }
}
