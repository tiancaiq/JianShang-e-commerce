import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  AdminListingModerationCaseDetail,
  ListingImage,
  ListingCondition,
  ListingModerationDecision,
} from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-admin-listing-moderation-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink],
  template: `
    <section class="listing-review-detail">
      <header class="page-header">
        <div>
          <a routerLink="/admin/listings/moderation">Listing Review</a>
          <h1>{{ detail()?.listing?.title || 'Listing review case' }}</h1>
        </div>

        @if (detail(); as data) {
          <div class="header-meta">
            <span class="status-pill">{{ data.moderationCase.caseStatus }}</span>
            <span>Case v{{ data.moderationCase.version }}</span>
            <span>Listing v{{ data.listing.version }}</span>
          </div>
        }
      </header>

      @if (loading()) {
        <div class="state-panel">Loading listing review case...</div>
      } @else if (errorMsg()) {
        <div class="state-panel error">
          <span>{{ errorMsg() }}</span>
          <button type="button" class="secondary-btn" (click)="loadCase()">Retry</button>
        </div>
      } @else if (detail(); as data) {
        <div class="detail-grid">
          <section class="detail-section detail-section-wide">
            <div class="section-header">
              <div>
                <h2>{{ data.listing.title }}</h2>
                <p>{{ data.listing.status }} / {{ data.listing.moderationStatus }}</p>
              </div>
              <strong>{{ data.listing.priceAmount | number: '1.2-2' }} {{ data.listing.currency }}</strong>
            </div>
            <p class="description">{{ data.listing.description }}</p>
          </section>

          <section class="detail-section">
            <h2>Case</h2>
            <dl>
              <div>
                <dt>Case ID</dt>
                <dd>{{ data.moderationCase.id }}</dd>
              </div>
              <div>
                <dt>Submitted by</dt>
                <dd class="identity-value">
                  <span>{{ data.moderationCase.sellerDisplayName || data.moderationCase.sellerId || data.moderationCase.submittedByUserId }}</span>
                  @if (data.moderationCase.sellerDisplayName) {
                    <small>{{ data.moderationCase.sellerId || data.moderationCase.submittedByUserId }}</small>
                  }
                </dd>
              </div>
              <div>
                <dt>Assigned admin</dt>
                <dd>{{ data.moderationCase.assignedAdminDisplayName || data.moderationCase.assignedAdminUserId || 'Unassigned' }}</dd>
              </div>
              <div>
                <dt>Submitted</dt>
                <dd>{{ data.moderationCase.createdAt | date: 'medium' }}</dd>
              </div>
              <div>
                <dt>Resolved</dt>
                <dd>{{ data.moderationCase.resolvedAt ? (data.moderationCase.resolvedAt | date: 'medium') : 'Not resolved' }}</dd>
              </div>
            </dl>
          </section>

          <section class="detail-section">
            <h2>Listing</h2>
            <dl>
              <div>
                <dt>Listing ID</dt>
                <dd>{{ data.listing.id }}</dd>
              </div>
              <div>
                <dt>Seller type</dt>
                <dd>{{ data.listing.sellerType }}</dd>
              </div>
              <div>
                <dt>Condition</dt>
                <dd>{{ data.listing.condition }}</dd>
              </div>
              <div>
                <dt>Location</dt>
                <dd>{{ locationFor(data) }}</dd>
              </div>
              <div>
                <dt>Quantity</dt>
                <dd>{{ data.listing.quantity }}</dd>
              </div>
              @if (data.listing.sku) {
                <div>
                  <dt>SKU</dt>
                  <dd>{{ data.listing.sku }}</dd>
                </div>
              }
            </dl>
          </section>

          <section class="detail-section detail-section-wide">
            <h2>Images</h2>
            @if ((data.listing.images || []).length === 0) {
              <p>No images attached.</p>
            } @else {
              <div class="image-grid">
                @for (image of data.listing.images || []; track image.id) {
                  <article class="image-tile">
                    <div class="image-preview">
                      @if (image.url) {
                        <img [src]="listingService.mediaUrl(image.url)" [alt]="image.altText || image.originalFileName || 'Listing image'">
                      }
                    </div>
                    <strong>{{ image.altText || image.originalFileName || 'Listing image' }}</strong>
                    <span>{{ image.moderationStatus }} / {{ image.uploadStatus }}</span>
                  </article>
                }
              </div>
            }
          </section>

          <section class="detail-section">
            <h2>Decision History</h2>
            @if (data.decisions.length === 0) {
              <p>No decisions yet.</p>
            } @else {
              <div class="history-stack">
                @for (decision of data.decisions; track decision.id) {
                  <article>
                    <strong>{{ decision.decision }}</strong>
                    <p>{{ decision.reason }}</p>
                    <small>{{ decision.createdAt | date: 'medium' }}</small>
                  </article>
                }
              </div>
            }
          </section>

          @if (canResolve(data)) {
            <section class="detail-section">
              <h2>Resolution</h2>
              <form class="decision-form" (ngSubmit)="resolveCase()">
                <label>
                  <span>Decision</span>
                  <select name="decision" [(ngModel)]="decision" [disabled]="saving()">
                    <option value="APPROVE">Approve</option>
                    <option value="REJECT">Reject</option>
                    <option value="REQUEST_CHANGES">Request changes</option>
                  </select>
                </label>

                <label>
                  <span>Reason</span>
                  <textarea
                    name="reason"
                    data-testid="listing-decision-reason"
                    [(ngModel)]="reason"
                    rows="4"
                    maxlength="1000"
                    [disabled]="saving()"
                    [class.invalid-field]="decisionErrorMsg()"
                    [attr.aria-invalid]="decisionErrorMsg() ? 'true' : 'false'"
                  ></textarea>
                </label>

                @if (decisionErrorMsg()) {
                  <div class="decision-error">{{ decisionErrorMsg() }}</div>
                }

                <button type="submit" class="primary-btn" [disabled]="saving()">
                  {{ saving() ? 'Resolving case' : 'Resolve case' }}
                </button>
              </form>
            </section>
          } @else {
            <section class="detail-section">
              <h2>Review status</h2>
              <p>{{ readOnlyMessage(data) }}</p>
            </section>
          }

          @if (canManageActiveListing(data)) {
            <section class="detail-section detail-section-wide">
              <h2>Active listing actions</h2>
              <form class="active-listing-form" (ngSubmit)="saveActiveListing()">
                <label>
                  <span>Category ID</span>
                  <input name="active-category" [(ngModel)]="activeEdit.categoryId" [disabled]="savingActiveEdit()">
                </label>
                <label>
                  <span>Title</span>
                  <input name="active-title" [(ngModel)]="activeEdit.title" [disabled]="savingActiveEdit()" maxlength="160">
                </label>
                <label>
                  <span>Description</span>
                  <textarea name="active-description" [(ngModel)]="activeEdit.description" [disabled]="savingActiveEdit()" rows="4" maxlength="5000"></textarea>
                </label>
                <label>
                  <span>Condition</span>
                  <select name="active-condition" [(ngModel)]="activeEdit.condition" [disabled]="savingActiveEdit()">
                    @for (condition of conditionOptions; track condition) {
                      <option [value]="condition">{{ condition }}</option>
                    }
                  </select>
                </label>
                <label>
                  <span>Condition notes</span>
                  <input name="active-condition-notes" [(ngModel)]="activeEdit.conditionNotes" [disabled]="savingActiveEdit()" maxlength="1000">
                </label>
                <label>
                  <span>Price</span>
                  <input name="active-price" type="number" step="0.01" min="0" [(ngModel)]="activeEdit.priceAmount" [disabled]="savingActiveEdit()">
                </label>
                @if (data.listing.sellerType === 'INDIVIDUAL') {
                  <label>
                    <span>Public city</span>
                    <input name="active-city" [(ngModel)]="activeEdit.publicCity" [disabled]="savingActiveEdit()" maxlength="120">
                  </label>
                  <label>
                    <span>Public region</span>
                    <input name="active-region" [(ngModel)]="activeEdit.publicRegion" [disabled]="savingActiveEdit()" maxlength="120">
                  </label>
                  <label class="checkbox-row">
                    <input name="active-negotiable" type="checkbox" [(ngModel)]="activeEdit.negotiable" [disabled]="savingActiveEdit()">
                    <span>Negotiable</span>
                  </label>
                } @else {
                  <label>
                    <span>SKU</span>
                    <input name="active-sku" [(ngModel)]="activeEdit.sku" [disabled]="savingActiveEdit()" maxlength="64">
                  </label>
                  <label>
                    <span>Quantity</span>
                    <input name="active-quantity" type="number" min="0" step="1" [(ngModel)]="activeEdit.quantity" [disabled]="savingActiveEdit()">
                  </label>
                }
                <label class="form-wide">
                  <span>Edit reason</span>
                  <textarea
                    name="active-edit-reason"
                    data-testid="active-edit-reason"
                    [(ngModel)]="activeEditReason"
                    [disabled]="savingActiveEdit()"
                    rows="3"
                    maxlength="1000"
                  ></textarea>
                </label>

                <button type="submit" class="primary-btn" [disabled]="savingActiveEdit()">
                  {{ savingActiveEdit() ? 'Saving active listing' : 'Save active listing' }}
                </button>
              </form>

              <div class="remove-panel">
                <label>
                  <span>Remove reason</span>
                  <textarea
                    name="active-remove-reason"
                    data-testid="active-remove-reason"
                    [(ngModel)]="activeRemoveReason"
                    [disabled]="removingActiveListing()"
                    rows="3"
                    maxlength="1000"
                  ></textarea>
                </label>
                <button type="button" class="danger-btn" (click)="removeActiveListing()" [disabled]="removingActiveListing()">
                  {{ removingActiveListing() ? 'Removing listing' : 'Remove from marketplace' }}
                </button>
              </div>

              @if (activeActionErrorMsg()) {
                <div class="decision-error">{{ activeActionErrorMsg() }}</div>
              }
            </section>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .listing-review-detail {
      display: grid;
      gap: 1rem;
      max-width: 1120px;
    }

    .page-header,
    .section-header,
    .state-panel {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: flex-start;
    }

    .page-header a {
      color: var(--color-accent);
      font-size: 0.8125rem;
      font-weight: 800;
      text-decoration: none;
    }

    h1,
    h2 {
      margin: 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      letter-spacing: 0;
    }

    h1 {
      margin-top: 0.25rem;
      font-size: 1.55rem;
    }

    h2 {
      font-size: 1rem;
    }

    p {
      margin: 0.3rem 0 0;
      color: var(--color-text-secondary);
      line-height: 1.5;
    }

    .header-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      color: var(--color-text-muted);
      font-weight: 800;
    }

    .status-pill {
      color: var(--color-success);
    }

    .detail-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .detail-section,
    .state-panel {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-secondary);
      padding: 1rem;
    }

    .detail-section-wide {
      grid-column: 1 / -1;
    }

    dl {
      display: grid;
      gap: 0.65rem;
      margin: 0.85rem 0 0;
    }

    dt {
      color: var(--color-text-muted);
      font-size: 0.75rem;
      margin-bottom: 0.2rem;
    }

    dd {
      margin: 0;
      color: var(--color-text-primary);
      overflow-wrap: anywhere;
    }

    .identity-value {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
    }

    .identity-value small,
    .history-stack small,
    .image-tile span {
      color: var(--color-text-muted);
      font-size: 0.75rem;
    }

    .description {
      max-width: 72ch;
    }

    .image-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 0.75rem;
      margin-top: 0.85rem;
    }

    .image-tile {
      display: grid;
      gap: 0.45rem;
      min-width: 0;
    }

    .image-preview {
      display: grid;
      place-items: center;
      aspect-ratio: 4 / 3;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      overflow: hidden;
    }

    .image-preview img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .history-stack,
    .decision-form,
    .active-listing-form {
      display: grid;
      gap: 0.8rem;
      margin-top: 0.85rem;
    }

    .active-listing-form {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .decision-form label,
    .active-listing-form label,
    .remove-panel label {
      display: grid;
      gap: 0.35rem;
      color: var(--color-text-secondary);
      font-weight: 800;
    }

    .form-wide {
      grid-column: 1 / -1;
    }

    .checkbox-row {
      grid-template-columns: auto 1fr;
      align-items: center;
      align-content: center;
    }

    .checkbox-row input {
      width: auto;
    }

    input,
    select,
    textarea {
      width: 100%;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      padding: 0.7rem;
      font: inherit;
    }

    .invalid-field {
      border-color: var(--color-danger);
    }

    .decision-error,
    .error {
      color: var(--color-danger);
    }

    button {
      min-height: 40px;
      border: 0;
      border-radius: var(--radius-md);
      padding: 0.65rem 0.95rem;
      font: inherit;
      font-weight: 800;
      cursor: pointer;
    }

    button:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .primary-btn {
      background: var(--color-accent);
      color: #07111f;
    }

    .secondary-btn {
      border: 1px solid var(--color-border);
      background: var(--color-bg-secondary);
      color: var(--color-text-primary);
    }

    .danger-btn {
      border: 1px solid rgba(244, 63, 94, 0.55);
      background: rgba(244, 63, 94, 0.12);
      color: var(--color-danger);
    }

    .remove-panel {
      display: grid;
      gap: 0.75rem;
      margin-top: 1rem;
      border-top: 1px solid var(--color-border);
      padding-top: 1rem;
    }

    @media (max-width: 900px) {
      .detail-grid {
        grid-template-columns: 1fr;
      }

      .active-listing-form {
        grid-template-columns: 1fr;
      }

      .page-header,
      .section-header,
      .state-panel {
        flex-direction: column;
      }
    }
  `],
})
export class AdminListingModerationDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly listingService = inject(ListingService);
  private readonly toastService = inject(ToastService);

  readonly detail = signal<AdminListingModerationCaseDetail | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly savingActiveEdit = signal(false);
  readonly removingActiveListing = signal(false);
  readonly errorMsg = signal('');
  readonly decisionErrorMsg = signal('');
  readonly activeActionErrorMsg = signal('');

  readonly conditionOptions: ListingCondition[] = ['NEW', 'OPEN_BOX', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS'];
  decision: ListingModerationDecision = 'APPROVE';
  reason = '';
  activeEdit = {
    categoryId: '',
    title: '',
    description: '',
    condition: 'GOOD' as ListingCondition,
    conditionNotes: '',
    priceAmount: 0,
    negotiable: false,
    publicCity: '',
    publicRegion: '',
    sku: '',
    quantity: 1,
  };
  activeEditReason = '';
  activeRemoveReason = '';

  ngOnInit(): void {
    this.loadCase();
  }

  loadCase(): void {
    const caseId = this.caseId();
    if (!caseId) {
      this.errorMsg.set('Listing moderation case could not be loaded.');
      return;
    }

    this.loading.set(true);
    this.errorMsg.set('');
    this.listingService.getListingModerationCaseDetail(caseId).subscribe({
      next: detail => {
        this.detail.set(detail);
        this.populateActiveEdit(detail);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        this.handleLoadError(error);
      },
    });
  }

  resolveCase(): void {
    const current = this.detail();
    if (!current || !this.canResolve(current)) {
      this.decisionErrorMsg.set('Only claimed pending cases can be resolved.');
      return;
    }

    const reason = this.reason.trim();
    if (!reason) {
      this.decisionErrorMsg.set('Decision reason is required.');
      return;
    }

    const decisionLabel = this.decision.toLowerCase().replace('_', ' ');
    if (!window.confirm(`Confirm ${decisionLabel} decision for ${current.listing.title}?`)) {
      return;
    }

    this.saving.set(true);
    this.decisionErrorMsg.set('');
    this.listingService.resolveListingModerationCase(current.moderationCase.id, current.moderationCase.version, {
      decision: this.decision,
      reason,
    }).subscribe({
      next: detail => {
        this.detail.set(detail);
        this.populateActiveEdit(detail);
        this.saving.set(false);
        this.reason = '';
        this.toastService.success('Listing moderation case resolved.');
      },
      error: error => {
        this.saving.set(false);
        this.handleDecisionError(error);
      },
    });
  }

  canResolve(detail: AdminListingModerationCaseDetail): boolean {
    return detail.moderationCase.caseStatus === 'CLAIMED'
      && detail.listing.status === 'PENDING_REVIEW'
      && detail.listing.moderationStatus === 'PENDING';
  }

  canManageActiveListing(detail: AdminListingModerationCaseDetail): boolean {
    return detail.listing.status === 'ACTIVE' && detail.listing.moderationStatus === 'APPROVED';
  }

  saveActiveListing(): void {
    const current = this.detail();
    if (!current || !this.canManageActiveListing(current)) {
      this.activeActionErrorMsg.set('Only active approved listings can be edited.');
      return;
    }

    const reason = this.activeEditReason.trim();
    if (!reason) {
      this.activeActionErrorMsg.set('Edit reason is required.');
      return;
    }
    if (!this.activeEdit.title.trim() || !this.activeEdit.description.trim() || !this.activeEdit.categoryId.trim()) {
      this.activeActionErrorMsg.set('Title, description, and category are required.');
      return;
    }
    if (this.activeEdit.priceAmount < 0) {
      this.activeActionErrorMsg.set('Price must be zero or greater.');
      return;
    }
    if (!window.confirm(`Save changes to active listing ${current.listing.title}?`)) {
      return;
    }

    this.savingActiveEdit.set(true);
    this.activeActionErrorMsg.set('');
    this.listingService.updateActiveListingByAdmin(current.listing.id, current.listing.version, {
      categoryId: this.activeEdit.categoryId.trim(),
      title: this.activeEdit.title.trim(),
      description: this.activeEdit.description.trim(),
      condition: this.activeEdit.condition,
      conditionNotes: this.activeEdit.conditionNotes.trim() || null,
      price: {
        amount: Number(this.activeEdit.priceAmount),
        currency: current.listing.currency,
      },
      negotiable: current.listing.sellerType === 'INDIVIDUAL' ? this.activeEdit.negotiable : false,
      location: current.listing.sellerType === 'INDIVIDUAL'
        ? {
            city: this.activeEdit.publicCity.trim(),
            region: this.activeEdit.publicRegion.trim(),
          }
        : null,
      sku: current.listing.sellerType === 'BUSINESS' ? this.activeEdit.sku.trim() : null,
      quantity: current.listing.sellerType === 'BUSINESS' ? Number(this.activeEdit.quantity) : 1,
      reason,
    }).subscribe({
      next: listing => {
        this.updateListingInDetail(listing);
        this.savingActiveEdit.set(false);
        this.activeEditReason = '';
        this.toastService.success('Active listing updated.');
      },
      error: error => {
        this.savingActiveEdit.set(false);
        this.handleActiveActionError(error);
      },
    });
  }

  removeActiveListing(): void {
    const current = this.detail();
    if (!current || !this.canManageActiveListing(current)) {
      this.activeActionErrorMsg.set('Only active approved listings can be removed.');
      return;
    }

    const reason = this.activeRemoveReason.trim();
    if (!reason) {
      this.activeActionErrorMsg.set('Remove reason is required.');
      return;
    }
    if (!window.confirm(`Remove ${current.listing.title} from the public marketplace?`)) {
      return;
    }

    this.removingActiveListing.set(true);
    this.activeActionErrorMsg.set('');
    this.listingService.removeActiveListingByAdmin(current.listing.id, current.listing.version, { reason }).subscribe({
      next: listing => {
        this.updateListingInDetail(listing);
        this.removingActiveListing.set(false);
        this.activeRemoveReason = '';
        this.toastService.success('Listing removed from marketplace.');
      },
      error: error => {
        this.removingActiveListing.set(false);
        this.handleActiveActionError(error);
      },
    });
  }

  readOnlyMessage(detail: AdminListingModerationCaseDetail): string {
    if (detail.moderationCase.caseStatus === 'RESOLVED') {
      return 'This case is read-only because it is already resolved.';
    }
    if (detail.moderationCase.caseStatus !== 'CLAIMED') {
      return 'Claim this case from the queue before resolving it.';
    }
    return 'This case is read-only because the listing is no longer pending review.';
  }

  locationFor(detail: AdminListingModerationCaseDetail): string {
    if (!detail.listing.publicCity && !detail.listing.publicRegion) {
      return 'Location not set';
    }
    return [detail.listing.publicCity, detail.listing.publicRegion].filter(Boolean).join(', ');
  }

  imageLabel(image: ListingImage): string {
    return image.altText || image.originalFileName || image.id;
  }

  private caseId(): string {
    return this.route.snapshot.paramMap.get('caseId') || '';
  }

  private handleLoadError(error: { status?: number }): void {
    if (error.status === 401) {
      this.router.navigate(['/login']);
      return;
    }
    if (error.status === 403) {
      this.errorMsg.set('You need platform admin access for this page.');
      return;
    }
    this.errorMsg.set('Listing moderation case could not be loaded.');
  }

  private handleDecisionError(error: { status?: number }): void {
    if (error.status === 401) {
      this.router.navigate(['/login']);
      return;
    }
    if (error.status === 403) {
      this.decisionErrorMsg.set('You need platform admin access for this action.');
      return;
    }
    if (error.status === 409) {
      this.decisionErrorMsg.set('The case changed. Refresh and try again.');
      return;
    }
    if (error.status === 400) {
      this.decisionErrorMsg.set('Only pending-review listings can receive a decision.');
      return;
    }
    this.decisionErrorMsg.set('Listing moderation case could not be resolved.');
  }

  private populateActiveEdit(detail: AdminListingModerationCaseDetail): void {
    this.activeEdit = {
      categoryId: detail.listing.categoryId,
      title: detail.listing.title,
      description: detail.listing.description,
      condition: detail.listing.condition,
      conditionNotes: detail.listing.conditionNotes || '',
      priceAmount: detail.listing.priceAmount,
      negotiable: detail.listing.negotiable,
      publicCity: detail.listing.publicCity || '',
      publicRegion: detail.listing.publicRegion || '',
      sku: detail.listing.sku || '',
      quantity: detail.listing.quantity,
    };
  }

  private updateListingInDetail(listing: AdminListingModerationCaseDetail['listing']): void {
    this.detail.update(current => current
      ? {
          ...current,
          listing,
          moderationCase: {
            ...current.moderationCase,
            listingStatus: listing.status,
            listingModerationStatus: listing.moderationStatus,
            title: listing.title,
            priceAmount: listing.priceAmount,
            currency: listing.currency,
            publicCity: listing.publicCity,
            publicRegion: listing.publicRegion,
            sku: listing.sku,
            quantity: listing.quantity,
          },
        }
      : current);
    const updated = this.detail();
    if (updated) {
      this.populateActiveEdit(updated);
    }
  }

  private handleActiveActionError(error: { status?: number }): void {
    if (error.status === 401) {
      this.router.navigate(['/login']);
      return;
    }
    if (error.status === 403) {
      this.activeActionErrorMsg.set('You need platform admin access for this action.');
      return;
    }
    if (error.status === 409) {
      this.activeActionErrorMsg.set('The listing changed. Refresh and try again.');
      return;
    }
    if (error.status === 400) {
      this.activeActionErrorMsg.set('Only active approved listings can use this action.');
      return;
    }
    this.activeActionErrorMsg.set('Active listing action could not be completed.');
  }
}
