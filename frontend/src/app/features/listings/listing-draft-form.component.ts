import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Category, ListingCondition, ListingDraft, ListingImage, ListingSellerType } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';
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
  imports: [FormsModule],
  template: `
    <section class="listing-page">
      <header class="page-header">
        <div>
          <h1>{{ isEditMode() ? 'Edit Listing Draft' : 'New Listing Draft' }}</h1>
          <p>{{ isEditMode() ? 'Update your marketplace draft before review.' : 'Build a marketplace draft, add photos, and submit it for review.' }}</p>
        </div>
      </header>

      <form class="draft-form" (ngSubmit)="saveDraft()">
        <div class="form-grid">
          @if (!marketplaceAccountMode()) {
            <label class="field">
              <span>Seller type</span>
              <select name="sellerType" [(ngModel)]="sellerType" [disabled]="!canEditDraft() || saving() || isEditMode()">
                <option value="INDIVIDUAL">Individual</option>
                <option value="BUSINESS">Business</option>
              </select>
            </label>
          }

          @if (sellerType === 'BUSINESS') {
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
                  <span>{{ media.originalFileName || media.objectKey }}</span>
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

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="router.navigate([listingBasePath()])" [disabled]="saving() || submitting()">Cancel</button>
          @if (isEditMode() && canCloseListing()) {
            <button type="button" class="danger-btn" (click)="closeListing()" [disabled]="saving() || submitting() || uploadingMedia()">
              Close listing
            </button>
          }
          @if (isEditMode() && canEditDraft()) {
            <button type="button" class="secondary-btn" (click)="submitForReview()" [disabled]="!canSubmitForReview() || saving() || submitting()">
              {{ submitting() ? 'Submitting' : 'Submit for review' }}
            </button>
          }
          @if (canEditDraft()) {
            <button type="submit" class="primary-btn" [disabled]="saving() || submitting() || loadingCategories()">
              {{ saving() ? 'Saving' : (isEditMode() ? 'Update draft' : 'Save draft') }}
            </button>
          }
        </div>
      </form>
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

    .media-list span {
      flex: 1;
      overflow-wrap: anywhere;
      color: var(--listing-muted);
    }

    .media-list strong {
      flex: 0 0 auto;
      color: var(--color-success);
      font-size: 0.75rem;
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
  private mediaUploadService = inject(ListingMediaUploadService);
  private toastService = inject(ToastService);
  private route = inject(ActivatedRoute);
  router = inject(Router);

  categories = signal<Category[]>([]);
  loadingCategories = signal(false);
  loadingDraft = signal(false);
  saving = signal(false);
  submitting = signal(false);
  isEditMode = signal(false);
  listingStatus = signal('DRAFT');
  errorMsg = signal('');
  savedId = signal('');
  uploadingMedia = signal(false);
  mediaError = signal('');
  mediaMessage = signal('');
  mediaItems = signal<ListingImage[]>([]);
  pendingMediaItems = signal<PendingListingMedia[]>([]);
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
    this.loadCategories();
    if (this.editListingId) {
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

    const save = this.isEditMode()
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
          this.router.navigate(this.editListingPath(listing.id));
        }
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 403) {
          this.errorMsg.set('Your account does not have permission to create this listing draft.');
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
      this.mediaError.set('Images can be changed only while the listing is draft, pending review, or active.');
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
    this.listingService.updateListingImages(this.savedId(), { images: nextImages }).subscribe({
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
    return isEditableListingStatus(this.listingStatus());
  }

  canCloseListing(): boolean {
    return isClosableListingStatus(this.listingStatus());
  }

  canSubmitForReview(): boolean {
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

  private uploadPendingMediaForListing(listingId: string, draftJustCreated: boolean): void {
    const pendingItems = this.pendingMediaItems();
    if (pendingItems.length === 0) {
      this.saving.set(false);
      return;
    }

    this.uploadingMedia.set(true);
    this.mediaError.set('');
    this.mediaMessage.set('');

    this.mediaUploadService.uploadAndAttachMany(listingId, pendingItems.map(item => item.file), this.mediaItems()).subscribe({
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
      this.router.navigate(this.editListingPath(listingId));
    }
  }

  imageUrl(image: ListingImage): string {
    return this.listingService.mediaUrl(image.url || image.uploadUrl);
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

  // Keeps individual listing draft navigation inside the marketplace account surface.
  listingBasePath(): string {
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

  private populateFromDraft(listing: ListingDraft): void {
    const state = listingDraftToFormState(listing);
    this.savedId.set(listing.id);
    this.currentVersion = listing.version;
    this.listingStatus.set(listing.status);
    this.applyFormState(state);
    this.mediaItems.set(listing.images || []);
    this.clearSelectedMedia();
    this.formSubmitted.set(false);
    this.rememberCurrentFormSnapshot();
  }

  private formState(): ListingDraftFormState {
    return {
      sellerType: this.marketplaceAccountMode() ? 'INDIVIDUAL' : this.sellerType,
      businessId: this.marketplaceAccountMode() ? '' : this.businessId,
      categoryId: this.categoryId,
      title: this.title,
      description: this.description,
      condition: this.condition,
      conditionNotes: this.conditionNotes,
      price: this.price,
      currency: this.currency,
      publicCity: this.publicCity,
      publicRegion: this.publicRegion,
      negotiable: this.negotiable,
      sku: this.sku,
      quantity: this.quantity,
    };
  }

  private applyFormState(state: ListingDraftFormState): void {
    this.sellerType = this.marketplaceAccountMode() ? 'INDIVIDUAL' : state.sellerType;
    this.businessId = this.marketplaceAccountMode() ? '' : state.businessId;
    this.categoryId = state.categoryId;
    this.title = state.title;
    this.description = state.description;
    this.condition = state.condition;
    this.conditionNotes = state.conditionNotes;
    this.price = state.price;
    this.currency = state.currency;
    this.publicCity = state.publicCity;
    this.publicRegion = state.publicRegion;
    this.negotiable = state.negotiable;
    this.sku = state.sku;
    this.quantity = state.quantity || 1;
  }

  private refreshDraftAfterMediaChange(listingId: string): void {
    this.listingService.getListing(listingId).subscribe({
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

  private hasUnsavedListingChanges(): boolean {
    return this.formRequestSnapshot() !== this.lastLoadedRequestSnapshot;
  }

  private rememberCurrentFormSnapshot(): void {
    this.lastLoadedRequestSnapshot = this.formRequestSnapshot();
  }

  private formRequestSnapshot(): string {
    return listingDraftRequestSnapshot(this.formState());
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
