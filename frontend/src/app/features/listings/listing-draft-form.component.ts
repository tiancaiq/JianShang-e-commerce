import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Category, ListingCondition, ListingDraft, ListingImage, ListingMedia, ListingSellerType } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-listing-draft-form',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="listing-page">
      <header class="page-header">
        <div>
          <h1>{{ isEditMode() ? 'Edit Listing Draft' : 'New Listing Draft' }}</h1>
          <p>{{ isEditMode() ? 'Edit a saved draft before moderation.' : 'Save a draft for later review. Publishing and search are not enabled yet.' }}</p>
        </div>
      </header>

      <form class="draft-form" (ngSubmit)="saveDraft()">
        <div class="form-grid">
          <label class="field">
            <span>Seller type</span>
            <select name="sellerType" [(ngModel)]="sellerType" [disabled]="!canEditDraft() || saving() || isEditMode()">
              <option value="INDIVIDUAL">Individual</option>
              <option value="BUSINESS">Business</option>
            </select>
          </label>

          @if (sellerType === 'BUSINESS') {
            <label class="field">
              <span>Business ID</span>
              <input name="businessId" [(ngModel)]="businessId" maxlength="26" [disabled]="!canEditDraft() || saving() || isEditMode()" />
            </label>
          }

          <label class="field">
            <span>Category</span>
            <select name="categoryId" [(ngModel)]="categoryId" [disabled]="!canEditDraft() || loadingCategories() || saving()">
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
          <input name="title" [(ngModel)]="title" maxlength="160" [disabled]="!canEditDraft() || saving()" />
        </label>

        <label class="field">
          <span>Description</span>
          <textarea name="description" [(ngModel)]="description" maxlength="5000" rows="5" [disabled]="!canEditDraft() || saving()"></textarea>
        </label>

        <label class="field">
          <span>Condition notes</span>
          <textarea name="conditionNotes" [(ngModel)]="conditionNotes" maxlength="1000" rows="3" [disabled]="!canEditDraft() || saving()"></textarea>
        </label>

        <div class="form-grid">
          <label class="field">
            <span>Price</span>
            <input name="price" type="number" min="0" step="0.01" [(ngModel)]="price" [disabled]="!canEditDraft() || saving()" />
          </label>

          <label class="field">
            <span>Currency</span>
            <input name="currency" [(ngModel)]="currency" maxlength="3" [disabled]="!canEditDraft() || saving()" />
          </label>
        </div>

        @if (sellerType === 'INDIVIDUAL') {
          <div class="form-grid">
            <label class="field">
              <span>Public city</span>
              <input name="publicCity" [(ngModel)]="publicCity" maxlength="120" [disabled]="!canEditDraft() || saving()" />
            </label>

            <label class="field">
              <span>Public region</span>
              <input name="publicRegion" [(ngModel)]="publicRegion" maxlength="120" [disabled]="!canEditDraft() || saving()" />
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
              <input name="quantity" type="number" min="0" step="1" [(ngModel)]="quantity" [disabled]="!canEditDraft() || saving()" />
            </label>
          </div>
        }

        <section class="media-panel" aria-label="Listing media">
          <label class="field">
            <span>Listing image</span>
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              [disabled]="!canEditDraft() || saving() || uploadingMedia()"
              (change)="handleMediaSelected($event)"
            />
          </label>

          @if (selectedMediaName()) {
            <div class="media-selection">
              <span>{{ selectedMediaName() }}</span>
              <button type="button" class="text-btn" (click)="clearSelectedMedia()" [disabled]="!canEditDraft() || saving() || uploadingMedia()">
                Remove
              </button>
            </div>
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
                  <strong>{{ media.displayOrder + 1 }}</strong>
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

        @if (isEditMode() && !canEditDraft()) {
          <div class="success-message">Listing is {{ listingStatus() }} and locked for draft edits.</div>
        }

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="router.navigate(['/seller/listings'])" [disabled]="saving() || submitting()">Cancel</button>
          @if (isEditMode()) {
            <button type="button" class="secondary-btn" (click)="submitForReview()" [disabled]="saving() || submitting()">
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
    .listing-page {
      max-width: 960px;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .page-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
    }

    .page-header p {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    .draft-form {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      max-width: 760px;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      padding: 1.25rem;
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
      color: var(--color-text-secondary);
    }

    .field input,
    .field select,
    .field textarea {
      width: 100%;
      min-height: 64px;
      padding: 0.625rem 0.75rem;
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-text-primary);
      font-size: 0.875rem;
      outline: none;
    }

    .field textarea {
      resize: vertical;
    }

    .field input:focus,
    .field select:focus,
    .field textarea:focus {
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-muted);
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
      border-top: 1px solid var(--color-border);
      padding-top: 1rem;
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
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      font-size: 0.8125rem;
    }

    .media-list span {
      overflow-wrap: anywhere;
      color: var(--color-text-secondary);
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
      background: var(--color-bg-tertiary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
    }

    .media-selection span {
      overflow-wrap: anywhere;
    }

    .text-btn {
      border: 0;
      background: transparent;
      color: var(--color-accent);
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
    .secondary-btn {
      min-height: 40px;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      font-weight: 700;
      cursor: pointer;
    }

    .primary-btn {
      border: 1px solid transparent;
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .secondary-btn {
      border: 1px solid var(--color-border);
      background: var(--color-bg-tertiary);
      color: var(--color-text-secondary);
    }

    .primary-btn:disabled,
    .secondary-btn:disabled {
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
  private static readonly MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
  private static readonly ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

  private listingService = inject(ListingService);
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
  selectedMediaName = signal('');
  private selectedMediaFile: File | null = null;
  private editListingId = '';
  private currentVersion = 0;

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
  quantity: number | null = 0;

  ngOnInit(): void {
    this.editListingId = this.route.snapshot.paramMap.get('listingId') || '';
    this.isEditMode.set(Boolean(this.editListingId));
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
      this.errorMsg.set('This listing is no longer editable as a draft.');
      return;
    }
    if (!this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');
    this.savedId.set('');
    this.mediaError.set('');
    this.mediaMessage.set('');

    const request = {
      sellerType: this.sellerType,
      businessId: this.sellerType === 'BUSINESS' ? this.businessId.trim() : null,
      categoryId: this.categoryId,
      title: this.title.trim(),
      description: this.description.trim(),
      condition: this.condition,
      conditionNotes: this.conditionNotes.trim() || null,
      price: {
        amount: Number(this.price),
        currency: this.currency.trim().toUpperCase(),
      },
      negotiable: this.sellerType === 'INDIVIDUAL' ? this.negotiable : false,
      location: this.sellerType === 'INDIVIDUAL'
        ? { city: this.publicCity.trim() || null, region: this.publicRegion.trim() || null }
        : null,
      sku: this.sellerType === 'BUSINESS' ? this.sku.trim() : null,
      quantity: this.sellerType === 'BUSINESS' ? Number(this.quantity) : 1,
    };

    const save = this.isEditMode()
      ? this.listingService.updateDraft(this.editListingId, this.currentVersion, request)
      : this.listingService.createDraft(request);

    save.subscribe({
      next: listing => {
        this.editListingId = listing.id;
        this.currentVersion = listing.version;
        this.savedId.set(listing.id);
        if (this.selectedMediaFile) {
          this.uploadMediaForListing(listing.id, this.selectedMediaFile, true);
          return;
        }

        this.saving.set(false);
        this.toastService.success(this.isEditMode() ? 'Listing draft updated.' : 'Listing draft saved.');
        if (!this.isEditMode()) {
          this.router.navigate(['/seller/listings', listing.id, 'edit']);
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
      this.mediaError.set('Images can be changed only while the listing is a draft.');
      return;
    }
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    input.value = '';

    if (!file) {
      return;
    }
    if (!ListingDraftFormComponent.ALLOWED_IMAGE_TYPES.has(file.type)) {
      this.mediaError.set('Use a JPEG, PNG, or WebP image.');
      return;
    }
    if (file.size <= 0 || file.size > ListingDraftFormComponent.MAX_IMAGE_SIZE_BYTES) {
      this.mediaError.set('Image must be 10 MB or less.');
      return;
    }

    this.selectedMediaFile = file;
    this.selectedMediaName.set(file.name);
    this.mediaError.set('');
    this.mediaMessage.set('');

    if (this.savedId()) {
      this.uploadMediaForListing(this.savedId(), file, false);
    }
  }

  clearSelectedMedia(): void {
    this.selectedMediaFile = null;
    this.selectedMediaName.set('');
  }

  canEditDraft(): boolean {
    return this.listingStatus() === 'DRAFT';
  }

  submitForReview(): void {
    if (!this.editListingId) {
      return;
    }
    if (this.mediaItems().length === 0) {
      this.errorMsg.set('Add at least one image before submitting for review.');
      return;
    }

    this.submitting.set(true);
    this.errorMsg.set('');
    this.listingService.submitForReview(this.editListingId, this.currentVersion).subscribe({
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

  private uploadMediaForListing(listingId: string, file: File, draftJustCreated: boolean): void {
    this.uploadingMedia.set(true);
    this.mediaError.set('');
    this.mediaMessage.set('');

    this.listingService.requestMediaUpload(listingId, {
      contentType: file.type,
      fileName: file.name,
      sizeBytes: file.size,
    }).subscribe({
      next: media => this.uploadMediaBytes(listingId, file, media, draftJustCreated),
      error: error => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image upload could not be requested.');
      },
    });
  }

  private uploadMediaBytes(listingId: string, file: File, media: ListingMedia, draftJustCreated: boolean): void {
    this.listingService.uploadMediaFile(media.uploadUrl, file).subscribe({
      next: () => this.confirmMedia(listingId, file, media, draftJustCreated),
      error: () => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaError.set('Image bytes could not be uploaded to storage.');
      },
    });
  }

  private confirmMedia(listingId: string, file: File, media: ListingMedia, draftJustCreated: boolean): void {
    this.listingService.confirmMediaUpload(listingId, media.id, {
      sizeBytes: file.size,
    }).subscribe({
      next: confirmed => {
        this.attachConfirmedMedia(listingId, confirmed, draftJustCreated);
      },
      error: error => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image upload could not be confirmed.');
      },
    });
  }

  private attachConfirmedMedia(listingId: string, media: ListingMedia, draftJustCreated: boolean): void {
    const images = [
      ...this.mediaItems().map(item => ({
        mediaId: item.mediaObjectId,
        altText: item.altText,
      })),
      {
        mediaId: media.id,
        altText: media.originalFileName,
      },
    ];

    this.listingService.updateListingImages(listingId, { images }).subscribe({
      next: attachedImages => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaItems.set(attachedImages);
        this.mediaMessage.set('Image attached to draft.');
        this.clearSelectedMedia();
        this.toastService.success(draftJustCreated ? 'Listing draft and image saved.' : 'Listing image saved.');
        if (draftJustCreated && !this.isEditMode()) {
          this.router.navigate(['/seller/listings', listingId, 'edit']);
        }
      },
      error: error => {
        this.saving.set(false);
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image could not be attached to the draft.');
      },
    });
  }

  imageUrl(image: ListingImage): string {
    return this.listingService.mediaUrl(image.url || image.uploadUrl);
  }

  private validate(): boolean {
    if (!this.categoryId) {
      this.errorMsg.set('Category is required.');
      return false;
    }
    if (!this.title.trim()) {
      this.errorMsg.set('Title is required.');
      return false;
    }
    if (!this.description.trim()) {
      this.errorMsg.set('Description is required.');
      return false;
    }
    if (this.price === null || Number(this.price) < 0) {
      this.errorMsg.set('Price must be zero or greater.');
      return false;
    }
    if (!/^[A-Za-z]{3}$/.test(this.currency.trim())) {
      this.errorMsg.set('Currency must be a 3-letter code.');
      return false;
    }
    if (this.sellerType === 'BUSINESS') {
      if (this.businessId.trim().length !== 26) {
        this.errorMsg.set('Business ID is required.');
        return false;
      }
      if (!this.sku.trim()) {
        this.errorMsg.set('SKU is required for business listings.');
        return false;
      }
      if (this.quantity === null || Number(this.quantity) < 0) {
        this.errorMsg.set('Quantity must be zero or greater.');
        return false;
      }
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
    this.savedId.set(listing.id);
    this.currentVersion = listing.version;
    this.listingStatus.set(listing.status);
    this.sellerType = listing.sellerType;
    this.businessId = listing.businessId || '';
    this.categoryId = listing.categoryId;
    this.title = listing.title;
    this.description = listing.description;
    this.condition = listing.condition;
    this.conditionNotes = listing.conditionNotes || '';
    this.price = Number(listing.priceAmount);
    this.currency = listing.currency;
    this.publicCity = listing.publicCity || '';
    this.publicRegion = listing.publicRegion || '';
    this.negotiable = listing.negotiable;
    this.sku = listing.sku || '';
    this.quantity = listing.quantity;
    this.mediaItems.set(listing.images || []);
  }
}
