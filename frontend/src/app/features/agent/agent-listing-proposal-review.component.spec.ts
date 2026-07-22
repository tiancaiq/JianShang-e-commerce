import { HttpErrorResponse } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { Category } from '../../core/models/listing.model';
import {
  ListingProposalResponse,
  ListingProposalReviewMedia,
} from './agent-listing-proposal.model';
import { AgentListingProposalReviewComponent } from './agent-listing-proposal-review.component';
import { AgentListingProposalService } from './agent-listing-proposal.service';

describe('AgentListingProposalReviewComponent', () => {
  let fixture: ComponentFixture<AgentListingProposalReviewComponent>;
  let component: AgentListingProposalReviewComponent;
  let proposalService: jasmine.SpyObj<AgentListingProposalService>;

  const listingId = '01H00000000000000000000001';
  const mediaId = '01J00000000000000000000001';
  const secondMediaId = '01J00000000000000000000002';
  const proposalId = '01K00000000000000000000001';
  const categories: Category[] = [{
    id: '01C00000000000000000000001',
    slug: 'bicycles',
    name: 'Bicycles',
    parentId: null,
    displayOrder: 0,
    attributes: [],
  }];
  const media: ListingProposalReviewMedia[] = [
    {
      mediaId,
      imageUrl: '/api/v1/listing-media/one',
      altText: 'Blue bicycle',
      eligible: true,
    },
    {
      mediaId: secondMediaId,
      imageUrl: '/api/v1/listing-media/two',
      altText: 'Rejected image',
      eligible: false,
    },
  ];
  const readyProposal: ListingProposalResponse = {
    proposalId,
    status: 'READY',
    proposalVersion: 1,
    schemaVersion: 'LISTING_PROPOSAL_V1',
    listingId,
    sourceListingVersion: 12,
    sourceMediaEvidence: [{
      mediaId,
      evidenceId: 'E1',
      sha256: 'a'.repeat(64),
      actualMime: 'image/png',
      byteSize: 2048,
    }],
    proposal: {
      schemaVersion: 'ai-list-proposal-v1',
      suggestedTitle: {
        value: 'City bicycle',
        confidence: 0.91,
        evidenceIds: ['E1'],
      },
      suggestedDescription: {
        value: 'Blue city bicycle with a rear rack.',
        confidence: 0.82,
        evidenceIds: ['E1'],
      },
      categoryCandidates: [{
        label: 'Bicycles',
        confidence: 0.88,
        evidenceIds: ['E1'],
      }],
      evidence: [{
        evidenceId: 'E1',
        mediaId,
        observation: 'A blue bicycle with a rear rack is visible.',
      }],
      unknownFields: [
        'SELLER_IDENTITY',
        'PRICE',
        'EXACT_LOCATION',
        'QUANTITY',
        'CONDITION',
        'NEGOTIABILITY',
        'POLICY_CLAIMS',
        'CONTACT_DATA',
        'AUTHENTICITY',
        'SAFETY',
      ],
      proposalOnly: true,
      requiresSellerConfirmation: true,
    },
    proposalOnly: true,
    requiresSellerConfirmation: true,
    createdAt: '2026-07-20T10:00:00Z',
    expiresAt: '2026-07-21T10:00:00Z',
    dismissedAt: null,
    contentPurgedAt: null,
    resultMetadata: {
      instructionVersion: 'ai-list-instructions-v1',
      schemaVersion: 'ai-list-proposal-v1',
      providerMode: 'FAKE',
      resultCode: 'PROPOSAL_READY',
      latencyMs: 12,
      inputTokens: 0,
      outputTokens: 0,
    },
  };

  beforeEach(async () => {
    proposalService = jasmine.createSpyObj<AgentListingProposalService>(
      'AgentListingProposalService',
      ['createOrResume', 'get', 'dismiss'],
    );
    proposalService.createOrResume.and.returnValue(of(readyProposal));
    proposalService.dismiss.and.returnValue(of({
      ...readyProposal,
      status: 'DISMISSED',
      proposalVersion: 2,
      sourceMediaEvidence: null,
      proposal: null,
      proposalOnly: null,
      requiresSellerConfirmation: null,
      dismissedAt: '2026-07-20T10:05:00Z',
      contentPurgedAt: '2026-07-20T10:05:00Z',
      resultMetadata: null,
    }));

    await TestBed.configureTestingModule({
      imports: [AgentListingProposalReviewComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AgentListingProposalService, useValue: proposalService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AgentListingProposalReviewComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('listingId', listingId);
    fixture.componentRef.setInput('listingVersion', 12);
    fixture.componentRef.setInput('listingStatus', 'DRAFT');
    fixture.componentRef.setInput('media', media);
    fixture.componentRef.setInput('categories', categories);
    fixture.detectChanges();
  });

  it('starts network-silent and exposes only eligible owned-image choices', () => {
    const checkboxes = fixture.nativeElement.querySelectorAll(
      '.image-option input[type="checkbox"]',
    ) as NodeListOf<HTMLInputElement>;

    expect(proposalService.createOrResume).not.toHaveBeenCalled();
    expect(proposalService.get).not.toHaveBeenCalled();
    expect(proposalService.dismiss).not.toHaveBeenCalled();
    expect(checkboxes.length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('0 of 4 images selected');
  });

  it('caps image selection at four even when more eligible media is supplied', () => {
    const fiveImages = Array.from({ length: 5 }, (_, index): ListingProposalReviewMedia => ({
      mediaId: `01J0000000000000000000000${index + 1}`,
      imageUrl: `data:image/png;base64,${index}`,
      altText: `Listing image ${index + 1}`,
      eligible: true,
    }));
    fixture.componentRef.setInput('media', fiveImages);
    fixture.detectChanges();

    fiveImages.forEach(item => component.toggleMedia(item.mediaId));
    fixture.detectChanges();

    expect(component.selectedMediaIds()).toEqual(fiveImages.slice(0, 4).map(item => item.mediaId));
    const fifth = fixture.nativeElement.querySelectorAll(
      '.image-option input[type="checkbox"]',
    )[4] as HTMLInputElement;
    expect(fifth.disabled).toBeTrue();
  });

  it('creates through the exact versioned request and keeps one client ID across outage retry', () => {
    proposalService.createOrResume.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 503,
      error: { error: { code: 'AI_PROPOSAL_UNAVAILABLE' } },
    })));
    component.toggleMedia(mediaId);

    component.createOrResume();
    component.createOrResume();

    const first = proposalService.createOrResume.calls.argsFor(0)[0];
    const second = proposalService.createOrResume.calls.argsFor(1)[0];
    expect(first).toEqual(jasmine.objectContaining({
      schemaVersion: 'LISTING_PROPOSAL_V1',
      listingId,
      expectedListingVersion: 12,
      mediaIds: [mediaId],
    }));
    expect(first.clientRequestId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(second.clientRequestId).toBe(first.clientRequestId);
    expect(component.errorMessage()).toContain('Continue editing normally');
  });

  it('renders evidence, confidence, unknown fields, and seller-controlled keep/edit/discard choices', () => {
    component.toggleMedia(mediaId);
    component.createOrResume();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';
    const reviewFinalValues = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find(button => button.textContent?.includes('Review final values'));

    expect(text).toContain('A blue bicycle with a rear rack is visible.');
    expect(text).toContain('91%');
    expect(text).toContain('Price');
    expect(text).toContain('Exact location');
    expect(text).toContain('Condition');
    expect(text).toContain('Quantity');
    expect(text).toContain('Negotiability');
    expect(text).toContain('Seller identity');
    expect(text).toContain('Authenticity');
    expect(text).toContain('Safety');
    expect(reviewFinalValues?.disabled).toBeFalse();
    expect(component.titleReview()).toEqual({ choice: 'KEEP', value: 'City bicycle' });

    component.setTitleValue('Seller edited bicycle title');
    component.setDescriptionChoice('DISCARD');

    expect(component.titleReview()).toEqual({
      choice: 'EDIT',
      value: 'Seller edited bicycle title',
    });
    expect(component.descriptionReview().choice).toBe('DISCARD');
  });

  it('requires a distinct confirmation and emits only seller-selected final values', () => {
    const emit = spyOn(component.applyConfirmed, 'emit');
    component.toggleMedia(mediaId);
    component.createOrResume();
    component.setTitleValue('Seller edited bicycle title');
    component.setDescriptionChoice('DISCARD');
    component.setCategoryChoice('DISCARD');
    fixture.detectChanges();

    const review = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find(button => button.textContent?.includes('Review final values'));
    review?.click();
    fixture.detectChanges();

    expect(emit).not.toHaveBeenCalled();
    const ledger = fixture.nativeElement.querySelector('.confirmation-ledger') as HTMLElement;
    const ledgerText = ledger.textContent || '';
    expect(ledgerText).toContain('Confirm exact final values');
    expect(ledgerText).toContain('Source version 12');
    expect(ledgerText).toContain('Seller edited bicycle title');
    expect(ledgerText).not.toContain('Blue city bicycle with a rear rack.');
    expect(ledger.ownerDocument.activeElement).toBe(ledger);

    const confirm = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find(button => button.textContent?.includes('Confirm and update listing'));
    confirm?.click();

    expect(emit).toHaveBeenCalledOnceWith({
      proposalId,
      listingId,
      sourceListingVersion: 12,
      fields: { title: 'Seller edited bicycle title' },
    });
  });

  it('replaces an open confirmation with the Product failure or stale-conflict outcome', () => {
    component.toggleMedia(mediaId);
    component.createOrResume();
    component.openConfirmation();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.confirmation-ledger')).toBeTruthy();

    fixture.componentRef.setInput('applicationState', 'FAILED');
    fixture.componentRef.setInput(
      'applicationMessage',
      'Product could not update the listing. Nothing is marked applied.',
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.confirmation-ledger')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Selected fields were not applied');
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Nothing is marked applied');

    component.openConfirmation();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.confirmation-ledger')).toBeTruthy();

    fixture.componentRef.setInput('applicationState', 'CONFLICT');
    fixture.componentRef.setInput(
      'applicationMessage',
      'Reload and compare the current draft; no automatic retry occurred.',
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.confirmation-ledger')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Listing version conflict');
    expect(component.canOpenConfirmation()).toBeFalse();
  });

  it('never converts an AI category label to an ID and requires an explicit existing category choice', () => {
    const emit = spyOn(component.applyConfirmed, 'emit');
    component.toggleMedia(mediaId);
    component.createOrResume();
    component.setTitleChoice('DISCARD');
    component.setDescriptionChoice('DISCARD');
    component.chooseCategoryCandidate(readyProposal.proposal!.categoryCandidates[0]);

    expect(component.confirmationFields()).toEqual([]);
    expect(component.canOpenConfirmation()).toBeFalse();

    component.selectCategoryId(categories[0].id);
    component.openConfirmation();
    component.confirmApplication();

    expect(emit).toHaveBeenCalledOnceWith({
      proposalId,
      listingId,
      sourceListingVersion: 12,
      fields: { categoryId: categories[0].id },
    });
  });

  it('blocks confirmation while ordinary editor changes are unsaved', () => {
    component.toggleMedia(mediaId);
    component.createOrResume();
    fixture.componentRef.setInput('hasUnsavedEditorChanges', true);
    fixture.detectChanges();

    expect(component.canOpenConfirmation()).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Save or discard ordinary editor changes before applying proposal fields.');
  });

  it('warns when the editor version changes and never applies or submits a Product mutation', () => {
    component.toggleMedia(mediaId);
    component.createOrResume();
    fixture.componentRef.setInput('listingVersion', 13);
    fixture.detectChanges();

    expect(component.versionChanged()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Listing version changed');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('version 12');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('version 13');
    expect(proposalService.createOrResume).toHaveBeenCalledTimes(1);
    expect(component.canOpenConfirmation()).toBeFalse();
  });

  it('dismisses with one replay-safe key and purges the review display', () => {
    component.toggleMedia(mediaId);
    component.createOrResume();

    component.dismiss();
    fixture.detectChanges();

    expect(proposalService.dismiss).toHaveBeenCalledOnceWith(
      proposalId,
      jasmine.stringMatching(/^[0-9A-HJKMNP-TV-Z]{26}$/),
    );
    expect(component.proposal()?.status).toBe('DISMISSED');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Your listing was not changed');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('City bicycle');
  });

  it('keeps image selection and review controls keyboard-native and explicitly labelled', () => {
    const liveSelection = fixture.nativeElement.querySelector('[aria-live="polite"]');
    component.toggleMedia(mediaId);
    component.createOrResume();
    fixture.detectChanges();

    const titleChoices = fixture.nativeElement.querySelectorAll(
      'input[name="proposalTitleChoice"]',
    ) as NodeListOf<HTMLInputElement>;
    const evidenceHeading = fixture.nativeElement.querySelector('#proposal-evidence-heading');

    expect(titleChoices.length).toBe(3);
    expect(Array.from(titleChoices).every(input => input.type === 'radio')).toBeTrue();
    expect(evidenceHeading).toBeTruthy();
    expect(liveSelection).toBeTruthy();

    component.selectCategoryId(categories[0].id);
    component.openConfirmation();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#proposal-confirmation-heading')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.confirmation-ledger dl')).toBeTruthy();
  });
});
