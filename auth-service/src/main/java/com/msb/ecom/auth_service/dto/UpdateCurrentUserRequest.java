package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateCurrentUserRequest {

    @Size(max = 200)
    private String displayName;

    @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")
    private String phone;

    @Size(max = 2048)
    @Pattern(regexp = "^https?://.+")
    private String avatarUrl;

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String phone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String avatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported profile field: " + fieldName);
    }
}
