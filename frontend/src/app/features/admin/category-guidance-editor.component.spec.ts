import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CategoryGuidanceSource } from '../../core/models/category-guidance.model';
import { CategoryGuidanceService } from '../../core/services/category-guidance.service';
import { ListingService } from '../../core/services/listing.service';
import { CategoryGuidanceEditorComponent } from './category-guidance-editor.component';

describe('CategoryGuidanceEditorComponent', () => {
  let fixture: ComponentFixture<CategoryGuidanceEditorComponent>;
  let guidanceService: jasmine.SpyObj<CategoryGuidanceService>;
  let listingService: jasmine.SpyObj<ListingService>;

  const source: CategoryGuidanceSource = {
    sourceType: 'CATEGORY_GUIDANCE',
    sourceId: '01K00000000000000000000002',
    sourceVersion: '2',
    supersedesVersion: '1',
    lifecycle: 'ACTIVE',
    visibility: 'PUBLIC',
    language: 'en',
    effectiveFrom: '2026-07-19T08:00:00Z',
    invalidatedAt: null,
    contentHash: 'a'.repeat(64),
    content: {
      categorySlug: 'electronics',
      categoryName: 'Electronics',
      title: 'Buying electronics',
      body: 'Check the model and visible condition.',
    },
  };

  beforeEach(async () => {
    guidanceService = jasmine.createSpyObj<CategoryGuidanceService>(
      'CategoryGuidanceService',
      ['current', 'publish', 'retire'],
    );
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['getCategories']);
    listingService.getCategories.and.returnValue(of([{
      id: source.sourceId,
      slug: 'electronics',
      name: 'Electronics',
      parentId: null,
      displayOrder: 10,
      attributes: [],
    }]));
    guidanceService.current.and.returnValue(of(source));

    await TestBed.configureTestingModule({
      imports: [CategoryGuidanceEditorComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: CategoryGuidanceService, useValue: guidanceService },
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CategoryGuidanceEditorComponent);
    fixture.detectChanges();
  });

  it('loads current guidance and exposes only manual authoring controls', () => {
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Category guidance');
    expect(text).toContain('human-authored');
    expect(text).toContain('ACTIVE · v2');
    expect(text).not.toContain('Generate');
    expect(guidanceService.current).toHaveBeenCalledWith(source.sourceId, 'en');
  });

  it('requires confirmation and publishes against the loaded version', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    guidanceService.publish.and.returnValue(of({ ...source, sourceVersion: '3' }));
    const component = fixture.componentInstance;
    component.title = 'Updated buying guidance';
    component.body = 'Inspect the model and included accessories.';

    component.publish();

    expect(guidanceService.publish).toHaveBeenCalledWith(
      source.sourceId,
      'en',
      '2',
      {
        title: 'Updated buying guidance',
        body: 'Inspect the model and included accessories.',
      },
    );
    expect(component.current()?.sourceVersion).toBe('3');
  });

  it('reloads after an optimistic version conflict', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    guidanceService.publish.and.returnValue(throwError(() => ({ status: 409 })));
    const component = fixture.componentInstance;
    component.title = 'Conflicting edit';
    component.body = 'This edit uses a stale source version.';
    guidanceService.current.calls.reset();

    component.publish();

    expect(guidanceService.current).toHaveBeenCalledWith(source.sourceId, 'en');
    expect(component.message()).toContain('changed in another request');
  });

  it('requires confirmation before retiring active guidance', () => {
    spyOn(window, 'confirm').and.returnValue(false);

    fixture.componentInstance.retire();

    expect(guidanceService.retire).not.toHaveBeenCalled();
  });
});
