package com.msb.ecom.product_service.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

@RestController
@RequestMapping("/api/v1/admin/catalog/categories")
@RequiredArgsConstructor
public class AdminCatalogController {
    private final CatalogService service;

    @GetMapping
    public CatalogOverview categories(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) CategoryStatus status,
                                      @RequestParam(required = false) SellerEligibility sellerEligibility) {
        return service.overview(q, status, sellerEligibility);
    }

    @GetMapping("/{categoryId}")
    public CategoryDetail category(@PathVariable String categoryId) { return service.detail(categoryId); }

    @PostMapping
    public CategoryDetail create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @RequestBody CreateCategoryRequest request) {
        return service.create(idempotencyKey, request);
    }

    @PatchMapping("/{categoryId}")
    public CategoryDetail update(@PathVariable String categoryId,
                                 @RequestBody UpdateCategoryRequest request) {
        return service.update(categoryId, request);
    }

    @PostMapping("/{categoryId}/move/dry-run")
    public ImpactPreview movePreview(@PathVariable String categoryId,
                                     @RequestBody MoveCategoryRequest request) {
        return service.movePreview(categoryId, request);
    }

    @PostMapping("/{categoryId}/move")
    public CategoryDetail move(@PathVariable String categoryId,
                               @RequestBody MoveCategoryRequest request) {
        return service.move(categoryId, request);
    }

    @PostMapping("/{categoryId}/status/dry-run")
    public ImpactPreview statusPreview(@PathVariable String categoryId,
                                       @RequestBody ChangeCategoryStatusRequest request) {
        return service.statusPreview(categoryId, request);
    }

    @PostMapping("/{categoryId}/status")
    public org.springframework.http.ResponseEntity<Object> status(@PathVariable String categoryId,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @RequestBody ChangeCategoryStatusRequest request) {
        CatalogApproval governance = service.evaluateStatusGovernance(categoryId, idempotencyKey, request);
        Object body = governance.approvalRequired()
                ? governance : service.changeStatus(categoryId, idempotencyKey, request);
        return org.springframework.http.ResponseEntity.status(governance.approvalRequired() ? 202 : 200).body(body);
    }

    @PostMapping("/{categoryId}/policy/dry-run")
    public ImpactPreview policyPreview(@PathVariable String categoryId,
                                       @RequestBody PublishPolicyRequest request) {
        return service.policyPreview(categoryId, request);
    }

    @PostMapping("/{categoryId}/policy")
    public CategoryDetail policy(@PathVariable String categoryId,
                                 @RequestBody PublishPolicyRequest request) {
        return service.publishPolicy(categoryId, request);
    }

    @PostMapping("/{categoryId}/attributes/dry-run")
    public ImpactPreview attributePreview(@PathVariable String categoryId,
                                          @RequestParam String attributeId,
                                          @RequestBody UpdateAttributeRequest request) {
        return service.attributePreview(categoryId, attributeId, request);
    }

    @PostMapping("/{categoryId}/attributes/create/dry-run")
    public ImpactPreview createAttributePreview(@PathVariable String categoryId,
                                                @RequestBody CreateAttributeRequest request) {
        return service.createAttributePreview(categoryId, request);
    }

    @PostMapping("/{categoryId}/attributes")
    public CategoryDetail createAttribute(@PathVariable String categoryId,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestBody CreateAttributeRequest request) {
        return service.createAttribute(categoryId, idempotencyKey, request);
    }

    @PatchMapping("/{categoryId}/attributes/{attributeId}")
    public CategoryDetail updateAttribute(@PathVariable String categoryId,
                                          @PathVariable String attributeId,
                                          @RequestBody UpdateAttributeRequest request) {
        return service.updateAttribute(categoryId, attributeId, request);
    }

    @PostMapping("/{categoryId}/attributes/{attributeId}/status")
    public CategoryDetail attributeStatus(@PathVariable String categoryId,
                                          @PathVariable String attributeId,
                                          @RequestBody ChangeAttributeStatusRequest request) {
        return service.changeAttributeStatus(categoryId, attributeId, request);
    }

    @PostMapping("/{categoryId}/attributes/{attributeId}/status/dry-run")
    public ImpactPreview attributeStatusPreview(@PathVariable String categoryId,
                                                @PathVariable String attributeId,
                                                @RequestBody ChangeAttributeStatusRequest request) {
        return service.attributeStatusPreview(categoryId, attributeId, request);
    }

    @PostMapping("/{categoryId}/attributes/{attributeId}/options")
    public CategoryDetail createOption(@PathVariable String categoryId,
                                       @PathVariable String attributeId,
                                       @RequestBody CreateOptionRequest request) {
        return service.createOption(categoryId, attributeId, request);
    }

    @PostMapping("/{categoryId}/attributes/{attributeId}/options/{optionId}/status")
    public CategoryDetail optionStatus(@PathVariable String categoryId,
                                       @PathVariable String attributeId,
                                       @PathVariable String optionId,
                                       @RequestBody ChangeOptionStatusRequest request) {
        return service.changeOptionStatus(categoryId, attributeId, optionId, request);
    }

    @PostMapping("/{categoryId}/attributes/{attributeId}/options/{optionId}/status/dry-run")
    public ImpactPreview optionStatusPreview(@PathVariable String categoryId,
                                             @PathVariable String attributeId,
                                             @PathVariable String optionId,
                                             @RequestBody ChangeOptionStatusRequest request) {
        return service.optionStatusPreview(categoryId, attributeId, optionId, request);
    }

    @PostMapping("/{categoryId}/guidance")
    public CategoryDetail createGuidance(@PathVariable String categoryId,
                                         @RequestBody CreateGuidanceRequest request) {
        return service.createGuidance(categoryId, request);
    }

    @PatchMapping("/{categoryId}/guidance/{guidanceId}")
    public CategoryDetail updateGuidance(@PathVariable String categoryId,
                                         @PathVariable String guidanceId,
                                         @RequestBody UpdateGuidanceRequest request) {
        return service.updateGuidance(categoryId, guidanceId, request);
    }

    @PostMapping("/{categoryId}/guidance/{guidanceId}/status")
    public CategoryDetail guidanceStatus(@PathVariable String categoryId,
                                         @PathVariable String guidanceId,
                                         @RequestBody ChangeGuidanceStatusRequest request) {
        return service.changeGuidanceStatus(categoryId, guidanceId, request);
    }
}
