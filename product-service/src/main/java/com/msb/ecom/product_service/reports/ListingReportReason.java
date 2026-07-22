package com.msb.ecom.product_service.reports;

enum ListingReportReason {
    PROHIBITED_OR_REGULATED_ITEM("TRUST_SAFETY_LEGAL", "HIGH"),
    DANGEROUS_OR_UNSAFE_ITEM("TRUST_SAFETY", "HIGH"),
    FRAUD_OR_MISREPRESENTATION("MARKETPLACE_INTEGRITY", "NORMAL"),
    COUNTERFEIT_OR_IP_CONCERN("LEGAL_IP", "NORMAL"),
    HATE_HARASSMENT_OR_THREAT("TRUST_SAFETY", "HIGH"),
    SEXUAL_OR_EXPLOITATIVE_CONTENT("TRUST_SAFETY", "HIGH"),
    PRIVACY_OR_PERSONAL_DATA("PRIVACY", "HIGH"),
    DUPLICATE_OR_SPAM("MARKETPLACE_INTEGRITY", "LOW"),
    OTHER_POLICY_CONCERN("MARKETPLACE_INTEGRITY", "NORMAL");

    private final String routingQueue;
    private final String priority;

    ListingReportReason(String routingQueue, String priority) {
        this.routingQueue = routingQueue;
        this.priority = priority;
    }

    String routingQueue() {
        return routingQueue;
    }

    String priority() {
        return priority;
    }

    static ListingReportReason parse(String value) {
        if (value == null) {
            throw new ListingReportInvalidRequestException();
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ListingReportInvalidRequestException();
        }
    }
}
