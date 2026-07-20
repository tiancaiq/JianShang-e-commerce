package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.knowledge.CategoryGuidancePageResponse;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceSourceResponse;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceSourceService;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent/knowledge/category-guidance")
@RequiredArgsConstructor
public class InternalCategoryGuidanceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Service-Token";

    private final CategoryGuidanceSourceService sourceService;
    private final CategoryGuidanceMetrics metrics;

    @GetMapping("/{categoryId}/languages/{language}/versions/{sourceVersion}")
    public CategoryGuidanceSourceResponse exact(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String categoryId,
            @PathVariable String language,
            @PathVariable long sourceVersion) {
        try {
            CategoryGuidanceSourceResponse response =
                    sourceService.exact(internalToken, categoryId, language, sourceVersion);
            metrics.sourceRead("exact", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.sourceRead("exact", sourceResult(exception));
            throw exception;
        }
    }

    @GetMapping("/export")
    public CategoryGuidancePageResponse export(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        try {
            CategoryGuidancePageResponse response =
                    sourceService.export(internalToken, cursor, limit);
            metrics.sourceRead("export", "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.sourceRead("export", sourceResult(exception));
            throw exception;
        }
    }

    private String sourceResult(RuntimeException exception) {
        if (exception instanceof com.msb.ecom.product_service.model.ListingAuthorizationException) {
            return "forbidden";
        }
        if (exception instanceof com.msb.ecom.product_service.knowledge.CategoryGuidanceNotFoundException) {
            return "not-found";
        }
        if (exception instanceof IllegalArgumentException) {
            return "invalid";
        }
        return "failed";
    }
}
