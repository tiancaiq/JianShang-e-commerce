import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AppealDetail, AppealResolutionPreview } from '../../core/models/appeal.model';
import { AppealService } from '../../core/services/appeal.service';
import { AdminAppealDetailComponent } from './admin-appeal-detail.component';

describe('AdminAppealDetailComponent', () => {
  let fixture: ComponentFixture<AdminAppealDetailComponent>;
  let component: AdminAppealDetailComponent;
  let service: jasmine.SpyObj<AppealService>;
  let detail: AppealDetail;

  beforeEach(async () => {
    detail = appealDetail();
    service = jasmine.createSpyObj<AppealService>('AppealService',
      ['detail', 'claim', 'release', 'start', 'note', 'review', 'previewResolution', 'resolve']);
    service.detail.and.returnValue(of(detail));
    service.claim.and.returnValue(of(detail));
    service.release.and.returnValue(of(detail));
    service.start.and.returnValue(of(detail));
    service.note.and.returnValue(of(detail));
    service.review.and.returnValue(of({ ...detail, status: 'REVOKE_RECOMMENDED' }));
    service.previewResolution.and.returnValue(of(resolutionPreview()));
    service.resolve.and.returnValue(of({ ...detail, status: 'REVOKED', finalOutcome: 'REVOKED',
      resolvedAt: '2026-08-16T01:00:00Z', resolutionSummary: 'The original enforcement was revoked.' }));
    await TestBed.configureTestingModule({
      imports: [AdminAppealDetailComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ appealId: detail.appealId }) } } },
        { provide: AppealService, useValue: service },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminAppealDetailComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders original context and records a recommendation without an enforcement command', () => {
    expect(fixture.nativeElement.textContent).toContain('Original enforcement');
    expect(fixture.nativeElement.textContent).toContain('Original investigation');
    expect(fixture.nativeElement.textContent).toContain('This records a recommendation only');

    component.outcome = 'REVOKE_RECOMMENDED';
    component.reviewReasonCode = 'WRONG_DECISION';
    component.reviewReason = 'The original action should be revoked after a separate resolution.';
    component.recommend();

    expect(service.review).toHaveBeenCalledWith(detail.appealId, 4, 'REVOKE_RECOMMENDED',
      'WRONG_DECISION', 'The original action should be revoked after a separate resolution.', null);
  });

  it('reloads and reports a conflict instead of silently retrying', () => {
    service.review.and.returnValue(throwError(() => ({ status: 409 })));
    component.reviewReason = 'A sufficiently detailed review conclusion.';

    component.recommend();
    fixture.detectChanges();

    expect(component.actionError()).toContain('latest record has been reloaded');
    expect(service.detail).toHaveBeenCalledTimes(2);
    expect(service.review).toHaveBeenCalledTimes(1);
  });

  it('honors another-admin read-only capabilities from the backend', () => {
    component.detail.set({ ...detail, availableAdminCapabilities: {
      ...detail.availableAdminCapabilities, canRelease: false, canStartReview: false, canAddNote: false,
      canReview: false, canRecommendUphold: false, canRecommendModify: false, canRecommendRevoke: false,
      isAssignedToMe: false, isAssignedToOther: true, isReadOnly: true,
      readOnlyReason: 'This appeal is assigned to another reviewer.',
    } });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('This appeal is assigned to another reviewer.');
    expect(fixture.nativeElement.querySelector('.recommendation form')).toBeNull();
  });

  it('previews the immutable recommendation and requires explicit confirmation before resolution', async () => {
    component.detail.set({ ...detail, status: 'REVOKE_RECOMMENDED', reviewOutcome: 'REVOKE_RECOMMENDED',
      reviewedAt: '2026-08-16T00:30:00Z', availableAdminCapabilities: {
        ...detail.availableAdminCapabilities, canReview: false, canRecommendUphold: false,
        canRecommendModify: false, canRecommendRevoke: false, canResolveRevoke: true,
        isFinalRecommendation: true, isReadOnly: true, readOnlyReason: 'Recommendation recorded.',
      } });
    fixture.detectChanges();

    component.previewResolution();
    await fixture.whenStable();

    expect(service.previewResolution).toHaveBeenCalledWith(detail.appealId, {
      expectedAppealVersion: 4, expectedEnforcementVersion: 1, expectedTargetVersion: 2,
    });
    expect(fixture.nativeElement.textContent).toContain('Dry-run preview');
    expect(fixture.nativeElement.textContent).toContain('User buying · Restrict · 01WEAKERENFORCEMENT');
    const confirm = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.resolution-buttons button:last-child')!;
    expect(confirm.disabled).toBeTrue();
    expect(service.resolve).not.toHaveBeenCalled();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.explicit-confirmation input')!.click();
    await fixture.whenStable();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.resolution-buttons button:last-child')!.click();
    await fixture.whenStable();

    expect(service.resolve).toHaveBeenCalledWith(detail.appealId, jasmine.objectContaining({
      expectedAppealVersion: 4, expectedEnforcementVersion: 1, expectedTargetVersion: 2,
      previewToken: 'preview-token', idempotencyKey: jasmine.any(String), confirmed: true,
    }));
    expect(component.detail()?.finalOutcome).toBe('REVOKED');
  });

  it('discards a stale preview and reloads without silently retrying execution', () => {
    component.detail.set({ ...detail, status: 'REVOKE_RECOMMENDED', reviewOutcome: 'REVOKE_RECOMMENDED',
      availableAdminCapabilities: { ...detail.availableAdminCapabilities, canResolveRevoke: true } });
    component.previewResolution();
    component.resolutionConfirmed = true;
    service.resolve.and.returnValue(throwError(() => ({ status: 409, error: { error: { code: 'ENFORCEMENT_VERSION_CONFLICT' } } })));

    component.confirmResolution();
    fixture.detectChanges();

    expect(component.resolutionPreview()).toBeNull();
    expect(component.resolutionError()).toContain('old preview was discarded');
    expect(service.resolve).toHaveBeenCalledTimes(1);
    expect(service.detail).toHaveBeenCalledTimes(2);
  });

  it('explains a recommendation that the current executor is not authorized to resolve', () => {
    component.detail.set({ ...detail, status: 'REVOKE_RECOMMENDED', reviewOutcome: 'REVOKE_RECOMMENDED',
      availableAdminCapabilities: { ...detail.availableAdminCapabilities, canReview: false,
        canResolveRevoke: false, isFinalRecommendation: true, isReadOnly: true,
        readOnlyReason: 'Recommendation recorded.' } });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('combined appeal and target-specific enforcement permissions');
    expect(fixture.nativeElement.querySelector('.resolution-action')).toBeNull();
    expect(service.previewResolution).not.toHaveBeenCalled();
  });

  it('renders a final appeal as immutable with its safe outcome and replacement record', () => {
    component.detail.set({ ...detail, status: 'MODIFIED', finalOutcome: 'MODIFIED',
      resolvedAt: '2026-08-16T01:00:00Z', resolutionSummary: 'The original enforcement was replaced with a narrower action.',
      replacementEnforcementSummary: { ...detail.enforcementSummary, enforcementActionId: '01REPLACEMENT',
        actionType: 'RESTRICT', lifecycleState: 'ACTIVE' },
      availableAdminCapabilities: { ...detail.availableAdminCapabilities, canReview: false,
        canResolveUphold: false, canResolveModify: false, canResolveRevoke: false,
        isFinalResolution: true, isReadOnly: true, readOnlyReason: 'Final appeals are read-only.' },
    });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('The original enforcement was replaced with a narrower action.');
    expect(text).toContain('01REPLACEMENT');
    expect(text).toContain('This final outcome is immutable.');
    expect(fixture.nativeElement.querySelector('.resolution-action')).toBeNull();
  });
});

function appealDetail(): AppealDetail {
  const capabilities = {
    canRead: true, canClaim: false, canRelease: true, canStartReview: false, canAddNote: true,
    canReview: true, canRecommendUphold: true, canRecommendModify: true, canRecommendRevoke: true,
    canResolveUphold: false, canResolveModify: false, canResolveRevoke: false,
    isAssignedToMe: true, isAssignedToOther: false, isFinalRecommendation: false,
    isFinalResolution: false, isReadOnly: false, readOnlyReason: null,
  };
  return {
    appealId: '01ARZ3NDEKTSV4RRFFQ69G5AAD', enforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5AAC',
    targetType: 'USER', targetId: '01ARZ3NDEKTSV4RRFFQ69G5AAA', safeTargetLabel: 'Marketplace account',
    status: 'UNDER_REVIEW', submittedAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:10:00Z', version: 4,
    appellantSummary: { appellantType: 'USER', userId: '01ARZ3NDEKTSV4RRFFQ69G5AAA', safeDisplayName: 'Affected user' },
    reasonCode: 'DECISION_INCORRECT', explanation: 'The action was based on incomplete context.',
    safeEvidenceReferences: [], assignedAdmin: { userId: '01ARZ3NDEKTSV4RRFFQ69G5AAE', safeDisplayName: 'Reviewer' },
    reviewStartedAt: '2026-08-16T00:05:00Z', reviewedAt: null, reviewOutcome: null,
    reviewReasonCode: null, reviewReason: null, replacementProposal: null,
    resolvedAt: null, finalOutcome: null, resolutionSummary: null, replacementEnforcementSummary: null,
    enforcementSummary: { enforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5AAC', targetType: 'USER',
      targetId: '01ARZ3NDEKTSV4RRFFQ69G5AAA', actionType: 'SUSPEND', scopes: ['USER_SELLING'],
      lifecycleState: 'ACTIVE', effectiveAt: '2026-08-15T00:00:00Z', expiresAt: null, version: 1,
      createdAt: '2026-08-15T00:00:00Z', reasonCode: 'POLICY', reason: 'Reviewed policy violation.',
      caseId: '01ARZ3NDEKTSV4RRFFQ69G5AAH' },
    currentEnforcementState: 'ACTIVE',
    originalCaseSummary: { caseId: '01ARZ3NDEKTSV4RRFFQ69G5AAH', title: 'Original investigation',
      status: 'CLOSED_ACTIONED', severity: 'HIGH', closedAt: '2026-08-15T00:00:00Z', conclusionCode: null },
    linkedReports: [], originalTargetSnapshotContext: { capturedAt: '2026-08-15T00:00:00Z', status: 'ACTIVE' },
    currentTargetSummary: { capturedAt: '2026-08-16T00:00:00Z', status: 'ACTIVE', version: 2 },
    enforcementTimeline: [], caseTimeline: [], appealTimeline: [{ eventId: '01ARZ3NDEKTSV4RRFFQ69G5AAI',
      occurredAt: '2026-08-16T00:00:00Z', eventType: 'APPEAL_SUBMITTED', actorType: 'MARKETPLACE_USER',
      actorId: null, actorDisplayName: 'Affected user', source: 'MARKETPLACE', previousState: null,
      newState: 'SUBMITTED', reasonCode: null, reason: null, correlationId: null, requestId: null, safeMetadata: {} }],
    internalReviewNotes: [], createdAt: '2026-08-16T00:00:00Z', availableAdminCapabilities: capabilities,
  };
}

function resolutionPreview(): AppealResolutionPreview {
  return {
    outcome: 'REVOKED', appealVersion: 4, enforcementVersion: 1, targetVersion: 2,
    originalEnforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5AAC', replacementProposal: null,
    predictedEffectiveEnforcementState: 'RESTRICTED', effectiveRestrictionsAfter: [{
      scope: 'USER_BUYING', actionType: 'RESTRICT', enforcementActionId: '01WEAKERENFORCEMENT',
    }], impactSummary: ['The original enforcement will be revoked.'],
    warnings: ['A weaker overlapping action may remain effective.'], previewToken: 'preview-token',
    expiresAt: '2026-08-16T00:20:00Z',
  };
}
