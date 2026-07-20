package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

public class AddressPatchRequest {

    private boolean labelPresent;
    private String label;
    private boolean recipientNamePresent;
    private String recipientName;
    private boolean phonePresent;
    private String phone;
    private boolean line1Present;
    private String line1;
    private boolean line2Present;
    private String line2;
    private boolean cityPresent;
    private String city;
    private boolean regionPresent;
    private String region;
    private boolean postalCodePresent;
    private String postalCode;
    private boolean countryCodePresent;
    private String countryCode;

    public boolean hasChanges() {
        return labelPresent
                || recipientNamePresent
                || phonePresent
                || line1Present
                || line2Present
                || cityPresent
                || regionPresent
                || postalCodePresent
                || countryCodePresent;
    }

    public boolean labelPresent() {
        return labelPresent;
    }

    public String label() {
        return label;
    }

    @JsonSetter("label")
    public void setLabel(String label) {
        this.labelPresent = true;
        this.label = label;
    }

    public boolean recipientNamePresent() {
        return recipientNamePresent;
    }

    public String recipientName() {
        return recipientName;
    }

    @JsonSetter("recipientName")
    public void setRecipientName(String recipientName) {
        this.recipientNamePresent = true;
        this.recipientName = recipientName;
    }

    public boolean phonePresent() {
        return phonePresent;
    }

    public String phone() {
        return phone;
    }

    @JsonSetter("phone")
    public void setPhone(String phone) {
        this.phonePresent = true;
        this.phone = phone;
    }

    public boolean line1Present() {
        return line1Present;
    }

    public String line1() {
        return line1;
    }

    @JsonSetter("line1")
    public void setLine1(String line1) {
        this.line1Present = true;
        this.line1 = line1;
    }

    public boolean line2Present() {
        return line2Present;
    }

    public String line2() {
        return line2;
    }

    @JsonSetter("line2")
    public void setLine2(String line2) {
        this.line2Present = true;
        this.line2 = line2;
    }

    public boolean cityPresent() {
        return cityPresent;
    }

    public String city() {
        return city;
    }

    @JsonSetter("city")
    public void setCity(String city) {
        this.cityPresent = true;
        this.city = city;
    }

    public boolean regionPresent() {
        return regionPresent;
    }

    public String region() {
        return region;
    }

    @JsonSetter("region")
    public void setRegion(String region) {
        this.regionPresent = true;
        this.region = region;
    }

    public boolean postalCodePresent() {
        return postalCodePresent;
    }

    public String postalCode() {
        return postalCode;
    }

    @JsonSetter("postalCode")
    public void setPostalCode(String postalCode) {
        this.postalCodePresent = true;
        this.postalCode = postalCode;
    }

    public boolean countryCodePresent() {
        return countryCodePresent;
    }

    public String countryCode() {
        return countryCode;
    }

    @JsonSetter("countryCode")
    public void setCountryCode(String countryCode) {
        this.countryCodePresent = true;
        this.countryCode = countryCode;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported address field: " + fieldName);
    }
}
