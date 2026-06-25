import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Category, ListingCondition, ListingMedia, ListingSellerType } from '../../core/models/listing.model';
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
          <h1>New Listing Draft</h1>
          <p>Save a draft for later review. Publishing and search are not enabled yet.</p>
        </div>
      </header>

      <form class="draft-form" (ngSubmit)="saveDraft()">
        <div class="form-grid">
          <label class="field">
            <span>Seller type</span>
            <select name="sellerType" [(ngModel)]="sellerType" [disabled]="saving()">
              <option value="INDIVIDUAL">Individual</option>
              <option value="BUSINESS">Business</option>
            </select>
          </label>

          @if (sellerType === 'BUSINESS') {
            <label class="field">
              <span>Business ID</span>
              <input name="businessId" [(ngModel)]="businessId" maxlength="26" [disabled]="saving()" />
            </label>
          }

          <label class="field">
            <span>Category</span>
            <select name="categoryId" [(ngModel)]="categoryId" [disabled]="loadingCategories() || saving()">
              <option value="">Select category</option>
              @for (category of categories(); track category.id) {
                <option [value]="category.id">{{ category.name }}</option>
              }
            </select>
          </label>

          <label class="field">
            <span>Condition</span>
            <select name="condition" [(ngModel)]="condition" [disabled]="saving()">
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
          <input name="title" [(ngModel)]="title" maxlength="160" [disabled]="saving()" />
        </label>

        <label class="field">
          <span>Description</span>
          <textarea name="description" [(ngModel)]="description" maxlength="5000" rows="5" [disabled]="saving()"></textarea>
        </label>

        <label class="field">
          <span>Condition notes</span>
          <textarea name="conditionNotes" [(ngModel)]="conditionNotes" maxlength="1000" rows="3" [disabled]="saving()"></textarea>
        </label>

        <div class="form-grid">
          <label class="field">
            <span>Price</span>
            <input name="price" type="number" min="0" step="0.01" [(ngModel)]="price" [disabled]="saving()" />
          </label>

          <label class="field">
            <span>Currency</span>
            <input name="currency" [(ngModel)]="currency" maxlength="3" [disabled]="saving()" />
          </label>
        </div>

        @if (sellerType === 'INDIVIDUAL') {
          <div class="form-grid">
            <label class="field">
              <span>Public city</span>
              <input name="publicCity" [(ngModel)]="publicCity" maxlength="120" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Public region</span>
              <input name="publicRegion" [(ngModel)]="publicRegion" maxlength="120" [disabled]="saving()" />
            </label>
          </div>

          <label class="checkbox-row">
            <input type="checkbox" name="negotiable" [(ngModel)]="negotiable" [disabled]="saving()" />
            <span>Price is negotiable</span>
          </label>
        } @else {
          <div class="form-grid">
            <label class="field">
              <span>SKU</span>
              <input name="sku" [(ngModel)]="sku" maxlength="64" [disabled]="saving()" />
            </label>

            <label class="field">
              <span>Quantity</span>
              <input name="quantity" type="number" min="0" step="1" [(ngModel)]="quantity" [disabled]="saving()" />
            </label>
          </div>
        }

        @if (errorMsg()) {
          <div class="error-message">{{ errorMsg() }}</div>
        }

        @if (savedId()) {
          <div class="success-message">Draft saved: {{ savedId() }}</div>

          <section class="media-panel" aria-label="Listing media">
            <label class="field">
              <span>Listing image</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                [disabled]="uploadingMedia()"
                (change)="handleMediaSelected($event)"
              />
            </label>

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
                    <span>{{ media.originalFileName || media.objectKey }}</span>
                    <strong>{{ media.uploadStatus }}</strong>
                  </li>
                }
              </ul>
            }
          </section>
        }

        <div class="actions">
          <button type="button" class="secondary-btn" (click)="router.navigate(['/dashboard'])" [disabled]="saving()">Cancel</button>
          <button type="submit" class="primary-btn" [disabled]="saving() || loadingCategories()">
            {{ saving() ? 'Saving' : 'Save draft' }}
          </button>
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
      min-height: 40px;
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
      min-height: 40px;
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
  router = inject(Router);

  categories = signal<Category[]>([]);
  loadingCategories = signal(false);
  saving = signal(false);
  errorMsg = signal('');
  savedId = signal('');
  uploadingMedia = signal(false);
  mediaError = signal('');
  mediaMessage = signal('');
  mediaItems = signal<ListingMedia[]>([]);

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
    this.loadCategories();
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
    if (!this.validate()) {
      return;
    }

    this.saving.set(true);
    this.errorMsg.set('');
    this.savedId.set('');
    this.mediaError.set('');
    this.mediaMessage.set('');
    this.mediaItems.set([]);

    this.listingService.createDraft({
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
    }).subscribe({
      next: listing => {
        this.saving.set(false);
        this.savedId.set(listing.id);
        this.toastService.success('Listing draft saved.');
      },
      error: error => {
        this.saving.set(false);
        if (error.status === 403) {
          this.errorMsg.set('Your account does not have permission to create this listing draft.');
          return;
        }
        this.errorMsg.set(error.error?.error?.message || 'Listing draft could not be saved.');
      },
    });
  }

  handleMediaSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0);
    input.value = '';

    if (!file) {
      return;
    }
    if (!this.savedId()) {
      this.mediaError.set('Save the draft before adding an image.');
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

    this.uploadingMedia.set(true);
    this.mediaError.set('');
    this.mediaMessage.set('');

    this.listingService.requestMediaUpload(this.savedId(), {
      contentType: file.type,
      fileName: file.name,
      sizeBytes: file.size,
    }).subscribe({
      next: media => this.confirmMedia(file, media),
      error: error => {
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image upload could not be requested.');
      },
    });
  }

  private confirmMedia(file: File, media: ListingMedia): void {
    this.listingService.confirmMediaUpload(this.savedId(), media.id, {
      sizeBytes: file.size,
    }).subscribe({
      next: confirmed => {
        this.uploadingMedia.set(false);
        this.mediaItems.update(items => [confirmed, ...items]);
        this.mediaMessage.set('Image metadata saved.');
        this.toastService.success('Listing image saved.');
      },
      error: error => {
        this.uploadingMedia.set(false);
        this.mediaError.set(error.error?.error?.message || 'Image upload could not be confirmed.');
      },
    });
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
}
