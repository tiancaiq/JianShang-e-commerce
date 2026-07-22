import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { BusinessStoreContext } from '../../core/models/business-store.model';
import { Category, ListingCondition, ListingDraft, ListingImage, ListingSellerType } from '../../core/models/listing.model';
import { BusinessStoreService } from '../../core/services/business-store.service';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
import { AGENT_CUSTOMER_SERVICE_ENABLED } from '../agent/agent-customer-service.capability';
import {
  ListingProposalApplicationCommand,
  ListingProposalApplicationState,
  ListingProposalReviewMedia,
} from '../agent/agent-listing-proposal.model';
import {
  AgentListingProposalReviewComponent,
} from '../agent/agent-listing-proposal-review.component';
import {
  buildListingDraftRequest,
  listingDraftToFormState,
  ListingDraftFormState,
  MAX_LISTING_IMAGE_COUNT,
  canSubmitListingForReview,
  isClosableListingStatus,
  isEditableListingStatus,
  listingDraftRequestSnapshot,
  validateListingDraftForm,
  validateSelectedListingImage,
} from './listing-draft-form.helpers';
import { ListingMediaUploadError, ListingMediaUploadService } from './listing-media-upload.service';

interface PendingListingMedia {
  id: string;
  file: File;
  previewUrl: string;
}

@Component({
  selector: 'app-listing-draft-form',
  standalone: true,
  imports: [FormsModule, AgentListingProposalReviewComponent],
  template: `
    <section class="listing-page">
      <header class="page-header">
        <div>
          <h1>{{ pageTitle() }}</h1>
          <p>{{ pageDescription() }}</p>
        </div>
      </header>

      <form class="draft-form" (ngSubmit)="saveDraft()">
        <div class="form-grid">
          @if (!marketplaceAccountMode() && !businessStoreMode()) {
            <label class="field">
              <span>Seller type</span>
              <select name="sellerType" [(ngModel)]="sellerType" [disabled]="!canEditDraft() || saving() || isEditMode()">
                <option value="INDIVIDUAL">Individual</option>
                <option value="BUSINESS">Business</option>
              </select>
            </label>
          }

          @if (sellerType === 'BUSINESS' && !businessStoreMode()) {
            <label class="field">
              <span>Business ID</span>
              <input name="businessId" [(ngModel)]="businessId" maxlength="26" [disabled]="!canEditDraft() || saving() || isEditMode()" />
            </label>
          }

          <label class="field">
            <span>Category</span>
            <select name="categoryId" [(ngModel)]="categoryId" [class.invalid]="fieldInvalid('categoryId')" [disabled]="!canEditDraft() || loadingCategories() || saving()">
              <option value="">Select category</option>
              @for (category of categories(); track category.id) {
                <option [value]="category.id">{{ category.name }}</option>
              }
            </select>
          </label>

          <label class="field">
            <span>Condition</span>
            <select name="condition" [(ngModel)]="condition" [disabled]="!canEditDraft() || saving()">
              <option value="NEW">New</option>
              <option value="OPEN_BOX">Open box</option>
              <option value="LIKE_NEW">Like new</option>
              <option value="GOOD">Good</option>
              <option value="FAIR">Fair</option>
              <option value="FOR_PARTS">For parts</option>
            </select>
          </label>
        </div>

        <label class="field">
          <span>Title</span>
          <input name="title" [(ngModel)]="title" maxlength="160" [class.invalid]="fieldInvalid('title')" [disabled]="!canEditDraft() || saving()" />
        </label>

        <label class="field">
          <span>Description</span>
          <textarea name="description" [(ngModel)]="description" maxlength="5000" rows="5" [class.invalid]="fieldInvalid('description')" [disabled]="!canEditDraft() || saving()"></textarea>
        </label>

        <label class="field">
          <span>Condition notes</span>
          <textarea name="conditionNotes" [(ngModel)]="conditionNotes" maxlength="1000" rows="3" [disabled]="!canEditDraft() || saving()"></textarea>
        </label>

        <div class="form-grid">
          <label class="field">
            <span>Price</span>
            <input name="price" type="number" min="0" step="0.01" [(ngModel)]="price" [class.invalid]="fieldInvalid('price')" [disabled]="!canEditDraft() || saving()" />
          </label>

          <label class="field">
            <span>Currency</span>
            <input name="currency" [(ngModel)]="currency" maxlength="3" [class.invalid]="fieldInvalid('currency')" [disabled]="!canEditDraft() || saving()" />
          </label>
        </div>

        @if (sellerType === 'INDIVIDUAL') {
          <div class="form-grid">
            <label class="field">
              <span>City</span>
              <input name="publicCity" [(ngModel)]="publicCity" maxlength="120" [disabled]="!canEditDraft() || saving()" />
            </label>

            <label class="field">
              <span>County</span>
              <input name="publicRegion" [(ngModel)]="publicRegion" maxlength="120" [disabled]="!canEditDraft() || saving()" />
            </label>

            <label class="field">
              <span>Quantity</span>
              <input name="quantity" type="number" min="1" step="1" [(ngModel)]="quantity" [class.invalid]="fieldInvalid('quantity')" [disabled]="!canEditDraft() || saving()" />
            </label>
          </div>

          <label class="checkbox-row">
            <input type="checkbox" name="negotiable" [(ngModel)]="negotiable" [disabled]="!canEditDraft() || saving()" />
            <span>Price is negotiable</span>
          </label>
        } @else {
          <div class="form-grid">
            <label class="field">
              <span>SKU</span>
              <input name="sku" [(ngModel)]="sku" maxlength="64" [disabled]="!canEditDraft() || saving()" />
            </label>

            <label class="field">
              <span>Quantity</span>
              <input name="quantity" type="number" min="1" step="1" [(ngModel)]="quantity" [class.invalid]="fieldInvalid('quantity')" [disabled]="!canEditDraft() || saving()" />
            </label>
          </div>
        }

        @if (businessStoreMode() && storeContext()) {
          <div class="success-message">Store: {{ storeContext()?.store?.name }}</div>
        }

        @if (businessStoreMode() && isEditMode() && listingStatus() === 'ACTIVE') {
          <div class="success-message">
            This item is active. Pause it before changing item details or images.
          </div>
        }

        <section class="media-panel" aria-label="Listing media">
          <label class="field">
            <span>Listing images</span>
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              [disabled]="!canEditDraft() || saving() || uploadingMedia()"
              (change)="handleMediaSelected($event)"
            />
          </label>

          <p class="media-hint">{{ mediaItems().length + pendingMediaItems().length }} / {{ maxImageCount }} images attached.</p>

          @if (pendingMediaItems().length > 0) {
            <ul class="media-list">
              @for (media of pendingMediaItems(); track media.id) {
                <li>
                  <img [src]="media.previewUrl" [alt]="media.file.name" />
                  <span>{{ media.file.name }}</span>
                  <button type="button" class="text-btn" (click)="removePendingMedia(media.id)" [disabled]="saving() || uploadingMedia()">
                    Remove
                  </button>
                </li>
              }
            </ul>
          }

          @if (mediaError()) {
            <div class="error-message">{{ mediaError() }}</div>
          }

          @if (mediaMessage()) {
            <div class="success-message">{{ mediaMessage() }}</div>
          }

          @if (mediaItems().length > 0) {
            <ul class="media-list">
              @for (media of mediaItems(); track media.id) {
                <li>
                  <img [src]="imageUrl(media)" [alt]="media.altText || media.originalFileName || 'Listing image'" />
                  <div class="media-copy">
                    <span>{{ media.originalFileName || media.objectKey }}</span>
                    <div class="media-status-row" aria-label="Image status">
                      <strong [class]="mediaStatusClass(media.uploadStatus)">Upload: {{ mediaStatusLabel(media.uploadStatus) }}</strong>
                      <strong [class]="mediaStatusClass(media.moderationStatus)">Review: {{ mediaStatusLabel(media.moderationStatus) }}</strong>
                    </div>
                  </div>
                  <button type="button" class="text-btn" (click)="removeAttachedMedia(media)" [disabled]="!canEditDraft() || saving() || uploadingMedia()">
                    Remove
                  </button>
                </li>
              }
            </ul>
          }
        </section>

        @if (errorMsg()) {
          <div class="error-message">{{ errorMsg() }}</div>
        }

        @if (savedId()) {
          <div class="success-message">Draft saved: {{ savedId() }}</div>
        }

        @if (isEditMode() && listingStatus() === 'CLOSED') {
          <div class="success-message">Listing is CLOSED. Save changes and submit for review to reopen it after approval.</div>
        }

        @if (isPendingReview()) {
          <div class="pending-review-panel">
            <strong>Pending review</strong>
            <span>Your listing and uploaded images are locked while an admin reviews them.</span>
          </div>
        }

        @if (isChangesRequested()) {
          <div class="pending-review-panel" data-testid="changes-requested-recovery">
            <strong>Changes requested</strong>
            <span>{{ moderationReason() || 'An admin requested updates to this listing.' }}</span>
            <span>Update the listing details or images, then submit it for review again.</span>
          </div>
        }

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="cancelDraft()" [disabled]="saving() || submitting()">Cancel</button>
          @if (!businessStoreMode() && isEditMode() && canCloseListing()) {
            <button type="button" class="danger-btn" (click)="closeListing()" [disabled]="saving() || submitting() || uploadingMedia()">
              Close listing
            </button>
          }
          @if (!businessStoreMode() && isEditMode() && canEditDraft()) {
            <button type="button" class="secondary-btn" (click)="submitForReview()" [disabled]="!canSubmitForReview() || saving() || submitting()">
              {{ submitting() ? 'Submitting' : 'Submit for review' }}
            </button>
          }
          @if (businessStoreMode() && isEditMode() && listingStatus() === 'DRAFT') {
            <button type="button" class="secondary-btn" (click)="publishStoreItem()" [disabled]="!canPublishStoreItem() || saving() || submitting() || uploadingMedia()">
              {{ submitting() ? 'Publishing' : 'Publish' }}
            </button>
          }
          @if (businessStoreMode() && isEditMode() && listingStatus() === 'ACTIVE') {
            <button type="button" class="secondary-btn" (click)="pauseStoreItem()" [disabled]="hasUnsavedListingChanges() || saving() || submitting() || uploadingMedia()">
              {{ submitting() ? 'Pausing' : 'Pause' }}
            </button>
          }
          @if (businessStoreMode() && isEditMode() && listingStatus() === 'PAUSED') {
            <button type="button" class="secondary-btn" (click)="relistStoreItem()" [disabled]="!canPublishStoreItem() || saving() || submitting() || uploadingMedia()">
              {{ submitting() ? 'Relisting' : 'Relist' }}
            </button>
          }
          @if (canEditDraft()) {
            <button type="submit" class="primary-btn" [disabled]="saving() || submitting() || loadingCategories() || loadingStoreContext()">
              {{ saveButtonLabel() }}
            </button>
          }
        </div>
      </form>

      @if (listingProposalReviewEnabled && proposalReviewEligible()) {
        <app-agent-listing-proposal-review
          [listingId]="savedId()"
          [listingVersion]="proposalReviewListingVersion()"
          [listingStatus]="listingStatus()"
          [media]="proposalReviewMedia()"
          [categories]="categories()"
          [hasUnsavedEditorChanges]="hasUnsavedListingChanges()"
          [applicationState]="proposalApplicationState()"
          [applicationMessage]="proposalApplicationMessage()"
          [appliedVersion]="proposalAppliedVersion()"
          (applyConfirmed)="applyListingProposal($event)"
        />
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .listing-page {
      max-width: 960px;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
      margin: 0 auto;
    }

    .page-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
      color: var(--listing-text);
    }

    .page-header p {
      color: var(--listing-subtle);
      font-size: 0.875rem;
    }

    .draft-form {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      max-width: 760px;
      background: var(--listing-surface);
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
      box-shadow: var(--listing-shadow);
    }

    .form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }

    .field span,
    .checkbox-row span {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--listing-muted);
    }

    .field input,
    .field select,
    .field textarea {
      width: 100%;
      min-height: 42px;
      padding: 0.625rem 0.75rem;
      background: var(--listing-field);
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      color: var(--listing-text);
      font-size: 0.875rem;
      outline: none;
    }

    .field textarea {
      min-height: 120px;
      resize: vertical;
    }

    .field input:focus,
    .field select:focus,
    .field textarea:focus {
      border-color: var(--listing-accent);
      box-shadow: 0 0 0 3px var(--listing-accent-muted);
    }

    .field input.invalid,
    .field select.invalid,
    .field textarea.invalid {
      border-color: #ec4899;
      box-shadow: 0 0 0 3px rgba(236, 72, 153, 0.15);
    }

    .checkbox-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .error-message,
    .success-message {
      border-radius: var(--radius-md);
      padding: 0.625rem 0.75rem;
      font-size: 0.875rem;
    }

    .error-message {
      color: var(--color-danger);
      background: rgba(244, 63, 94, 0.08);
      border: 1px solid rgba(244, 63, 94, 0.2);
    }

    .success-message {
      color: var(--color-success);
      background: rgba(34, 197, 94, 0.08);
      border: 1px solid rgba(34, 197, 94, 0.2);
    }

    .media-panel {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      border-top: 1px solid var(--listing-border);
      padding-top: 1rem;
    }

    .media-hint {
      margin: 0;
      color: var(--listing-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .media-list {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      list-style: none;
      padding: 0;
      margin: 0;
    }

    .media-list li {
      display: flex;
      justify-content: space-between;
      gap: 0.75rem;
      align-items: center;
      min-height: 64px;
      padding: 0.625rem 0.75rem;
      background: var(--listing-field);
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      font-size: 0.8125rem;
    }

    .media-list img {
      width: 56px;
      height: 42px;
      flex: 0 0 auto;
      object-fit: cover;
      border-radius: var(--radius-sm);
      background: var(--listing-surface);
    }

    .media-copy {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      min-width: 0;
    }

    .media-list span {
      overflow-wrap: anywhere;
      color: var(--listing-muted);
    }

    .media-list strong {
      width: fit-content;
      border-radius: 999px;
      padding: 0.15rem 0.45rem;
      background: rgba(148, 163, 184, 0.12);
      color: var(--listing-muted);
      font-size: 0.75rem;
    }

    .media-status-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
    }

    .media-list strong.status-good {
      background: rgba(34, 197, 94, 0.12);
      color: var(--color-success);
    }

    .media-list strong.status-warn {
      background: rgba(245, 158, 11, 0.14);
      color: #b45309;
    }

    .media-list strong.status-bad {
      background: rgba(244, 63, 94, 0.12);
      color: var(--color-danger);
    }

    .pending-review-panel {
      display: grid;
      gap: 0.25rem;
      border-radius: var(--radius-md);
      border: 1px solid rgba(245, 158, 11, 0.28);
      background: rgba(245, 158, 11, 0.1);
      color: var(--listing-text);
      padding: 0.75rem 0.875rem;
    }

    .pending-review-panel strong {
      color: #b45309;
    }

    .pending-review-panel span {
      color: var(--listing-muted);
      font-size: 0.875rem;
    }

    .media-selection {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.75rem;
      min-height: 40px;
      padding: 0.625rem 0.75rem;
      background: var(--listing-field);
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-md);
      color: var(--listing-muted);
      font-size: 0.8125rem;
    }

    .media-selection span {
      overflow-wrap: anywhere;
    }

    .text-btn {
      border: 0;
      background: transparent;
      color: var(--listing-accent);
      font-weight: 700;
      cursor: pointer;
    }

    .text-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.75rem;
    }

    .primary-btn,
    .secondary-btn,
    .danger-btn {
      min-height: 40px;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font-weight: 700;
      cursor: pointer;
    }

    .primary-btn {
      border: 1px solid transparent;
      background: var(--listing-primary-bg);
      color: var(--listing-primary-text);
    }

    .secondary-btn {
      border: 1px solid var(--listing-border);
      background: var(--listing-field);
      color: var(--listing-muted);
    }

    .danger-btn {
      border: 1px solid rgba(244, 63, 94, 0.28);
      background: rgba(244, 63, 94, 0.08);
      color: var(--color-danger);
    }

    .primary-btn:disabled,
    .secondary-btn:disabled,
    .danger-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    @media (max-width: 760px) {
      .form-grid {
        grid-template-columns: 1fr;
      }

      .actions {
        flex-direction: column-reverse;
      }
    }
  `]
})
export class ListingDraftFormComponent implements OnInit {
  private listingService = inject(ListingService);
  private businessStoreService = inject(BusinessStoreService);
  private mediaUploadService = inject(ListingMediaUploadService);
  private toastService = inject(ToastService);
  private route = inject(ActivatedRoute);
  router = inject(Router);
  readonly listingProposalReviewEnabled = inject(AGENT_CUSTOMER_SERVICE_ENABLED);

  categories = signal<Category[]>([]);
  loadingCategories = signal(false);
  loadingStoreContext = signal(false);
  loadingDraft = signal(false);
  saving = signal(false);
  submitting = signal(false);
  isEditMode = signal(false);
  listingStatus = signal('DRAFT');
  moderationReason = signal<string | null>(null);
  errorMsg = signal('');
  savedId = signal('');
  uploadingMedia = signal(false);
  mediaError = signal('');
  mediaMessage = signal('');
  proposalApplicationState = signal<ListingProposalApplicationState>('IDLE');
  proposalApplicationMessage = signal('');
  proposalAppliedVersion = signal<number | null>(null);
  mediaItems = signal<ListingImage[]>([]);
  pendingMediaItems = signal<PendingListingMedia[]>([]);
  storeContext = signal<BusinessStoreContext | null>(null);
  formSubmitted = signal(false);
  readonly maxImageCount = MAX_LISTING_IMAGE_COUNT;
  private editListingId = '';
  private currentVersion = 0;
  private lastLoadedRequestSnapshot = '';

  sellerType: ListingSellerType = 'INDIVIDUAL';
  businessId = '';
  categoryId = '';
  title = '';
  description = '';
  condition: ListingCondition = 'GOOD';
  conditionNotes = '';
  price: number | null = null;
  currency = 'USD';
  publicCity = '';
  publicRegion = '';
  negotiable = true;
  sku = '';
  quantity: number | null = 1;

  ngOnInit(): void {
    this.editListingId = this.route.snapshot.paramMap.get('listingId') || '';
    this.isEditMode.set(Boolean(this.editListingId));
    if (this.marketplaceAccountMode()) {
      this.sellerType = 'INDIVIDUAL';
      this.businessId = '';
    }
    if (this.businessStoreMode()) {
      this.sellerType = 'BUSINESS';
      this.negotiable = false;
      this.publicCity = '';
      this.publicRegion = '';
      this.loadStoreContext();
    }
    this.loadCategories();
    if (this.editListingId && !this.businessStoreMode()) {
      this.loadDraft(this.editListingId);
    }
  }

  loadCategories(): void {
    this.loadingCategories.set(true);
    this.listingService.getCategories().subscribe({
      next: categories => {
        this.categories.set(categories);
        if (!this.categoryId && categories.length > 0) {
          this.categoryId = categories[0].id;
        }
        this.loadingCategories.set(false);
      },
      error: () => {
        this.loadingCategories.set(false);
        this.errorMsg.set('Categories could not be loaded.');
      },
    });
  }

  saveDraft(): void {
    if (!this.canEditDraft()) {
      this.errorMsg.set('This listing is no longer editable.');
      return;
    }
    this.formSubmitted.set(true);
    if (!this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');
    this.savedId.set('');
    this.mediaError.set('');
    this.mediaMessage.set('');

    const request = buildListingDraftRequest(this.formState());
    const context = this.storeContext();

    const save = this.businessStoreMode()
      ? this.isEditMode()
        ? this.listingService.updateBusinessStoreItem(context?.businessId || '', this.editListingId, this.currentVersion, request)
        : this.listingService.createBusinessStoreItem(context?.businessId || '', request)
      : this.isEditMode()
        ? this.listingService.updateDraft(this.editListingId, this.currentVersion, request)
        : this.listingService.createDraft(request);

    save.subscribe({
      next: listing => {
        this.editListingId = listing.id;
        if (this.pendingMediaItems().length > 0) {
          this.currentVersion = listing.version;
          this.listingStatus.set(listing.status);
          this.savedId.set(listing.id);
          this.mediaItems.set(listing.images || []);
          this.rememberCurrentFormSnapshot();
          this.uploadPendingMediaForListing(listing.id, true);
          return;
        }

        this.populateFromDraft(listing);
        this.saving.set(false);
        this.toastService.success(this.isEditMode() ? 'Listing draft updated.' : 'Listing draft saved.');
        if (!this.isEditMode()) {
          this.router.navigate(this.editListingPath(listing.id), { queryParamsHandling: 'preserve' });
        }
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 403) {
          this.errorMsg.set('Your account does not have permission to create this listing draft.');
          return;
        }
        if (error.status === 409 && error.error?.error?.code === 'BUSINESS_SKU_CONFLICT') {
          this.errorMsg.set(
            error.error?.error?.message
              || 'This store already has an item with that SKU. Edit the existing item or use a different SKU.',
          );
          return;
        }
        if (error.status === 409) {
          this.errorMsg.set('This draft changed elsewhere. Reload it before saving again.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Listing draft could not be saved.');
      },
    });
  }

  handleMediaSelected(event: Event): void {
    if (!this.canEditDraft()) {
      this.mediaError.set(this.businessStoreMode()
        ? 'Pause an active store item before changing its images.'
        : 'Images cannot be changed in this listing state.');
      return;
    }
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    input.value = '';

    if (files.length === 0) {
      return;
    }
    const availableSlots = MAX_LISTING_IMAGE_COUNT - this.mediaItems().length - this.pendingMediaItems().length;
    if (files.length > availableSlots) {
      this.mediaError.set(`A listing can have up to ${MAX_LISTING_IMAGE_COUNT} images.`);
      return;
    }

    const accepted: PendingListingMedia[] = [];
    for (const file of files) {
      const mediaValidationError = validateSelectedListingImage(file);
      if (mediaValidationError) {
        this.mediaError.set(mediaValidationError);
        return;
      }
      accepted.push({
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        file,
        previewUrl: URL.createObjectURL(file),
      });
    }

    this.pendingMediaItems.update(items => [...items, ...accepted]);
    this.mediaError.set('');
    this.mediaMessage.set('');

    if (this.savedId()) {
      this.uploadPendingMediaForListing(this.savedId(), false);
    }
  }

  clearSelectedMedia(): void {
    this.pendingMediaItems().forEach(item => URL.revokeObjectURL(item.previewUrl));
    this.pendingMediaItems.set([]);
  }

  removePendingMedia(id: string): void {
    const item = this.pendingMediaItems().find(media => media.id === id);
    if (item) {
      URL.revokeObjectURL(item.previewUrl);
    }
    this.pendingMediaItems.update(items => items.filter(media => media.id !== id));
  }

  removeAttachedMedia(media: ListingImage): void {
    if (!this.savedId() || !this.canEditDraft()) {
      return;
    }

    const nextImages = this.mediaItems()
      .filter(item => item.id !== media.id)
      .map(item => ({
        mediaId: item.mediaObjectId,
        altText: item.altText,
      }));

    this.uploadingMedia.set(true);
    this.mediaError.set('');
    const businessId = this.businessMediaBusinessId();
    const updateImages = businessId
      ? this.listingService.updateBusinessStoreItemImages(businessId, this.savedId(), { images: nextImages })
      : this.listingService.updateListingImages(this.savedId(), { images: nextImages });

    updateImages.subscribe({
      next: images => {
        this.mediaItems.set(images);
        this.mediaMessage.set('Image removed.');
        this.uploadingMedia.set(false);
        this.refreshDraftAfterMediaChange(this.savedId());
      },
      error: error => {
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image could not be removed.');
      },
    });
  }

  canEditDraft(): boolean {
    if (this.businessStoreMode()) {
      return !this.isEditMode() || this.listingStatus() === 'DRAFT' || this.listingStatus() === 'PAUSED';
    }
    return isEditableListingStatus(this.listingStatus());
  }

  isPendingReview(): boolean {
    return this.isEditMode() && this.listingStatus() === 'PENDING_REVIEW';
  }

  isChangesRequested(): boolean {
    return this.isEditMode() && this.listingStatus() === 'CHANGES_REQUESTED';
  }

  cancelDraft(): void {
    this.router.navigate([this.listingBasePath()], { queryParamsHandling: 'preserve' });
  }

  canCloseListing(): boolean {
    if (this.businessStoreMode()) {
      return false;
    }
    return isClosableListingStatus(this.listingStatus());
  }

  canSubmitForReview(): boolean {
    if (this.businessStoreMode()) {
      return false;
    }
    return canSubmitListingForReview({
      editMode: this.isEditMode(),
      status: this.listingStatus(),
      attachedImageCount: this.mediaItems().length,
      pendingImageCount: this.pendingMediaItems().length,
      hasUnsavedChanges: this.hasUnsavedListingChanges(),
    });
  }

  submitForReview(): void {
    if (!this.editListingId) {
      return;
    }
    this.formSubmitted.set(true);
    if (!this.validate()) {
      return;
    }
    if (this.pendingMediaItems().length > 0) {
      this.errorMsg.set('Save the draft to upload selected images before submitting for review.');
      return;
    }
    if (this.mediaItems().length === 0) {
      this.errorMsg.set('Add at least one image before submitting for review.');
      return;
    }
    if (this.listingStatus() !== 'DRAFT' && !this.hasUnsavedListingChanges()) {
      this.errorMsg.set('Update the listing before resubmitting it for review.');
      return;
    }

    this.submitting.set(true);
    this.errorMsg.set('');
    if (this.listingStatus() !== 'DRAFT') {
      this.saveEditableListingBeforeSubmit();
      return;
    }

    this.submitSavedDraft(this.currentVersion);
  }

  private saveEditableListingBeforeSubmit(): void {
    const request = buildListingDraftRequest(this.formState());
    this.listingService.updateDraft(this.editListingId, this.currentVersion, request).subscribe({
      next: listing => {
        this.populateFromDraft(listing);
        this.submitSavedDraft(listing.version);
      },
      error: error => {
        this.submitting.set(false);
        if (error.status === 409) {
          this.errorMsg.set('This listing changed elsewhere. Reload it before submitting.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Listing could not be saved before review.');
      },
    });
  }

  private submitSavedDraft(version: number): void {
    this.listingService.submitForReview(this.editListingId, version).subscribe({
      next: listing => {
        this.submitting.set(false);
        this.populateFromDraft(listing);
        this.toastService.success('Listing submitted for review.');
      },
      error: error => {
        this.submitting.set(false);
        if (error.status === 409) {
          this.errorMsg.set('This draft changed elsewhere. Reload it before submitting.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Listing could not be submitted for review.');
      },
    });
  }

  closeListing(): void {
    if (!this.editListingId || !this.canCloseListing()) {
      return;
    }

    this.submitting.set(true);
    this.errorMsg.set('');
    this.listingService.closeListing(this.editListingId, this.currentVersion).subscribe({
      next: listing => {
        this.submitting.set(false);
        this.populateFromDraft(listing);
        this.toastService.success('Listing closed.');
      },
      error: error => {
        this.submitting.set(false);
        if (error.status === 409) {
          this.errorMsg.set('This listing changed elsewhere. Reload it before closing.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Listing could not be closed.');
      },
    });
  }

  canPublishStoreItem(): boolean {
    if (!this.businessStoreMode() || !this.editListingId) {
      return false;
    }
    if (this.mediaItems().length === 0 || this.pendingMediaItems().length > 0) {
      return false;
    }
    return !this.hasUnsavedListingChanges();
  }

  publishStoreItem(): void {
    this.runStorePublicationAction('publish');
  }

  pauseStoreItem(): void {
    this.runStorePublicationAction('pause');
  }

  relistStoreItem(): void {
    this.runStorePublicationAction('relist');
  }

  private runStorePublicationAction(actionName: 'publish' | 'pause' | 'relist'): void {
    const businessId = this.businessMediaBusinessId();
    if (!businessId || !this.editListingId) {
      this.errorMsg.set('An approved business store is required.');
      return;
    }
    if (actionName !== 'pause' && !this.canPublishStoreItem()) {
      this.errorMsg.set(this.hasUnsavedListingChanges()
        ? 'Save changes before publishing this store item.'
        : 'Add at least one image before publishing this store item.');
      return;
    }
    if (actionName === 'pause' && this.hasUnsavedListingChanges()) {
      this.errorMsg.set('Save or discard changes before pausing this store item.');
      return;
    }

    const action = actionName === 'publish'
      ? this.listingService.publishBusinessStoreItem(businessId, this.editListingId, this.currentVersion)
      : actionName === 'pause'
        ? this.listingService.pauseBusinessStoreItem(businessId, this.editListingId, this.currentVersion)
        : this.listingService.relistBusinessStoreItem(businessId, this.editListingId, this.currentVersion);

    this.submitting.set(true);
    this.errorMsg.set('');
    action.subscribe({
      next: listing => {
        this.submitting.set(false);
        this.populateFromDraft(listing);
        const message = actionName === 'publish'
          ? 'Store item published.'
          : actionName === 'pause'
            ? 'Store item paused.'
            : 'Store item relisted.';
        this.toastService.success(message);
      },
      error: error => {
        this.submitting.set(false);
        if (error.status === 409) {
          this.errorMsg.set('This store item changed elsewhere. Reload it before continuing.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Store item status could not be changed.');
      },
    });
  }

  private uploadPendingMediaForListing(listingId: string, draftJustCreated: boolean): void {
    const pendingItems = this.pendingMediaItems();
    if (pendingItems.length === 0) {
      this.saving.set(false);
      return;
    }

    this.uploadingMedia.set(true);
    this.mediaError.set('');
    this.mediaMessage.set('');

    this.mediaUploadService.uploadAndAttachMany(
      listingId,
      pendingItems.map(item => item.file),
      this.mediaItems(),
      this.businessMediaBusinessId(),
    ).subscribe({
      next: attachedImages => this.finishMediaUpload(listingId, attachedImages, draftJustCreated),
      error: error => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaError.set(this.mediaUploadErrorMessage(error));
      },
    });
  }

  private finishMediaUpload(listingId: string, attachedImages: ListingImage[], draftJustCreated: boolean): void {
    this.saving.set(false);
    this.uploadingMedia.set(false);
    this.mediaItems.set(attachedImages);
    this.mediaMessage.set('Images attached to listing.');
    this.clearSelectedMedia();
    this.refreshDraftAfterMediaChange(listingId);
    this.toastService.success(draftJustCreated ? 'Listing draft and images saved.' : 'Listing images saved.');
    if (draftJustCreated && !this.isEditMode()) {
      this.router.navigate(this.editListingPath(listingId), { queryParamsHandling: 'preserve' });
    }
  }

  imageUrl(image: ListingImage): string {
    return this.listingService.mediaUrl(image.url || image.uploadUrl);
  }

  mediaStatusLabel(status: string): string {
    return status
      .toLowerCase()
      .split('_')
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  mediaStatusClass(status: string): string {
    if (['UPLOADED', 'APPROVED'].includes(status)) {
      return 'status-good';
    }
    if (['FAILED', 'REJECTED', 'CHANGES_REQUESTED'].includes(status)) {
      return 'status-bad';
    }
    return 'status-warn';
  }

  fieldInvalid(field: string): boolean {
    if (!this.formSubmitted()) {
      return false;
    }
    if (field === 'categoryId') {
      return !this.categoryId;
    }
    if (field === 'title') {
      return !this.title.trim();
    }
    if (field === 'description') {
      return !this.description.trim();
    }
    if (field === 'price') {
      return this.price === null || Number(this.price) < 0;
    }
    if (field === 'currency') {
      return !/^[A-Za-z]{3}$/.test(this.currency.trim());
    }
    if (field === 'quantity') {
      return this.quantity === null || Number(this.quantity) < 1;
    }
    return false;
  }

  marketplaceAccountMode(): boolean {
    return this.router.url.startsWith('/account/listings') || this.router.url.startsWith('/listings');
  }

  businessStoreMode(): boolean {
    return this.router.url.startsWith('/seller/store/items');
  }

  /** Restricts the optional proposal entry point to editable owned individual drafts. */
  proposalReviewEligible(): boolean {
    return this.marketplaceAccountMode()
      && !this.businessStoreMode()
      && this.isEditMode()
      && Boolean(this.savedId())
      && ['DRAFT', 'CHANGES_REQUESTED'].includes(this.listingStatus());
  }

  proposalReviewListingVersion(): number {
    return this.currentVersion;
  }

  /** Applies only seller-confirmed proposal fields through the existing owner-scoped Product PATCH. */
  applyListingProposal(command: ListingProposalApplicationCommand): void {
    this.proposalApplicationMessage.set('');
    this.proposalAppliedVersion.set(null);
    if (!this.listingProposalReviewEnabled
      || !this.proposalReviewEligible()
      || this.saving()
      || this.submitting()) {
      this.failProposalApplication('The listing is not available for proposal application.');
      return;
    }
    if (command.listingId !== this.editListingId
      || command.sourceListingVersion !== this.currentVersion) {
      this.proposalApplicationState.set('CONFLICT');
      this.proposalApplicationMessage.set(
        'The listing version changed. Reload and compare the current draft; no proposal fields were applied.',
      );
      return;
    }
    if (this.hasUnsavedListingChanges()) {
      this.failProposalApplication(
        'Save or discard ordinary editor changes first so only confirmed proposal fields are updated.',
      );
      return;
    }

    const fieldNames = Object.keys(command.fields);
    if (fieldNames.length < 1
      || fieldNames.some(name => !['title', 'description', 'categoryId'].includes(name))) {
      this.failProposalApplication('Choose at least one supported proposal field.');
      return;
    }

    const nextState = { ...this.formState() };
    if (Object.prototype.hasOwnProperty.call(command.fields, 'title')) {
      const title = command.fields.title?.trim() || '';
      if (title.length < 1 || title.length > 160) {
        this.failProposalApplication('The confirmed title is not valid.');
        return;
      }
      nextState.title = title;
    }
    if (Object.prototype.hasOwnProperty.call(command.fields, 'description')) {
      const description = command.fields.description?.trim() || '';
      if (description.length < 1 || description.length > 5_000) {
        this.failProposalApplication('The confirmed description is not valid.');
        return;
      }
      nextState.description = description;
    }
    if (Object.prototype.hasOwnProperty.call(command.fields, 'categoryId')) {
      const categoryId = command.fields.categoryId || '';
      if (!this.categories().some(category => category.id === categoryId)) {
        this.failProposalApplication('Choose a current category from the listing editor.');
        return;
      }
      nextState.categoryId = categoryId;
    }

    const validation = validateListingDraftForm(nextState);
    if (!validation.valid) {
      this.failProposalApplication(validation.message);
      return;
    }

    this.proposalApplicationState.set('APPLYING');
    this.saving.set(true);
    this.listingService.updateDraft(
      this.editListingId,
      command.sourceListingVersion,
      buildListingDraftRequest(nextState),
    ).subscribe({
      next: listing => {
        this.populateFromDraft(listing);
        this.saving.set(false);
        this.proposalAppliedVersion.set(listing.version);
        this.proposalApplicationState.set('APPLIED');
        this.proposalApplicationMessage.set(
          'Product accepted the confirmed fields. The listing remains an editable draft.',
        );
        this.toastService.success('Selected proposal fields applied to the listing draft.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 409) {
          this.proposalApplicationState.set('CONFLICT');
          this.proposalApplicationMessage.set(
            'The listing changed before confirmation completed. Reload and compare the current draft; no automatic retry occurred.',
          );
          return;
        }
        this.failProposalApplication(
          'Product could not update the listing. Nothing is marked applied; review the draft and try again later.',
        );
      },
    });
  }

  /** Maps only attached image display data and Product-owned media IDs into the Agent review boundary. */
  proposalReviewMedia(): ListingProposalReviewMedia[] {
    const allowedMimes = new Set(['image/jpeg', 'image/png', 'image/webp']);
    const allowedModeration = new Set(['NOT_SUBMITTED', 'CHANGES_REQUESTED', 'APPROVED']);
    return this.mediaItems().map(image => ({
      mediaId: image.mediaObjectId,
      imageUrl: this.imageUrl(image),
      altText: image.altText || image.originalFileName || 'Listing image',
      eligible: image.uploadStatus === 'UPLOADED'
        && allowedMimes.has(image.contentType.toLowerCase())
        && allowedModeration.has(image.moderationStatus),
    }));
  }

  private failProposalApplication(message: string): void {
    this.proposalApplicationState.set('FAILED');
    this.proposalApplicationMessage.set(message);
  }

  // Keeps paused business item copy distinct from pre-publication drafts.
  saveButtonLabel(): string {
    if (this.saving()) {
      return 'Saving';
    }
    if (!this.isEditMode()) {
      return 'Save draft';
    }
    if (!this.businessStoreMode() && this.listingStatus() === 'ACTIVE') {
      return 'Update active listing';
    }
    if (!this.businessStoreMode() && this.listingStatus() === 'CLOSED') {
      return 'Update closed listing';
    }
    if (!this.businessStoreMode() && this.listingStatus() === 'CHANGES_REQUESTED') {
      return 'Save requested changes';
    }
    if (this.businessStoreMode() && this.listingStatus() === 'PAUSED') {
      return 'Update item';
    }
    return 'Update draft';
  }

  pageTitle(): string {
    if (this.businessStoreMode()) {
      return this.isEditMode() ? 'Edit Store Item' : 'New Store Item';
    }
    if (this.isPendingReview()) {
      return 'Pending review';
    }
    if (this.isChangesRequested()) {
      return 'Update requested changes';
    }
    if (this.isEditMode() && this.listingStatus() === 'ACTIVE') {
      return 'Edit Active Listing';
    }
    if (this.isEditMode() && this.listingStatus() === 'CLOSED') {
      return 'Edit Closed Listing';
    }
    return this.isEditMode() ? 'Edit Listing Draft' : 'New Listing Draft';
  }

  pageDescription(): string {
    if (this.businessStoreMode()) {
      return this.isEditMode()
        ? 'Update this business item while it is draft or paused.'
        : 'Create a business item for your approved catalog.';
    }
    if (this.isPendingReview()) {
      return 'Your listing is locked while an admin reviews the listing details and uploaded images.';
    }
    if (this.isChangesRequested()) {
      return 'Review the admin feedback, update your listing details or images, and submit it again.';
    }
    if (this.isEditMode() && this.listingStatus() === 'ACTIVE') {
      return 'This listing is approved and public. Save changes and submit them for review to update the public listing.';
    }
    if (this.isEditMode() && this.listingStatus() === 'CLOSED') {
      return 'This listing is closed. Save changes and submit it for review to make it public again.';
    }
    return this.isEditMode()
      ? 'Update your marketplace draft before review.'
      : 'Build a marketplace draft, add photos, and submit it for review.';
  }

  // Keeps individual listing draft navigation inside the marketplace account surface.
  listingBasePath(): string {
    if (this.businessStoreMode()) {
      return '/seller/store/items';
    }
    return '/account/listings';
  }

  // Builds the edit route used after draft creation and media attachment.
  private editListingPath(listingId: string): string[] {
    return [this.listingBasePath(), listingId, 'edit'];
  }

  private validate(): boolean {
    if (this.marketplaceAccountMode()) {
      this.sellerType = 'INDIVIDUAL';
      this.businessId = '';
      this.sku = '';
    }
    if (this.businessStoreMode()) {
      const context = this.storeContext();
      if (!context) {
        this.errorMsg.set('An approved business store is required before creating item drafts.');
        return false;
      }
      this.sellerType = 'BUSINESS';
      this.businessId = context.businessId;
      this.negotiable = false;
      this.publicCity = '';
      this.publicRegion = '';
    }
    const result = validateListingDraftForm(this.formState());
    if (!result.valid) {
      this.errorMsg.set(result.message);
      return false;
    }

    return true;
  }

  private loadDraft(listingId: string): void {
    this.loadingDraft.set(true);
    this.listingService.getListing(listingId).subscribe({
      next: listing => {
        this.populateFromDraft(listing);
        this.loadingDraft.set(false);
      },
      error: error => {
        this.loadingDraft.set(false);
        this.errorMsg.set(error.error?.error?.message || 'Listing draft could not be loaded.');
      },
    });
  }

  private loadStoreContext(): void {
    this.loadingStoreContext.set(true);
    this.businessStoreService.getCurrentStoreContext().subscribe({
      next: context => {
        this.loadingStoreContext.set(false);
        this.storeContext.set(context);
        if (!context) {
          this.errorMsg.set('An approved business store is required before creating item drafts.');
          return;
        }
        this.sellerType = 'BUSINESS';
        this.businessId = context.businessId;
        if (this.editListingId) {
          this.loadBusinessStoreItem(context.businessId, this.editListingId);
        }
      },
      error: () => {
        this.loadingStoreContext.set(false);
        this.errorMsg.set('Business store context could not be loaded.');
      },
    });
  }

  private loadBusinessStoreItem(businessId: string, listingId: string): void {
    this.loadingDraft.set(true);
    this.listingService.getBusinessStoreItem(businessId, listingId).subscribe({
      next: listing => {
        this.populateFromDraft(listing);
        this.loadingDraft.set(false);
      },
      error: error => {
        this.loadingDraft.set(false);
        this.errorMsg.set(error.error?.error?.message || 'Store item draft could not be loaded.');
      },
    });
  }

  private populateFromDraft(listing: ListingDraft): void {
    const state = listingDraftToFormState(listing);
    this.savedId.set(listing.id);
    this.currentVersion = listing.version;
    this.listingStatus.set(listing.status);
    this.moderationReason.set(listing.moderationReason || null);
    this.applyFormState(state);
    this.mediaItems.set(listing.images || []);
    this.clearSelectedMedia();
    this.formSubmitted.set(false);
    this.rememberCurrentFormSnapshot();
  }

  private formState(): ListingDraftFormState {
    const context = this.storeContext();
    return {
      sellerType: this.marketplaceAccountMode() ? 'INDIVIDUAL' : (this.businessStoreMode() ? 'BUSINESS' : this.sellerType),
      businessId: this.marketplaceAccountMode() ? '' : (this.businessStoreMode() ? (context?.businessId || this.businessId) : this.businessId),
      categoryId: this.categoryId,
      title: this.title,
      description: this.description,
      condition: this.condition,
      conditionNotes: this.conditionNotes,
      price: this.price,
      currency: this.currency,
      publicCity: this.businessStoreMode() ? '' : this.publicCity,
      publicRegion: this.businessStoreMode() ? '' : this.publicRegion,
      negotiable: this.businessStoreMode() ? false : this.negotiable,
      sku: this.sku,
      quantity: this.quantity,
    };
  }

  private applyFormState(state: ListingDraftFormState): void {
    this.sellerType = this.marketplaceAccountMode() ? 'INDIVIDUAL' : (this.businessStoreMode() ? 'BUSINESS' : state.sellerType);
    this.businessId = this.marketplaceAccountMode() ? '' : (this.businessStoreMode() ? (this.storeContext()?.businessId || state.businessId) : state.businessId);
    this.categoryId = state.categoryId;
    this.title = state.title;
    this.description = state.description;
    this.condition = state.condition;
    this.conditionNotes = state.conditionNotes;
    this.price = state.price;
    this.currency = state.currency;
    this.publicCity = this.businessStoreMode() ? '' : state.publicCity;
    this.publicRegion = this.businessStoreMode() ? '' : state.publicRegion;
    this.negotiable = this.businessStoreMode() ? false : state.negotiable;
    this.sku = state.sku;
    this.quantity = state.quantity || 1;
  }

  private refreshDraftAfterMediaChange(listingId: string): void {
    const businessId = this.businessMediaBusinessId();
    const refresh = businessId
      ? this.listingService.getBusinessStoreItem(businessId, listingId)
      : this.listingService.getListing(listingId);
    refresh.subscribe({
      next: listing => {
        this.currentVersion = listing.version;
        this.listingStatus.set(listing.status);
        this.mediaItems.set(listing.images || this.mediaItems());
        this.rememberCurrentFormSnapshot();
      },
      error: () => {
        this.errorMsg.set('Listing changed, but the latest version could not be refreshed.');
      },
    });
  }

  hasUnsavedListingChanges(): boolean {
    return this.formRequestSnapshot() !== this.lastLoadedRequestSnapshot;
  }

  private rememberCurrentFormSnapshot(): void {
    this.lastLoadedRequestSnapshot = this.formRequestSnapshot();
  }

  private formRequestSnapshot(): string {
    return listingDraftRequestSnapshot(this.formState());
  }

  private businessMediaBusinessId(): string | undefined {
    if (!this.businessStoreMode()) {
      return undefined;
    }
    return this.storeContext()?.businessId || this.businessId || undefined;
  }

  private mediaUploadErrorMessage(error: unknown): string {
    if (!(error instanceof ListingMediaUploadError)) {
      return 'Image upload could not be completed.';
    }
    const originalError = error.originalError as { error?: { error?: { message?: string } } } | undefined;
    if (error.step === 'request') {
      return originalError?.error?.error?.message || 'Image upload could not be requested.';
    }
    if (error.step === 'upload') {
      return 'Image bytes could not be uploaded to storage.';
    }
    if (error.step === 'confirm') {
      return originalError?.error?.error?.message || 'Image upload could not be confirmed.';
    }
    return originalError?.error?.error?.message || 'Image could not be attached to the draft.';
  }
}
