import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import {
  EmbeddingRequestBackfillStatus,
  VectorRebuildStatus,
} from '../../core/models/admin-search-maintenance.model';
import { AdminSearchMaintenanceService } from '../../core/services/admin-search-maintenance.service';
import { ADMIN_SEARCH_MAINTENANCE_ENABLED } from './admin-search-maintenance.capability';
import { AdminSearchMaintenanceComponent } from './admin-search-maintenance.component';

describe('AdminSearchMaintenanceComponent', () => {
  let fixture: ComponentFixture<AdminSearchMaintenanceComponent>;
  let service: jasmine.SpyObj<AdminSearchMaintenanceService>;

  const status: EmbeddingRequestBackfillStatus = {
    schemaVersion: 'MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1',
    runId: '01K00000000000000000000001',
    commandOutcome: 'SUCCEEDED',
    state: 'PENDING',
    pageCount: 1,
    processedCount: 50,
    createdCount: 45,
    alreadyPresentCount: 3,
    skippedCount: 2,
    failedCount: 0,
    errorCode: null,
    startedAt: '2026-07-24T10:00:00Z',
    updatedAt: '2026-07-24T10:00:01Z',
    completedAt: null,
  };
  const vectorStatus: VectorRebuildStatus = {
    schemaVersion: 'MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2',
    runId: '01K00000000000000000000002',
    commandOutcome: 'SUCCEEDED',
    state: 'CATCHING_UP',
    schemaIdentity: 'marketplace-public-listing-v2-vector',
    candidateRole: 'INACTIVE_V2_CANDIDATE',
    previousGenerationRole: 'ACTIVE_PREVIOUS',
    authoritativeDocumentCount: 305,
    vectorDocumentCount: 165,
    catchUpWorkCount: 0,
    deferredReceiptCount: 0,
    canCatchUp: true,
    canPromote: false,
    canRecover: false,
    errorCode: null,
    startedAt: '2026-07-24T10:00:00Z',
    updatedAt: '2026-07-24T10:00:01Z',
    promotedAt: null,
  };

  beforeEach(async () => {
    service = jasmine.createSpyObj<AdminSearchMaintenanceService>(
      'AdminSearchMaintenanceService',
      [
        'rebuildLegacyIndex',
        'startEmbeddingRequestBackfill',
        'getEmbeddingRequestBackfill',
        'resumeEmbeddingRequestBackfill',
        'startVectorRebuild',
        'getVectorRebuild',
        'catchUpVectorRebuild',
        'promoteVectorRebuild',
        'recoverVectorRebuild',
      ],
    );

    await TestBed.configureTestingModule({
      imports: [AdminSearchMaintenanceComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminSearchMaintenanceService, useValue: service },
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: true },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminSearchMaintenanceComponent);
    fixture.detectChanges();
  });

  it('is initially network silent and explains the derived-data operations', () => {
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Search maintenance');
    expect(root.textContent).toContain('do not edit listings or Product database facts');
    expect(root.textContent).toContain('V2 activation');
    expect(service.rebuildLegacyIndex).not.toHaveBeenCalled();
    expect(service.startEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.getEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.resumeEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.startVectorRebuild).not.toHaveBeenCalled();
    expect(service.getVectorRebuild).not.toHaveBeenCalled();
    expect(service.catchUpVectorRebuild).not.toHaveBeenCalled();
    expect(service.promoteVectorRebuild).not.toHaveBeenCalled();
    expect(service.recoverVectorRebuild).not.toHaveBeenCalled();
    expect(root.querySelector('[aria-live="polite"]')).toBeTruthy();
  });

  it('requires an operation-specific confirmation before rebuilding V1', () => {
    service.rebuildLegacyIndex.and.returnValue(of({ indexedCount: 18 }));
    const component = fixture.componentInstance;

    component.reviewLegacyRebuild();
    expect(service.rebuildLegacyIndex).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Confirm V1 rebuild');

    component.confirmLegacyRebuild();
    fixture.detectChanges();

    expect(service.rebuildLegacyIndex).toHaveBeenCalledTimes(1);
    expect(component.legacyIndexedCount()).toBe(18);
    expect(component.liveMessage()).toContain('18 listings indexed');
  });

  it('prevents double submission while a command is pending', () => {
    const pending = new Subject<{ indexedCount: number }>();
    service.rebuildLegacyIndex.and.returnValue(pending);
    const component = fixture.componentInstance;

    component.reviewLegacyRebuild();
    component.confirmLegacyRebuild();
    component.confirmLegacyRebuild();

    expect(service.rebuildLegacyIndex).toHaveBeenCalledTimes(1);
    expect(component.busy()).toBeTrue();
    pending.next({ indexedCount: 2 });
    pending.complete();
    expect(component.busy()).toBeFalse();
  });

  it('starts once, refreshes explicitly, and resumes exactly one page after confirmation', () => {
    service.startEmbeddingRequestBackfill.and.returnValue(of(status));
    service.getEmbeddingRequestBackfill.and.returnValue(of({
      ...status,
      commandOutcome: 'OBSERVED',
    }));
    service.resumeEmbeddingRequestBackfill.and.returnValue(of({
      ...status,
      pageCount: 2,
      processedCount: 100,
    }));
    const component = fixture.componentInstance;

    component.reviewBackfillStart();
    expect(service.startEmbeddingRequestBackfill).not.toHaveBeenCalled();
    component.confirmBackfillStart();
    expect(service.startEmbeddingRequestBackfill).toHaveBeenCalledTimes(1);
    expect(component.backfillStatus()?.runId).toBe(status.runId);

    component.refreshBackfillStatus();
    expect(service.getEmbeddingRequestBackfill).toHaveBeenCalledOnceWith(status.runId);

    component.reviewBackfillResume();
    expect(service.resumeEmbeddingRequestBackfill).not.toHaveBeenCalled();
    component.confirmBackfillResume();
    expect(service.resumeEmbeddingRequestBackfill).toHaveBeenCalledOnceWith(status.runId);
    expect(component.backfillStatus()?.pageCount).toBe(2);
  });

  it('prepares V2 only after a separate confirmation', () => {
    service.startVectorRebuild.and.returnValue(of(vectorStatus));
    const component = fixture.componentInstance;

    component.reviewVectorStart();
    expect(service.startVectorRebuild).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Confirm V2 prepare');

    component.confirmVectorStart();

    expect(service.startVectorRebuild).toHaveBeenCalledTimes(1);
    expect(component.vectorStatus()?.runId).toBe(vectorStatus.runId);
    expect(component.liveMessage()).toContain('prepared');
  });

  it('uses Product-provided V2 eligibility booleans instead of deriving readiness from counts', () => {
    const component = fixture.componentInstance;
    component.vectorStatus.set({
      ...vectorStatus,
      canCatchUp: true,
      canPromote: false,
      canRecover: false,
      catchUpWorkCount: 0,
    });
    service.catchUpVectorRebuild.and.returnValue(of({
      ...vectorStatus,
      commandOutcome: 'REPLAYED',
    }));
    service.promoteVectorRebuild.and.returnValue(of({
      ...vectorStatus,
      commandOutcome: 'SUCCEEDED',
      state: 'PROMOTED',
      candidateRole: 'ACTIVE_V2',
      previousGenerationRole: 'RETAINED_PREVIOUS',
      canCatchUp: false,
      canPromote: false,
      canRecover: false,
      promotedAt: '2026-07-24T10:00:02Z',
    }));
    fixture.detectChanges();

    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    const promoteButton = buttons.find(button => button.textContent?.includes('Review V2 promotion'));
    expect(promoteButton?.hasAttribute('disabled')).toBeTrue();

    component.reviewVectorPromote();
    component.confirmVectorPromote();
    expect(service.promoteVectorRebuild).not.toHaveBeenCalled();

    component.reviewVectorCatchUp();
    component.confirmVectorCatchUp();
    expect(service.catchUpVectorRebuild).toHaveBeenCalledOnceWith(vectorStatus.runId);

    component.vectorStatus.set({
      ...vectorStatus,
      canCatchUp: false,
      canPromote: true,
      canRecover: false,
      catchUpWorkCount: 7,
    });
    fixture.detectChanges();
    component.reviewVectorPromote();
    component.confirmVectorPromote();

    expect(service.promoteVectorRebuild).toHaveBeenCalledOnceWith(vectorStatus.runId);
  });

  it('refreshes and recovers V2 only through explicit safe commands', () => {
    const component = fixture.componentInstance;
    component.vectorStatus.set({
      ...vectorStatus,
      state: 'ROLLBACK_REQUIRED',
      canCatchUp: false,
      canPromote: false,
      canRecover: true,
    });
    service.getVectorRebuild.and.returnValue(of({
      ...vectorStatus,
      commandOutcome: 'OBSERVED',
      state: 'ROLLBACK_REQUIRED',
      canCatchUp: false,
      canPromote: false,
      canRecover: true,
    }));
    service.recoverVectorRebuild.and.returnValue(of({
      ...vectorStatus,
      commandOutcome: 'SUCCEEDED',
      state: 'CATCHING_UP',
      canCatchUp: true,
      canPromote: false,
      canRecover: false,
    }));

    component.refreshVectorStatus();
    expect(service.getVectorRebuild).toHaveBeenCalledOnceWith(vectorStatus.runId);

    component.reviewVectorRecover();
    expect(service.recoverVectorRebuild).not.toHaveBeenCalled();
    component.confirmVectorRecover();

    expect(service.recoverVectorRebuild).toHaveBeenCalledOnceWith(vectorStatus.runId);
  });

  it('does not automatically replay a failed POST and renders no raw backend detail', () => {
    service.startEmbeddingRequestBackfill.and.returnValue(throwError(() => ({
      status: 503,
      error: {
        password: 'secret',
        vector: [1, 2, 3],
        message: 'upstream internals',
      },
    })));
    const component = fixture.componentInstance;

    component.reviewBackfillStart();
    component.confirmBackfillStart();
    fixture.detectChanges();

    expect(service.startEmbeddingRequestBackfill).toHaveBeenCalledTimes(1);
    expect(service.getEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.resumeEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.startVectorRebuild).not.toHaveBeenCalled();
    const root = fixture.nativeElement as HTMLElement;
    const errorText = root.querySelector('.notice.error')?.textContent ?? '';
    expect(errorText).not.toContain('secret');
    expect(errorText).not.toContain('upstream internals');
    expect(errorText).not.toContain('[1,2,3]');
    expect(errorText).toContain('invalid or unavailable response');
  });
});

describe('AdminSearchMaintenanceComponent default-off boundary', () => {
  it('renders no controls and makes zero calls when disabled', async () => {
    const service = jasmine.createSpyObj<AdminSearchMaintenanceService>(
      'AdminSearchMaintenanceService',
      [
        'rebuildLegacyIndex',
        'startEmbeddingRequestBackfill',
        'getEmbeddingRequestBackfill',
        'resumeEmbeddingRequestBackfill',
        'startVectorRebuild',
        'getVectorRebuild',
        'catchUpVectorRebuild',
        'promoteVectorRebuild',
        'recoverVectorRebuild',
      ],
    );
    await TestBed.configureTestingModule({
      imports: [AdminSearchMaintenanceComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AdminSearchMaintenanceService, useValue: service },
        { provide: ADMIN_SEARCH_MAINTENANCE_ENABLED, useValue: false },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AdminSearchMaintenanceComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
    expect(service.rebuildLegacyIndex).not.toHaveBeenCalled();
    expect(service.startEmbeddingRequestBackfill).not.toHaveBeenCalled();
    expect(service.startVectorRebuild).not.toHaveBeenCalled();
  });
});
