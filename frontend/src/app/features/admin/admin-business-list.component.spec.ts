import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AdminBusinessService } from '../../core/services/admin-business.service';
import { AdminBusinessListComponent } from './admin-business-list.component';

describe('AdminBusinessListComponent', () => {
  let fixture: ComponentFixture<AdminBusinessListComponent>;
  const service = jasmine.createSpyObj<AdminBusinessService>('AdminBusinessService', ['search']);

  beforeEach(async () => {
    service.search.calls.reset();
    service.search.and.returnValue(of({
      data: {
        items: [{
          businessId: '01B00000000000000000000001',
          displayName: 'Harbor Workshop',
          businessState: 'ACTIVE',
          createdAt: '2026-08-15T00:00:00Z',
          updatedAt: '2026-08-15T00:00:00Z',
          version: 3,
          ownerUserId: '01U00000000000000000000001',
          memberCount: 2,
          activeListingCount: 4,
          strongestActiveAction: null,
          activeScopes: [],
          activeEnforcementCount: 0,
        }],
        page: 0,
        size: 25,
        totalElements: 1,
        totalPages: 1,
        sort: 'createdAt,desc',
      },
    }));
    await TestBed.configureTestingModule({
      imports: [AdminBusinessListComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AdminBusinessService, useValue: service },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminBusinessListComponent);
    fixture.detectChanges();
  });

  it('renders the server result and sends trimmed filters', () => {
    expect(fixture.nativeElement.textContent).toContain('Harbor Workshop');
    expect(fixture.nativeElement.textContent).toContain('CLEAR');

    fixture.componentInstance.query = '  Harbor  ';
    fixture.componentInstance.enforcementState = 'RESTRICTED';
    fixture.componentInstance.applyFilters();

    expect(service.search).toHaveBeenCalledWith(jasmine.objectContaining({
      q: 'Harbor',
      enforcementState: 'RESTRICTED',
      page: 0,
      size: 25,
    }));
  });
});
