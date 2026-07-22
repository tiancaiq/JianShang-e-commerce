package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ListingReportCreateRequest {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    @NotBlank
    @Pattern(regexp = ULID)
    private String listingId;

    @NotBlank
    private String reasonCode;

    @Size(max = 2000)
    private String statement;

    @Size(max = 4)
    private List<@NotBlank @Pattern(regexp = ULID) String> listingMediaIds;

    public ListingReportCreateRequest() {
    }

    public String listingId() {
        return listingId;
    }

    public void setListingId(String listingId) {
        this.listingId = listingId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String statement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public List<String> listingMediaIds() {
        return listingMediaIds;
    }

    public void setListingMediaIds(List<String> listingMediaIds) {
        this.listingMediaIds = listingMediaIds;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new ListingReportInvalidRequestException();
    }
}
