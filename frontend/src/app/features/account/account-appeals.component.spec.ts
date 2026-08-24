import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { EnforcementNotice, MyAppeal } from '../../core/models/appeal.model';
import { AppealService } from '../../core/services/appeal.service';
import { AccountAppealsComponent } from './account-appeals.component';

describe('AccountAppealsComponent', () => {
  let fixture: ComponentFixture<AccountAppealsComponent>;
  let component: AccountAppealsComponent;
  let service: jasmine.SpyObj<AppealService>;
  const notice: EnforcementNotice = {
    enforcementActionId: '01ARZ3NDEKTSV4RRFFQ69G5AAC', targetType: 'USER',
    targetId: '01ARZ3NDEKTSV4RRFFQ69G5AAA', safeTargetLabel: 'Your marketplace account',
    actionType: 'SUSPEND', scopes: ['USER_SELLING'], effectiveAt: '2026-08-16T00:00:00Z',
    expiresAt: null, supportReference: 'ENF-Q69G5AAC', appealEligible: true,
    appealIneligibilityReason: null, appealId: null, appealStatus: null,
  };

  beforeEach(async () => {
    service = jasmine.createSpyObj<AppealService>('AppealService', ['notices', 'mine', 'submit']);
    service.notices.and.returnValue(of([notice]));
    service.mine.and.returnValue(of([]));
    service.submit.and.returnValue(of({
      appealId: '01ARZ3NDEKTSV4RRFFQ69G5AAD', status: 'SUBMITTED',
      submittedAt: '2026-08-16T00:00:00Z', supportReference: 'APL-Q69G5AAD',
    }));
    await TestBed.configureTestingModule({
      imports: [AccountAppealsComponent],
      providers: [provideZonelessChangeDetection(), provideRouter([]), { provide: AppealService, useValue: service }],
    }).compileComponents();
    fixture = TestBed.createComponent(AccountAppealsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('shows an eligible action and requires an explanation for Other', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Your marketplace account');
    host.querySelector<HTMLButtonElement>('.notice-action button')!.click();
    fixture.detectChanges();

    component.reason = 'OTHER';
    component.explanation = '   ';
    component.submit();
    fixture.detectChanges();

    expect(host.textContent).toContain('Add an explanation when choosing Other.');
    expect(service.submit).not.toHaveBeenCalled();
  });

  it('shows the safe duplicate-appeal conflict without losing the selected action', () => {
    component.select(notice);
    component.explanation = 'Please review this action.';
    service.submit.and.returnValue(throwError(() => ({ status: 409 })));

    component.submit();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('An appeal already exists for this action.');
    expect(component.selected()).toBe(notice);
  });

  it('shows only the marketplace-safe final outcome and current effective state', () => {
    const finalAppeal: MyAppeal = {
      appealId: '01ARZ3NDEKTSV4RRFFQ69G5AAD', enforcementActionId: notice.enforcementActionId,
      targetType: 'USER', targetId: notice.targetId, safeTargetLabel: 'Your marketplace account',
      actionType: 'SUSPEND', status: 'REVOKED', submittedAt: '2026-08-16T00:00:00Z',
      updatedAt: '2026-08-16T01:00:00Z', supportReference: 'APL-Q69G5AAD',
      resolvedAt: '2026-08-16T01:00:00Z', safeOutcomeSummary: 'The original action was revoked after review.',
      currentEffectiveEnforcementState: 'RESTRICTED',
    };
    service.mine.and.returnValue(of([finalAppeal]));

    component.load();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('The original action was revoked after review.');
    expect(text).toContain('Current effective enforcement: Restricted');
    expect(text).toContain('Decision');
    expect(text).not.toContain('reviewer');
    expect(text).not.toContain('internal');
  });
});
