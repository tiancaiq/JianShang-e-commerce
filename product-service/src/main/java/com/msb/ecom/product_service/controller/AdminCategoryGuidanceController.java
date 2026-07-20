package com.msb.ecom.product_service.controller;

import com.msb.ecom.common.web.http.IfMatchVersion;
import com.msb.ecom.product_service.knowledge.CategoryGuidancePageResponse;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceNotFoundException;
import com.msb.ecom.product_service.knowledge.CategoryGuidancePublishRequest;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceService;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceSourceResponse;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceVersionConflictException;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/categories/{categoryId}/guidance/{language}")
@RequiredArgsConstructor
public class AdminCategoryGuidanceController {

    private final CategoryGuidanceService service;
    private final CategoryGuidanceMetrics metrics;

    @GetMapping
    public ResponseEntity<CategoryGuidanceSourceResponse> current(
            @PathVariable String categoryId,
            @PathVariable String language) {
        try {
            ResponseEntity<CategoryGuidanceSourceResponse> response =
                    withEtag(service.current(categoryId, language));
            metrics.command("read-current", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.command("read-current", result(exception));
            throw exception;
        }
    }

    @GetMapping("/versions")
    public CategoryGuidancePageResponse history(
            @PathVariable String categoryId,
            @PathVariable String language,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        try {
            CategoryGuidancePageResponse response =
                    service.history(categoryId, language, cursor, limit);
            metrics.command("read-history", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.command("read-history", result(exception));
            throw exception;
        }
    }

    @PostMapping("/versions")
    public ResponseEntity<CategoryGuidanceSourceResponse> publish(
            @PathVariable String categoryId,
            @PathVariable String language,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody CategoryGuidancePublishRequest request) {
        try {
            ResponseEntity<CategoryGuidanceSourceResponse> response =
                    withEtag(service.publish(categoryId, language, expectedVersion(ifMatch), request));
            metrics.command("publish", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.command("publish", result(exception));
            throw exception;
        }
    }

    @PostMapping("/retire")
    public ResponseEntity<CategoryGuidanceSourceResponse> retire(
            @PathVariable String categoryId,
            @PathVariable String language,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        try {
            ResponseEntity<CategoryGuidanceSourceResponse> response =
                    withEtag(service.retire(categoryId, language, expectedVersion(ifMatch)));
            metrics.command("retire", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.command("retire", result(exception));
            throw exception;
        }
    }

    private long expectedVersion(String ifMatch) {
        try {
            return IfMatchVersion.parseRequired(ifMatch, "Category guidance version is required.");
        } catch (IllegalArgumentException exception) {
            throw new CategoryGuidanceVersionConflictException();
        }
    }

    private ResponseEntity<CategoryGuidanceSourceResponse> withEtag(
            CategoryGuidanceSourceResponse response) {
        return ResponseEntity.ok()
                .eTag("\"" + response.sourceVersion() + "\"")
                .body(response);
    }

    private String result(RuntimeException exception) {
        if (exception instanceof CategoryGuidanceVersionConflictException) {
            return "version-conflict";
        }
        if (exception instanceof com.msb.ecom.product_service.model.ListingAuthorizationException
                || exception instanceof org.springframework.security.core.AuthenticationException) {
            return "forbidden";
        }
        if (exception instanceof IllegalArgumentException) {
            return "invalid";
        }
        if (exception instanceof CategoryGuidanceNotFoundException
                || exception instanceof com.msb.ecom.product_service.model.CategoryNotFoundException) {
            return "not-found";
        }
        if (exception instanceof com.msb.ecom.product_service.knowledge.CategoryGuidanceCategoryInactiveException) {
            return "category-inactive";
        }
        return "failed";
    }
}
