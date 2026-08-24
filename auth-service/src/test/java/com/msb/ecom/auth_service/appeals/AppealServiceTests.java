package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.*;
import com.msb.ecom.auth_service.appeals.AppealRepository.AppealRow;
import com.msb.ecom.auth_service.appeals.AppealRepository.EnforcementRow;
import com.msb.ecom.auth_service.appeals.ListingAppealContextClient.ListingEnforcementContext;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AdminBusinessService;
import com.msb.ecom.auth_service.service.AdminUserService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppealServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final String USER = "01ARZ3NDEKTSV4RRFFQ69G5AAA";
    private static final String OTHER = "01ARZ3NDEKTSV4RRFFQ69G5AAB";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G5AAC";
    private static final String APPEAL = "01ARZ3NDEKTSV4RRFFQ69G5AAD";
    private static final String ADMIN = "01ARZ3NDEKTSV4RRFFQ69G5AAE";
    private static final String BUSINESS = "01ARZ3NDEKTSV4RRFFQ69G5AAF";
    private static final String LISTING = "01ARZ3NDEKTSV4RRFFQ69G5AAG";
    private static final String REPLACEMENT = "01ARZ3NDEKTSV4RRFFQ69G5AAH";

    @Mock AppealRepository repository;
    @Mock ListingAppealContextClient listings;
    @Mock AuthService authService;
    @Mock AdminAuthorizationService authorization;
    @Mock AdminUserService adminUsers;
    @Mock AdminBusinessService adminBusinesses;
    @Mock EnforcementService enforcements;
    @Mock AppealResolutionCommandService resolutionCommands;
    @Mock UlidGenerator ulids;
    @Mock User actor;
    private AppealService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
        lenient().when(authService.ensureUserEntity()).thenReturn(actor);
        lenient().when(actor.getId()).thenReturn(USER);
        lenient().when(actor.getDisplayName()).thenReturn("Affected user");
        lenient().when(repository.authorizedBusinessIds(USER)).thenReturn(Set.of());
        lenient().when(ulids.next()).thenReturn(APPEAL);
        lenientDetailRepositoryDefaults();
        lenient().when(repository.replacementScopes(anyString())).thenReturn(Set.of(Scope.USER_SELLING));
        lenient().when(repository.currentTarget(any(), anyString())).thenReturn(
                new ObjectMapper().valueToTree(Map.of("id", USER, "status", "ACTIVE", "version", 2)));
        lenient().when(enforcements.evaluate(any(), anyString(), any())).thenReturn(List.of(
                new EffectiveRestriction(Scope.USER_SELLING, ActionType.SUSPEND, ACTION)));
        lenient().when(enforcements.lockState(any(), anyString(), any())).thenReturn(
                new EnforcementService.StatePin(2L, "local-state-one", List.of(
                        new EffectiveRestriction(Scope.USER_SELLING, ActionType.SUSPEND, ACTION))));
    }

    private void lenientDetailRepositoryDefaults() {
        lenient().when(repository.admin(any())).thenReturn(Optional.empty());
        lenient().when(repository.caseSummary(any())).thenReturn(Optional.empty());
        lenient().when(repository.caseReports(any())).thenReturn(List.of());
        lenient().when(repository.originalSnapshot(any())).thenReturn(new ObjectMapper().nullNode());
        lenient().when(repository.localEnforcementEvents(anyString())).thenReturn(List.of());
        lenient().when(repository.caseEvents(any())).thenReturn(List.of());
        lenient().when(repository.events(anyString())).thenReturn(List.of());
        lenient().when(repository.notes(anyString())).thenReturn(List.of());
    }

    @Test
    void affectedUserCanAppealOneActiveActionWithServerDerivedIdentity() {
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insert(any())).thenReturn(true);

        SubmissionResult result = service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.DECISION_INCORRECT, "This decision is incorrect", List.of()),
                "corr");

        assertThat(result.appealId()).isEqualTo(APPEAL);
        ArgumentCaptor<AppealRow> saved = ArgumentCaptor.forClass(AppealRow.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().appellantUserId()).isEqualTo(USER);
        assertThat(saved.getValue().targetId()).isEqualTo(USER);
        assertThat(saved.getValue().status()).isEqualTo(Status.SUBMITTED);
    }

    @Test
    void unrelatedUserCannotAppealUserEnforcement() {
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(OTHER, "ACTIVE")));

        assertThatThrownBy(() -> service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.NEW_EVIDENCE, "New context", List.of()), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_NOT_ELIGIBLE"));
        verify(repository, never()).insert(any());
    }

    @Test
    void activeOwnerOrManagerCanAppealBusinessEnforcementButUnrelatedUserCannot() {
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(
                enforcement(TargetType.BUSINESS, BUSINESS, "Active business", Set.of(Scope.BUSINESS_NEW_SALES))));
        when(repository.authorizedBusinessIds(USER)).thenReturn(Set.of(BUSINESS));
        when(repository.insert(any())).thenReturn(true);

        service.submit(ACTION, new CreateAppealRequest(ReasonCode.POLICY_MISAPPLIED,
                "The business policy was applied incorrectly", List.of()), "corr");

        ArgumentCaptor<AppealRow> saved = ArgumentCaptor.forClass(AppealRow.class);
        verify(repository).insert(saved.capture());
        assertThat(saved.getValue().appellantType()).isEqualTo(AppellantType.BUSINESS_REPRESENTATIVE);
        assertThat(saved.getValue().targetId()).isEqualTo(BUSINESS);

        reset(repository);
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(
                enforcement(TargetType.BUSINESS, BUSINESS, "Active business", Set.of(Scope.BUSINESS_NEW_SALES))));
        when(repository.authorizedBusinessIds(USER)).thenReturn(Set.of());
        assertThatThrownBy(() -> service.submit(ACTION, new CreateAppealRequest(
                ReasonCode.DECISION_INCORRECT, "I do not represent this business", List.of()), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_NOT_ELIGIBLE"));
    }

    @Test
    void individualAndBusinessListingOwnersAreDerivedFromProductContext() {
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(USER, null)));
        when(repository.insert(any())).thenReturn(true);

        service.submit(ACTION, new CreateAppealRequest(ReasonCode.NEW_EVIDENCE,
                "The listing context has changed", List.of()), "corr");

        ArgumentCaptor<AppealRow> individual = ArgumentCaptor.forClass(AppealRow.class);
        verify(repository).insert(individual.capture());
        assertThat(individual.getValue().appellantType()).isEqualTo(AppellantType.LISTING_OWNER);
        assertThat(individual.getValue().targetId()).isEqualTo(LISTING);

        reset(repository, listings);
        when(repository.authorizedBusinessIds(USER)).thenReturn(Set.of(BUSINESS));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(null, BUSINESS)));
        when(repository.insert(any())).thenReturn(true);
        service.submit(ACTION, new CreateAppealRequest(ReasonCode.NEW_EVIDENCE,
                "The business listing context has changed", List.of()), "corr");
        verify(repository).insert(argThat(value -> value.appellantType() == AppellantType.LISTING_OWNER
                && LISTING.equals(value.targetId())));
    }

    @Test
    void revokedActionAndDuplicateAppealAreRejected() {
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "REVOKED")));
        assertThatThrownBy(() -> service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.OTHER, "Review this action", List.of()), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("ENFORCEMENT_NOT_APPEALABLE"));

        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insert(any())).thenReturn(false);
        assertThatThrownBy(() -> service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.OTHER, "Review this action", List.of()), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_ALREADY_EXISTS"));
    }

    @Test
    void otherRequiresExplanationAndEvidenceReferencesRemainClosed() {
        assertThatThrownBy(() -> service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.OTHER, " ", List.of()), "corr"))
                .isInstanceOf(AppealException.class);
        assertThatThrownBy(() -> service.submit(ACTION,
                new CreateAppealRequest(ReasonCode.NEW_EVIDENCE, "See attachment", List.of("unsafe")), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_EVIDENCE_REFERENCE_UNSUPPORTED"));
    }

    @Test
    void modifyRecommendationPersistsProposalWithoutExecutingEnforcement() {
        when(actor.getId()).thenReturn(ADMIN);
        when(authorization.accessFor(actor)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.appeal.read", "admin.appeal.review")));
        AppealRow row = appeal(Status.UNDER_REVIEW, 4);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(row));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(appeal(Status.MODIFY_RECOMMENDED, 5)));
        when(repository.userLabel(USER)).thenReturn("Affected user");
        when(repository.currentTarget(TargetType.USER, USER)).thenReturn(new ObjectMapper().valueToTree(Map.of("version", 2)));

        service.review(APPEAL, new ReviewRequest(4L, ReviewOutcome.MODIFY_RECOMMENDED,
                "POLICY_REVIEW", "Use a narrower temporary restriction",
                new ReplacementProposal(TargetType.USER, USER, ActionType.RESTRICT,
                        Set.of(Scope.USER_SELLING), NOW.plusSeconds(3600), "APPEAL_MODIFICATION",
                        "Temporary selling restriction", 2L)), "corr");

        verify(repository).recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                eq("POLICY_REVIEW"), anyString(), argThat(value -> value.actionType() == ActionType.RESTRICT), eq(NOW));
    }

    @Test
    void targetWideReplacementRecommendationsPersistCanonicalScopesAndReasonCode() {
        adminAccess("admin.appeal.read", "admin.appeal.review");
        AppealRow userReview = appeal(Status.UNDER_REVIEW, 4);
        AppealRow userRecommended = withReplacement(appeal(Status.MODIFY_RECOMMENDED, 5),
                ActionType.BAN, NOW.plusSeconds(3600), "APPEAL_ADJUSTED");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(userReview));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(userRecommended));
        when(repository.replacementScopes(APPEAL)).thenReturn(Set.of(Scope.USER_BUYING, Scope.USER_SELLING));

        service.review(APPEAL, new ReviewRequest(4L, ReviewOutcome.MODIFY_RECOMMENDED,
                "POLICY_REVIEW", "Apply the target-wide action",
                new ReplacementProposal(TargetType.USER, USER, ActionType.BAN,
                        Set.of(Scope.USER_SELLING), NOW.plusSeconds(3600), "appeal_adjusted",
                        "Adjusted enforcement", 2L)), "corr");

        verify(repository).recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                eq("POLICY_REVIEW"), anyString(), argThat(value ->
                        value.scopes().equals(Set.of(Scope.USER_BUYING, Scope.USER_SELLING))
                                && value.reasonCode().equals("APPEAL_ADJUSTED")), eq(NOW));
    }

    @Test
    void businessBanAndListingSuspendRecommendationsPersistTheirFullOperationalScopes() {
        adminAccess("admin.appeal.read", "admin.appeal.review");
        AppealRow businessReview = withTarget(appeal(Status.UNDER_REVIEW, 4),
                TargetType.BUSINESS, BUSINESS, "Appealable business");
        AppealRow businessRecommended = withReplacement(withTarget(appeal(Status.MODIFY_RECOMMENDED, 5),
                TargetType.BUSINESS, BUSINESS, "Appealable business"), ActionType.BAN,
                NOW.plusSeconds(3600), "APPEAL_ADJUSTED");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(businessReview));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(
                TargetType.BUSINESS, BUSINESS, "Appealable business", Set.of(Scope.BUSINESS_NEW_SALES))));
        when(repository.recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(businessRecommended));
        when(repository.replacementScopes(APPEAL)).thenReturn(Set.of(
                Scope.BUSINESS_LISTING_CREATION, Scope.BUSINESS_LISTING_PUBLICATION,
                Scope.BUSINESS_NEW_SALES));

        service.review(APPEAL, new ReviewRequest(4L, ReviewOutcome.MODIFY_RECOMMENDED,
                "POLICY_REVIEW", "Apply the target-wide action",
                new ReplacementProposal(TargetType.BUSINESS, BUSINESS, ActionType.BAN,
                        Set.of(Scope.BUSINESS_NEW_SALES), NOW.plusSeconds(3600), "APPEAL_ADJUSTED",
                        "Adjusted enforcement", 2L)), "corr");

        verify(repository).recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), argThat(value -> value.scopes().equals(Set.of(
                        Scope.BUSINESS_LISTING_CREATION, Scope.BUSINESS_LISTING_PUBLICATION,
                        Scope.BUSINESS_NEW_SALES))), eq(NOW));

        reset(repository, listings);
        lenientDetailRepositoryDefaults();
        AppealRow listingReview = listingAppeal(Status.UNDER_REVIEW, 4, null);
        AppealRow listingRecommended = withReplacement(
                listingAppeal(Status.MODIFY_RECOMMENDED, 5, null), ActionType.SUSPEND,
                NOW.plusSeconds(3600), "APPEAL_ADJUSTED");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(listingReview));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(USER, null)));
        when(repository.recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), any(), eq(NOW))).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(listingRecommended));
        when(repository.replacementScopes(APPEAL)).thenReturn(Set.of(
                Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));

        service.review(APPEAL, new ReviewRequest(4L, ReviewOutcome.MODIFY_RECOMMENDED,
                "POLICY_REVIEW", "Apply the listing-wide action",
                new ReplacementProposal(TargetType.LISTING, LISTING, ActionType.SUSPEND,
                        Set.of(Scope.LISTING_PUBLIC_VISIBILITY), NOW.plusSeconds(3600),
                        "APPEAL_ADJUSTED", "Adjusted listing enforcement", 2L)), "corr");

        verify(repository).recommend(eq(APPEAL), eq(4L), eq(ADMIN), eq(ReviewOutcome.MODIFY_RECOMMENDED),
                anyString(), anyString(), argThat(value -> value.scopes().equals(Set.of(
                        Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY))), eq(NOW));
    }

    @Test
    void invalidReplacementReasonCodeLeavesReviewStateUnchanged() {
        adminAccess("admin.appeal.read", "admin.appeal.review");
        AppealRow underReview = appeal(Status.UNDER_REVIEW, 4);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(underReview));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));

        assertThatThrownBy(() -> service.review(APPEAL, new ReviewRequest(4L,
                ReviewOutcome.MODIFY_RECOMMENDED, "POLICY_REVIEW", "Use a narrower action",
                new ReplacementProposal(TargetType.USER, USER, ActionType.RESTRICT,
                        Set.of(Scope.USER_SELLING), NOW.plusSeconds(3600), "bad reason",
                        "Adjusted enforcement", 2L)), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_REPLACEMENT_REASON_CODE_INVALID"));

        assertThat(underReview.status()).isEqualTo(Status.UNDER_REVIEW);
        assertThat(underReview.version()).isEqualTo(4);
        verify(repository, never()).recommend(anyString(), anyLong(), anyString(), any(), anyString(),
                anyString(), any(), any());
        verify(repository, never()).insertEvent(any());
    }

    @Test
    void claimAndReleaseEnforceVersionAndAssignmentOwnership() {
        adminAccess("admin.appeal.read", "admin.appeal.assign", "admin.appeal.review");
        AppealRow unassigned = appeal(Status.SUBMITTED, 2, null);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(unassigned));
        when(repository.claim(APPEAL, 2, ADMIN, NOW)).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(appeal(Status.SUBMITTED, 3, ADMIN)));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.userLabel(USER)).thenReturn("Affected user");

        Detail claimed = service.claim(APPEAL, new VersionRequest(2L), "corr");
        assertThat(claimed.availableAdminCapabilities().isAssignedToMe()).isTrue();

        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(appeal(Status.SUBMITTED, 3, OTHER)));
        assertThatThrownBy(() -> service.release(APPEAL, new VersionRequest(3L), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_ASSIGNED_TO_ANOTHER_ADMIN"));
        verify(repository, never()).release(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void revokeRecommendationIsImmutableAndDoesNotCallAnEnforcementCommand() {
        adminAccess("admin.appeal.read", "admin.appeal.review");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(appeal(Status.UNDER_REVIEW, 4, ADMIN)));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.recommend(APPEAL, 4, ADMIN, ReviewOutcome.REVOKE_RECOMMENDED,
                "WRONG_DECISION", "The action should be revoked", null, NOW)).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(appeal(Status.REVOKE_RECOMMENDED, 5, ADMIN)));
        when(repository.userLabel(USER)).thenReturn("Affected user");

        Detail reviewed = service.review(APPEAL, new ReviewRequest(4L, ReviewOutcome.REVOKE_RECOMMENDED,
                "WRONG_DECISION", "The action should be revoked", null), "corr");
        assertThat(reviewed.status()).isEqualTo(Status.REVOKE_RECOMMENDED);
        assertThat(reviewed.availableAdminCapabilities().isReadOnly()).isTrue();
        assertThat(reviewed.currentEnforcementState()).isEqualTo("SUSPENDED");

        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(appeal(Status.REVOKE_RECOMMENDED, 5, ADMIN)));
        assertThatThrownBy(() -> service.review(APPEAL, new ReviewRequest(5L,
                ReviewOutcome.UPHOLD_RECOMMENDED, "RECONSIDERED", "Rewrite the result", null), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_NOT_REVIEWABLE"));
        verify(repository, times(1)).recommend(anyString(), anyLong(), anyString(), any(), anyString(),
                anyString(), nullable(ReplacementProposal.class), any());
    }

    @Test
    void reviewNoteIdempotencyRejectsChangedPayload() {
        adminAccess("admin.appeal.read", "admin.appeal.review");
        when(repository.noteByRetry(APPEAL, ADMIN, "note-key")).thenReturn(Optional.of(
                new AppealRepository.NoteRow("01ARZ3NDEKTSV4RRFFQ69G5AAJ", APPEAL,
                        "Original review note", ADMIN, "Admin reviewer", "corr", "note-key", NOW)));

        assertThatThrownBy(() -> service.addNote(APPEAL,
                new NoteRequest(4L, "Changed review note", "note-key"), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_NOTE_IDEMPOTENCY_CONFLICT"));
        verify(repository, never()).touchForNote(anyString(), anyLong(), anyString(), any());
        verify(repository, never()).insertNote(any());
    }

    @Test
    void upholdPreviewRequiresResolvePermissionAndBindsAuthoritativeVersions() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve");
        AppealRow recommended = appeal(Status.UPHOLD_RECOMMENDED, 5, OTHER);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insertPreview(any())).thenReturn(true);

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");

        assertThat(preview.outcome()).isEqualTo(FinalOutcome.UPHELD);
        assertThat(preview.predictedEffectiveEnforcementState()).isEqualTo("SUSPENDED");
        assertThat(preview.previewToken()).isEqualTo(APPEAL);
        verify(repository).insertPreview(argThat(value -> value.appealId().equals(APPEAL)
                && value.executorAdminId().equals(ADMIN) && value.requestFingerprint().length() == 64));
        verifyNoInteractions(adminUsers, adminBusinesses);
    }

    @Test
    void resolveOnlyAccessFinalizesAndBuildsTheResponseWithoutAReadPermissionRecheck() {
        adminAccess("admin.appeal.resolve");
        AppealRow recommended = appeal(Status.UPHOLD_RECOMMENDED, 5, OTHER);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insertPreview(any())).thenReturn(true);
        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));
        when(repository.finalizeResolution(anyString(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(
                resolvedAppeal(Status.UPHELD, 6, "uphold-key", null)));
        when(repository.userLabel(USER)).thenReturn("Affected user");

        clearInvocations(authorization);
        AdminAuthorizationService.AdminAccessSnapshot resolveOnly =
                new AdminAuthorizationService.AdminAccessSnapshot(
                        List.of("APPEAL_RESOLVER"), List.of("admin.appeal.resolve"));
        AdminAuthorizationService.AdminAccessSnapshot expiredElevation =
                new AdminAuthorizationService.AdminAccessSnapshot(List.of(), List.of());
        when(authorization.accessFor(actor)).thenReturn(resolveOnly, expiredElevation);

        Detail detail = service.resolveTransaction(APPEAL, new ResolutionRequest(5L, 1L, 2L,
                preview.previewToken(), "uphold-key", true), "corr");

        assertThat(detail.status()).isEqualTo(Status.UPHELD);
        verifyNoInteractions(adminUsers, adminBusinesses);
        verify(repository).finalizeResolution(eq(APPEAL), eq(5L), eq(Status.UPHOLD_RECOMMENDED),
                eq(FinalOutcome.UPHELD), eq(ADMIN), anyString(),
                eq("The original enforcement remains in effect."), eq("uphold-key"), anyString(),
                isNull(), eq(1L), eq(2L), eq(NOW));
        verify(authorization, times(1)).accessFor(actor);
    }

    @Test
    void revokeResolutionRequiresComposedTargetPermission() {
        when(authorization.accessFor(actor)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of("admin.appeal.read", "admin.appeal.resolve")));
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(appeal(Status.REVOKE_RECOMMENDED, 5)));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));

        assertThatThrownBy(() -> service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_RESOLUTION_PERMISSION_REQUIRED"));
        verifyNoInteractions(adminUsers);
    }

    @Test
    void revokeExecutesExistingServiceFinalizesAndReplaysSameIdempotencyKey() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.user.reinstate");
        AppealRow recommended = appeal(Status.REVOKE_RECOMMENDED, 5);
        EnforcementRow active = enforcement(USER, "ACTIVE");
        EnforcementRow revoked = new EnforcementRow(ACTION, TargetType.USER, USER, "Affected user",
                ActionType.SUSPEND, Set.of(Scope.USER_SELLING), "REVOKED", NOW.minusSeconds(60), null,
                2, NOW.minusSeconds(60), "POLICY", "Internal enforcement reason", null);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(active), Optional.of(active),
                Optional.of(revoked));
        when(repository.insertPreview(any())).thenReturn(true);
        when(adminUsers.revokePreview(eq(USER), eq(ACTION), any())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(ACTION, ActionType.SUSPEND, 2, true, List.of()), List.of(), List.of(),
                        List.of(), false, true));

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));
        when(adminUsers.revokeConfirmed(eq(USER), eq(ACTION), any())).thenReturn(
                enforcementResult(ACTION, ActionType.SUSPEND, 2, false, List.of()));
        when(repository.finalizeResolution(eq(APPEAL), eq(5L), eq(Status.REVOKE_RECOMMENDED),
                eq(FinalOutcome.REVOKED), eq(ADMIN), anyString(), anyString(), eq("resolution-key"),
                anyString(), isNull(), eq(2L), eq(2L), eq(NOW))).thenReturn(true);
        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        AppealRow finalRow = resolvedAppeal(Status.REVOKED, 6, "resolution-key", null);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(finalRow));
        when(repository.userLabel(USER)).thenReturn("Affected user");

        ResolutionRequest command = new ResolutionRequest(5L, 1L, 2L,
                preview.previewToken(), "resolution-key", true);
        Detail result = service.resolveTransaction(APPEAL, command, "corr");

        assertThat(result.status()).isEqualTo(Status.REVOKED);
        assertThat(result.resolutionSummary()).isEqualTo("The appealed enforcement was revoked.");
        verify(adminUsers).revokeConfirmed(eq(USER), eq(ACTION), argThat(value ->
                value.expectedEnforcementVersion() == 1 && value.idempotencyKey().length() == 64));
        verify(repository).finalizeResolution(eq(APPEAL), eq(5L), eq(Status.REVOKE_RECOMMENDED),
                eq(FinalOutcome.REVOKED), eq(ADMIN), anyString(), anyString(), eq("resolution-key"),
                requestHash.capture(), isNull(), eq(2L), eq(2L), eq(NOW));

        finalRow = withResolutionHash(finalRow, requestHash.getValue());
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(finalRow));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(revoked));
        Detail replay = service.resolveTransaction(APPEAL, command, "corr");
        assertThat(replay.status()).isEqualTo(Status.REVOKED);
        verify(adminUsers, times(1)).revokeConfirmed(eq(USER), eq(ACTION), any());
    }

    @Test
    void changedOverlapInvalidatesPreviewBeforeMutation() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.user.reinstate");
        AppealRow recommended = appeal(Status.REVOKE_RECOMMENDED, 5);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insertPreview(any())).thenReturn(true);
        when(adminUsers.revokePreview(eq(USER), eq(ACTION), any())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(ACTION, ActionType.SUSPEND, 2, true, List.of()), List.of(), List.of(),
                        List.of(), false, true));
        Result original = enforcementResult(ACTION, ActionType.SUSPEND, 1, false,
                List.of(new EffectiveRestriction(Scope.USER_SELLING, ActionType.SUSPEND, ACTION)));
        Result overlap = enforcementResult(REPLACEMENT, ActionType.RESTRICT, 0, false,
                List.of(new EffectiveRestriction(Scope.USER_SELLING, ActionType.RESTRICT, REPLACEMENT)));
        when(enforcements.lockState(TargetType.USER, USER, NOW)).thenReturn(
                new EnforcementService.StatePin(2L, "state-before", original.effectiveRestrictions()),
                new EnforcementService.StatePin(2L, "state-after", overlap.effectiveRestrictions()));

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));

        assertThatThrownBy(() -> service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, preview.previewToken(), "stale-preview-key", true), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_RESOLUTION_PREVIEW_INVALID"));
        verify(adminUsers, never()).revokeConfirmed(anyString(), anyString(), any());
        verify(repository, never()).finalizeResolution(anyString(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void finalStateRejectsChangedRequestAndDifferentIdempotencyKey() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.user.reinstate");
        AppealRow finalRow = withResolutionHash(
                resolvedAppeal(Status.REVOKED, 6, "used-key", null), "different-hash");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(finalRow));

        assertThatThrownBy(() -> service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, APPEAL, "used-key", true), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, APPEAL, "new-key", true), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_READ_ONLY"));
    }

    @Test
    void modifyRevokesOriginalThenCreatesAndLinksReplacement() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.user.reinstate",
                "admin.user.restrict");
        AppealRow recommended = appeal(Status.MODIFY_RECOMMENDED, 5);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insertPreview(any())).thenReturn(true);
        when(adminUsers.revokePreview(eq(USER), eq(ACTION), any())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(ACTION, ActionType.SUSPEND, 2, true, List.of()), List.of(), List.of(),
                        List.of(), false, true));
        EffectiveRestriction replacementRestriction = new EffectiveRestriction(
                Scope.USER_SELLING, ActionType.RESTRICT, REPLACEMENT);
        when(adminUsers.createAppealReplacementPreview(eq(USER), eq(ACTION), any(), isNull())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(null, ActionType.RESTRICT, 0, true, List.of(replacementRestriction)),
                        List.of(), List.of(replacementRestriction), List.of(), false, true));

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));
        when(adminUsers.revokeConfirmed(eq(USER), eq(ACTION), any())).thenReturn(
                enforcementResult(ACTION, ActionType.SUSPEND, 2, false, List.of()));
        when(adminUsers.createCaseConfirmed(eq(USER), any(), isNull())).thenReturn(
                enforcementResult(REPLACEMENT, ActionType.RESTRICT, 0, false,
                        List.of(replacementRestriction)));
        when(repository.finalizeResolution(anyString(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        AppealRow finalRow = resolvedAppeal(Status.MODIFIED, 6, "modify-key", REPLACEMENT);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(finalRow));
        when(repository.localEnforcement(REPLACEMENT)).thenReturn(Optional.of(new EnforcementRow(
                REPLACEMENT, TargetType.USER, USER, "Affected user", ActionType.RESTRICT,
                Set.of(Scope.USER_SELLING), "ACTIVE", NOW, NOW.plusSeconds(3600), 0, NOW,
                "APPEAL_ADJUSTED", "Adjusted enforcement", null)));
        when(repository.userLabel(USER)).thenReturn("Affected user");

        Detail result = service.resolveTransaction(APPEAL, new ResolutionRequest(5L, 1L, 2L,
                preview.previewToken(), "modify-key", true), "corr");

        assertThat(result.status()).isEqualTo(Status.MODIFIED);
        assertThat(result.replacementEnforcementSummary().enforcementActionId()).isEqualTo(REPLACEMENT);
        verify(adminUsers).revokeConfirmed(eq(USER), eq(ACTION), any());
        verify(adminUsers).createCaseConfirmed(eq(USER), argThat(value ->
                value.actionType() == ActionType.RESTRICT && value.expectedUserVersion() == 2), isNull());
        verify(repository).finalizeResolution(eq(APPEAL), eq(5L), eq(Status.MODIFY_RECOMMENDED),
                eq(FinalOutcome.MODIFIED), eq(ADMIN), anyString(), anyString(), eq("modify-key"),
                anyString(), eq(REPLACEMENT), eq(2L), eq(2L), eq(NOW));
    }

    @Test
    void expiredLocalReplacementIsRejectedBeforeRevokeAndFinalization() {
        adminAccess("admin.appeal.resolve", "admin.user.reinstate", "admin.user.restrict");
        Instant replacementExpiry = NOW.plusSeconds(60);
        AppealRow recommended = withReplacement(appeal(Status.MODIFY_RECOMMENDED, 5),
                ActionType.RESTRICT, replacementExpiry, "APPEAL_ADJUSTED");
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(enforcement(USER, "ACTIVE")));
        when(repository.insertPreview(any())).thenReturn(true);
        when(adminUsers.revokePreview(eq(USER), eq(ACTION), any())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(ACTION, ActionType.SUSPEND, 2, true, List.of()),
                        List.of(), List.of(), List.of(), false, true));
        EffectiveRestriction replacementRestriction = new EffectiveRestriction(
                Scope.USER_SELLING, ActionType.RESTRICT, REPLACEMENT);
        when(adminUsers.createAppealReplacementPreview(eq(USER), eq(ACTION), any(), isNull())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        enforcementResult(null, ActionType.RESTRICT, 0, true, List.of(replacementRestriction)),
                        List.of(), List.of(replacementRestriction), List.of(), false, true));

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        assertThat(preview.expiresAt()).isEqualTo(replacementExpiry);
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));
        service = serviceAt(replacementExpiry.plusSeconds(1));

        assertThatThrownBy(() -> service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, preview.previewToken(),
                        "expired-local-modify", true), "corr"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code()).isEqualTo("APPEAL_RESOLUTION_PREVIEW_EXPIRED"));

        verify(adminUsers, never()).revokeConfirmed(anyString(), anyString(), any());
        verify(adminUsers, never()).createCaseConfirmed(anyString(), any(), any());
        verify(resolutionCommands, never()).reserve(anyString(), anyString(), anyString(), anyString(), any());
        verify(repository, never()).finalizeResolution(anyString(), anyLong(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void listingModifyReusesPreviewTimestampAndStoredReplacementReasonAtExecution() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.listing.reinstate",
                "admin.listing.suspend");
        AppealRow recommended = listingAppeal(Status.MODIFY_RECOMMENDED, 5, null);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(USER, null)));
        when(repository.replacementScopes(APPEAL)).thenReturn(Set.of(Scope.LISTING_PUBLIC_VISIBILITY));
        when(repository.insertPreview(any())).thenReturn(true);
        when(listings.previewResolution(eq(APPEAL), any())).thenReturn(listingResolution(
                "MODIFIED", 1, null, true, false, "owner-token"));

        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "corr");
        ArgumentCaptor<AppealRepository.ResolutionPreviewRow> previewRow =
                ArgumentCaptor.forClass(AppealRepository.ResolutionPreviewRow.class);
        verify(repository).insertPreview(previewRow.capture());
        when(repository.preview(preview.previewToken(), APPEAL, ADMIN))
                .thenReturn(Optional.of(previewRow.getValue()));
        when(listings.executeResolution(eq(APPEAL), any())).thenReturn(listingResolution(
                "MODIFIED", 2, REPLACEMENT, false, false, null));
        when(repository.finalizeResolution(anyString(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(
                listingAppeal(Status.MODIFIED, 6, REPLACEMENT)));
        when(listings.byActionId(REPLACEMENT)).thenReturn(Optional.of(listing(USER, null)));

        service.resolveTransaction(APPEAL, new ResolutionRequest(5L, 1L, 2L,
                preview.previewToken(), "listing-modify-key", true), "corr");

        ArgumentCaptor<ListingAppealContextClient.ListingResolutionRequest> ownerRequests =
                ArgumentCaptor.forClass(ListingAppealContextClient.ListingResolutionRequest.class);
        verify(listings).previewResolution(eq(APPEAL), ownerRequests.capture());
        verify(listings).executeResolution(eq(APPEAL), ownerRequests.capture());
        var previewOwnerRequest = ownerRequests.getAllValues().get(0);
        var executeOwnerRequest = ownerRequests.getAllValues().get(1);
        assertThat(previewOwnerRequest.replacement().effectiveAt()).isEqualTo(NOW);
        assertThat(executeOwnerRequest.replacement().effectiveAt())
                .isEqualTo(previewOwnerRequest.replacement().effectiveAt());
        assertThat(executeOwnerRequest.replacement().reasonCode()).isEqualTo("APPEAL_ADJUSTED");
        assertThat(executeOwnerRequest.replacement().reason()).isEqualTo("Adjusted enforcement");
        assertThat(executeOwnerRequest.recoveryOnly()).isFalse();
    }

    @Test
    void productCompletionIsRecoveredAfterAuthRollbackEvenWhenOriginalIsNowRevoked() {
        adminAccess("admin.appeal.read", "admin.appeal.resolve", "admin.listing.reinstate");
        AppealRow recommended = listingAppeal(Status.REVOKE_RECOMMENDED, 5, null);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(USER, null, "REVOKED", 2)));
        when(resolutionCommands.find("recover-owner-key")).thenReturn(Optional.of(
                new AppealResolutionCommandService.Reservation("recover-owner-key", APPEAL,
                        "a".repeat(64), ADMIN, NOW.minusSeconds(30), false)));
        when(repository.preview(APPEAL, APPEAL, ADMIN)).thenReturn(Optional.of(
                new AppealRepository.ResolutionPreviewRow(APPEAL, APPEAL, ADMIN,
                        "b".repeat(64), "owner-token", NOW.minusSeconds(1), NOW.minusSeconds(301))));
        when(listings.executeResolution(eq(APPEAL), any())).thenReturn(listingResolution(
                "REVOKED", 2, null, false, true, null));
        when(repository.finalizeResolution(anyString(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(
                listingAppeal(Status.REVOKED, 6, null)));

        service.resolveTransaction(APPEAL, new ResolutionRequest(5L, 1L, 2L,
                APPEAL, "recover-owner-key", true), "corr");

        ArgumentCaptor<ListingAppealContextClient.ListingResolutionRequest> request =
                ArgumentCaptor.forClass(ListingAppealContextClient.ListingResolutionRequest.class);
        verify(listings).executeResolution(eq(APPEAL), request.capture());
        assertThat(request.getValue().recoveryOnly()).isTrue();
        verify(resolutionCommands, never()).reserve(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void expiredListingReplacementStillAllowsCompletedOwnerCommandRecoveryReplay() {
        adminAccess("admin.appeal.resolve", "admin.listing.reinstate", "admin.listing.suspend");
        AppealRow recommended = listingAppeal(Status.MODIFY_RECOMMENDED, 5, null);
        when(repository.byId(APPEAL, true)).thenReturn(Optional.of(recommended));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.empty());
        when(listings.byActionId(ACTION)).thenReturn(Optional.of(listing(USER, null, "REVOKED", 2)));
        when(resolutionCommands.find("recover-expired-modify")).thenReturn(Optional.of(
                new AppealResolutionCommandService.Reservation("recover-expired-modify", APPEAL,
                        "a".repeat(64), ADMIN, NOW.minusSeconds(30), false)));
        when(repository.preview(APPEAL, APPEAL, ADMIN)).thenReturn(Optional.of(
                new AppealRepository.ResolutionPreviewRow(APPEAL, APPEAL, ADMIN,
                        "b".repeat(64), "owner-token", NOW.minusSeconds(1), NOW.minusSeconds(301))));
        when(listings.executeResolution(eq(APPEAL), any())).thenReturn(listingResolution(
                "MODIFIED", 2, REPLACEMENT, false, true, null));
        when(repository.finalizeResolution(anyString(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(repository.byId(APPEAL, false)).thenReturn(Optional.of(
                listingAppeal(Status.MODIFIED, 6, REPLACEMENT)));
        when(listings.byActionId(REPLACEMENT)).thenReturn(Optional.of(listing(USER, null)));
        service = serviceAt(NOW.plusSeconds(7200));

        service.resolveTransaction(APPEAL, new ResolutionRequest(5L, 1L, 2L,
                APPEAL, "recover-expired-modify", true), "corr");

        ArgumentCaptor<ListingAppealContextClient.ListingResolutionRequest> request =
                ArgumentCaptor.forClass(ListingAppealContextClient.ListingResolutionRequest.class);
        verify(listings).executeResolution(eq(APPEAL), request.capture());
        assertThat(request.getValue().recoveryOnly()).isTrue();
        verify(resolutionCommands, never()).reserve(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void affectedActorSeesOnlySafeFinalOutcomeAndCurrentOverlappingState() {
        AppealRow resolved = resolvedAppeal(Status.REVOKED, 6, "safe-mine-key", null);
        when(repository.mine(USER)).thenReturn(List.of(resolved));
        when(repository.localEnforcement(ACTION)).thenReturn(Optional.of(new EnforcementRow(
                ACTION, TargetType.USER, USER, "Affected user", ActionType.SUSPEND,
                Set.of(Scope.USER_SELLING), "REVOKED", NOW.minusSeconds(60), null, 2,
                NOW.minusSeconds(60), "POLICY", "Internal enforcement reason", null)));
        when(enforcements.evaluate(TargetType.USER, USER, NOW)).thenReturn(List.of(
                new EffectiveRestriction(Scope.USER_SELLING, ActionType.RESTRICT, REPLACEMENT)));

        MyAppeal mine = service.mine().getFirst();

        assertThat(mine.status()).isEqualTo(Status.REVOKED);
        assertThat(mine.resolvedAt()).isEqualTo(NOW);
        assertThat(mine.safeOutcomeSummary()).isEqualTo("The appealed enforcement was revoked.");
        assertThat(mine.currentEffectiveEnforcementState()).isEqualTo("RESTRICTED");
        assertThat(mine.toString()).doesNotContain("Resolution admin", "WRONG_DECISION",
                "Internal enforcement reason");
    }

    private EnforcementRow enforcement(String target, String lifecycle) {
        return new EnforcementRow(ACTION, TargetType.USER, target, "Affected user", ActionType.SUSPEND,
                Set.of(Scope.USER_SELLING), lifecycle, NOW.minusSeconds(60), null, 1, NOW.minusSeconds(60),
                "POLICY", "Internal enforcement reason", null);
    }

    private EnforcementRow enforcement(TargetType type, String target, String label, Set<Scope> scopes) {
        return new EnforcementRow(ACTION, type, target, label, ActionType.SUSPEND, scopes, "ACTIVE",
                NOW.minusSeconds(60), null, 1, NOW.minusSeconds(60), "POLICY",
                "Internal enforcement reason", null);
    }

    private ListingEnforcementContext listing(String individualOwner, String businessOwner) {
        return listing(individualOwner, businessOwner, "ACTIVE", 1);
    }

    private ListingEnforcementContext listing(String individualOwner, String businessOwner,
                                               String lifecycle, long enforcementVersion) {
        return new ListingEnforcementContext(ACTION, LISTING, "Appealable listing",
                businessOwner == null ? "INDIVIDUAL" : "BUSINESS", individualOwner, businessOwner,
                "ACTIVE", 2, "SUSPEND", List.of("LISTING_PUBLIC_VISIBILITY"), lifecycle,
                NOW.minusSeconds(60), null, enforcementVersion, NOW.minusSeconds(60), "POLICY",
                "Internal listing enforcement reason", null, "SUSPENDED");
    }

    private ListingAppealContextClient.ListingResolutionResult listingResolution(
            String outcome, long originalVersion, String replacementId, boolean dryRun,
            boolean replayed, String confirmationToken) {
        return new ListingAppealContextClient.ListingResolutionResult(APPEAL, outcome, ACTION,
                originalVersion, replacementId, replacementId == null ? null : 0L, List.of(), 2L,
                dryRun, "corr", replayed, replacementId == null ? "SUSPENDED" : "RESTRICTED",
                List.of(), List.of(), confirmationToken);
    }

    private AppealRow listingAppeal(Status status, long version, String replacementId) {
        AppealRow base = appeal(status, version, ADMIN);
        boolean finalState = status == Status.MODIFIED;
        return new AppealRow(base.id(), base.enforcementActionId(), TargetType.LISTING, LISTING,
                "Appealable listing", AppellantType.LISTING_OWNER, base.appellantUserId(), status,
                base.reasonCode(), base.explanation(), base.evidenceReferences(), base.submittedAt(),
                base.assignedAdminId(), base.reviewStartedAt(), base.reviewedAt(), base.reviewOutcome(),
                base.reviewReasonCode(), base.reviewReason(), base.replacementActionType(),
                base.replacementExpiresAt(), base.replacementReasonCode(), base.replacementReason(),
                base.replacementTargetVersion(), base.originalCaseId(), base.originalEnforcementVersion(),
                base.correlationId(), version, base.createdAt(), base.updatedAt(), finalState ? NOW : null,
                finalState ? ADMIN : null, finalState ? "Resolution admin" : null,
                finalState ? "The original enforcement was replaced with an adjusted action." : null,
                finalState ? "listing-modify-key" : null, null, replacementId,
                finalState ? 2L : null, finalState ? 2L : null);
    }

    private AppealRow appeal(Status status, long version) {
        return appeal(status, version, ADMIN);
    }

    private AppealRow appeal(Status status, long version, String assignedAdminId) {
        ReviewOutcome reviewOutcome = switch (status) {
            case UPHOLD_RECOMMENDED, UPHELD -> ReviewOutcome.UPHOLD_RECOMMENDED;
            case MODIFY_RECOMMENDED, MODIFIED -> ReviewOutcome.MODIFY_RECOMMENDED;
            case REVOKE_RECOMMENDED, REVOKED -> ReviewOutcome.REVOKE_RECOMMENDED;
            default -> null;
        };
        boolean modified = reviewOutcome == ReviewOutcome.MODIFY_RECOMMENDED;
        return new AppealRow(APPEAL, ACTION, TargetType.USER, USER, "Affected user", AppellantType.USER, USER,
                status, ReasonCode.ACTION_TOO_SEVERE, "Please review", List.of(), NOW.minusSeconds(30), assignedAdminId,
                NOW.minusSeconds(20), status == Status.UNDER_REVIEW ? null : NOW, reviewOutcome,
                reviewOutcome == null ? null : "WRONG_DECISION",
                reviewOutcome == null ? null : "The recommendation is ready for resolution.",
                modified ? ActionType.RESTRICT : null, modified ? NOW.plusSeconds(3600) : null,
                modified ? "APPEAL_ADJUSTED" : null, modified ? "Adjusted enforcement" : null,
                modified ? 2L : null, null, 1, "corr", version, NOW.minusSeconds(30), NOW,
                null, null, null, null, null, null, null, null, null);
    }

    private AppealRow withTarget(AppealRow row, TargetType targetType, String targetId, String safeLabel) {
        return new AppealRow(row.id(), row.enforcementActionId(), targetType, targetId, safeLabel,
                row.appellantType(), row.appellantUserId(), row.status(), row.reasonCode(), row.explanation(),
                row.evidenceReferences(), row.submittedAt(), row.assignedAdminId(), row.reviewStartedAt(),
                row.reviewedAt(), row.reviewOutcome(), row.reviewReasonCode(), row.reviewReason(),
                row.replacementActionType(), row.replacementExpiresAt(), row.replacementReasonCode(),
                row.replacementReason(), row.replacementTargetVersion(), row.originalCaseId(),
                row.originalEnforcementVersion(), row.correlationId(), row.version(), row.createdAt(),
                row.updatedAt(), row.resolvedAt(), row.resolvedByAdminId(), row.resolvedByAdminName(),
                row.resolutionSummary(), row.resolutionIdempotencyKey(), row.resolutionRequestHash(),
                row.replacementEnforcementActionId(), row.resolutionOriginalEnforcementVersion(),
                row.resolutionTargetVersion());
    }

    private AppealRow withReplacement(AppealRow row, ActionType actionType, Instant expiresAt,
                                      String reasonCode) {
        return new AppealRow(row.id(), row.enforcementActionId(), row.targetType(), row.targetId(),
                row.safeTargetLabel(), row.appellantType(), row.appellantUserId(), row.status(), row.reasonCode(),
                row.explanation(), row.evidenceReferences(), row.submittedAt(), row.assignedAdminId(),
                row.reviewStartedAt(), row.reviewedAt(), row.reviewOutcome(), row.reviewReasonCode(),
                row.reviewReason(), actionType, expiresAt, reasonCode, "Adjusted enforcement", 2L,
                row.originalCaseId(), row.originalEnforcementVersion(), row.correlationId(), row.version(),
                row.createdAt(), row.updatedAt(), row.resolvedAt(), row.resolvedByAdminId(),
                row.resolvedByAdminName(), row.resolutionSummary(), row.resolutionIdempotencyKey(),
                row.resolutionRequestHash(), row.replacementEnforcementActionId(),
                row.resolutionOriginalEnforcementVersion(), row.resolutionTargetVersion());
    }

    private AppealRow resolvedAppeal(Status status, long version, String idempotencyKey,
                                     String replacementId) {
        AppealRow base = appeal(status, version, ADMIN);
        return new AppealRow(base.id(), base.enforcementActionId(), base.targetType(), base.targetId(),
                base.safeTargetLabel(), base.appellantType(), base.appellantUserId(), base.status(),
                base.reasonCode(), base.explanation(), base.evidenceReferences(), base.submittedAt(),
                base.assignedAdminId(), base.reviewStartedAt(), base.reviewedAt(), base.reviewOutcome(),
                base.reviewReasonCode(), base.reviewReason(), base.replacementActionType(),
                base.replacementExpiresAt(), base.replacementReasonCode(), base.replacementReason(),
                base.replacementTargetVersion(), base.originalCaseId(), base.originalEnforcementVersion(),
                base.correlationId(), base.version(), base.createdAt(), base.updatedAt(), NOW, ADMIN,
                "Resolution admin", switch (status) {
                    case UPHELD -> "The original enforcement remains in effect.";
                    case REVOKED -> "The appealed enforcement was revoked.";
                    default -> "The original enforcement was replaced with an adjusted action.";
                }, idempotencyKey,
                null, replacementId, 2L, 2L);
    }

    private AppealRow withResolutionHash(AppealRow row, String requestHash) {
        return new AppealRow(row.id(), row.enforcementActionId(), row.targetType(), row.targetId(),
                row.safeTargetLabel(), row.appellantType(), row.appellantUserId(), row.status(), row.reasonCode(),
                row.explanation(), row.evidenceReferences(), row.submittedAt(), row.assignedAdminId(),
                row.reviewStartedAt(), row.reviewedAt(), row.reviewOutcome(), row.reviewReasonCode(),
                row.reviewReason(), row.replacementActionType(), row.replacementExpiresAt(),
                row.replacementReasonCode(), row.replacementReason(), row.replacementTargetVersion(),
                row.originalCaseId(), row.originalEnforcementVersion(), row.correlationId(), row.version(),
                row.createdAt(), row.updatedAt(), row.resolvedAt(), row.resolvedByAdminId(),
                row.resolvedByAdminName(), row.resolutionSummary(), row.resolutionIdempotencyKey(), requestHash,
                row.replacementEnforcementActionId(), row.resolutionOriginalEnforcementVersion(),
                row.resolutionTargetVersion());
    }

    private com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result enforcementResult(
            String actionId, ActionType actionType, long version, boolean dryRun,
            List<EffectiveRestriction> restrictions) {
        return new com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result(actionId, TargetType.USER,
                USER, actionType, Set.of(Scope.USER_SELLING),
                dryRun && actionId == null
                        ? com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState.ACTIVE
                        : actionId != null && version > 1
                                ? com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState.REVOKED
                                : com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState.ACTIVE,
                NOW, actionType == ActionType.RESTRICT ? NOW.plusSeconds(3600) : null, version, NOW,
                version > 1 ? NOW : null, "APPEAL_RESOLUTION", "Appeal resolution", restrictions,
                "corr", dryRun);
    }

    private void adminAccess(String... permissions) {
        when(actor.getId()).thenReturn(ADMIN);
        when(authorization.accessFor(actor)).thenReturn(new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("TRUST_AND_SAFETY_ADMIN"), List.of(permissions)));
    }

    private AppealService serviceAt(Instant instant) {
        return new AppealService(repository, listings, authService, authorization,
                adminUsers, adminBusinesses, enforcements, resolutionCommands, ulids,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(instant, ZoneOffset.UTC));
    }
}
