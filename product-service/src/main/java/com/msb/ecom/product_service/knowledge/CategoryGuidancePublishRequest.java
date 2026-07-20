package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public class CategoryGuidancePublishRequest {

    private String title;
    private String body;

    public CategoryGuidancePublishRequest() {
    }

    public CategoryGuidancePublishRequest(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public String title() {
        return title;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String body() {
        return body;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    @JsonAnySetter
    void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported category guidance field: " + fieldName);
    }
}
