package com.msb.ecom.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class AddressCreateRequest {

    private String label;
    private String recipientName;
    private String phone;
    private String line1;
    private String line2;
    private String city;
    private String region;
    private String postalCode;
    private String countryCode;

    public String label() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String recipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String phone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String line1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1;
    }

    public String line2() {
        return line2;
    }

    public void setLine2(String line2) {
        this.line2 = line2;
    }

    public String city() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String region() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String postalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String countryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported address field: " + fieldName);
    }
}
