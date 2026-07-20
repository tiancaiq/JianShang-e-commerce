package com.msb.ecom.product_service.agentmedia;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AgentListingMediaRequest {

    private static final String ULID_PATTERN = "^[0-9A-Z]{26}$";

    @NotBlank
    @Pattern(regexp = "^ai-list-owned-draft-media-v1$")
    private String schemaVersion;

    @NotBlank
    @Pattern(regexp = ULID_PATTERN)
    private String actorUserId;

    @NotNull
    @Size(min = 1, max = 4)
    private List<
            @NotBlank
            @Pattern(regexp = ULID_PATTERN)
            String> mediaIds;

    public AgentListingMediaRequest() {
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String actorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public List<String> mediaIds() {
        return mediaIds;
    }

    public void setMediaIds(List<String> mediaIds) {
        this.mediaIds = mediaIds;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("Unknown request field: " + fieldName);
    }
}
