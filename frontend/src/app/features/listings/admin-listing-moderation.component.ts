import { Component, OnInit, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ListingDraft, ListingModerationDecision } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ToastService } from '../../core/services/toast.service';

@Component({
  selector: 'app-admin-listing-moderation',
  standalone: true,
  imports: [DecimalPipe, FormsModule],
  template: `
    <section class="moderation-page">
      <header class="page-header">
        <div>
          <h1>Listing Review</h1>
          <p>Review submitted listing drafts before public marketplace visibility.</p>
        </div>
        <button type="button" class="secondary-btn" (click)="loadQueue()" [disabled]="loading()">Refresh</button>
      </header>

      @if (errorMsg()) {
        <div class="error-message">{{ errorMsg() }}</div>
      }

      @if (loading()) {
        <div class="empty-state">Loading listing review queue...</div>
      } @else if (listings().length === 0) {
        <div class="empty-state">No listings are waiting for review.</div>
      } @else {
        <div class="listing-stack">
          @for (listing of listings(); track listing.id) {
            <article class="listing-panel">
              <div class="listing-summary">
                <div>
                  <div class="eyebrow">{{ listing.sellerType }} - {{ listing.condition }}</div>
                  <h2>{{ listing.title }}</h2>
                  <p>{{ listing.description }}</p>
                </div>
                <div class="price-box">
                  <strong>{{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}</strong>
                  <span>{{ listing.negotiable ? 'Negotiable' : 'Fixed price' }}</span>
                </div>
              </div>

              <dl class="meta-grid">
                <div>
                  <dt>Listing ID</dt>
                  <dd>{{ listing.id }}</dd>
                </div>
                <div>
                  <dt>Status</dt>
                  <dd>{{ listing.status }} / {{ listing.moderationStatus }}</dd>
                </div>
                <div>
                  <dt>Category</dt>
                  <dd>{{ listing.categoryId }}</dd>
                </div>
                <div>
                  <dt>Location</dt>
                  <dd>{{ listing.publicCity || 'Not set' }}{{ listing.publicRegion ? ', ' + listing.publicRegion : '' }}</dd>
                </div>
                @if (listing.sku) {
                  <div>
                    <dt>SKU</dt>
                    <dd>{{ listing.sku }}</dd>
                  </div>
                }
                <div>
                  <dt>Quantity</dt>
                  <dd>{{ listing.quantity }}</dd>
                </div>
              </dl>

              @if (listing.images?.length) {
                <div class="media-list" aria-label="Submitted images">
                  @for (image of listing.images; track image.id) {
                    <div class="media-row">
                      <span>{{ image.displayOrder + 1 }}</span>
                      <strong>{{ image.originalFileName || image.mediaObjectId }}</strong>
                      <small>{{ image.uploadStatus }} / {{ image.moderationStatus }}</small>
                    </div>
                  }
                </div>
              }

              <label class="field">
                <span>Decision reason</span>
                <textarea
                  [name]="'reason-' + listing.id"
                  [ngModel]="reasonFor(listing.id)"
                  (ngModelChange)="setReason(listing.id, $event)"
                  maxlength="1000"
                  rows="4"
                  [disabled]="savingId() === listing.id"
                ></textarea>
              </label>

              <div class="decision-bar">
                <button type="button" class="approve-btn" (click)="decide(listing, 'APPROVE')" [disabled]="savingId() === listing.id">
                  Approve
                </button>
                <button type="button" class="secondary-btn" (click)="decide(listing, 'REQUEST_CHANGES')" [disabled]="savingId() === listing.id">
                  Request changes
                </button>
                <button type="button" class="danger-btn" (click)="decide(listing, 'REJECT')" [disabled]="savingId() === listing.id">
                  Reject
                </button>
              </div>
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .moderation-page {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      max-width: 1120px;
    }

    .page-header,
    .listing-summary,
    .decision-bar {
      display: flex;
      gap: 1rem;
    }

    .page-header,
    .listing-summary {
      justify-content: space-between;
      align-items: flex-start;
    }

    h1,
    h2 {
      margin: 0;
      color: var(--color-text-primary);
      font-family: var(--font-display);
      letter-spacing: 0;
    }

    h1 {
      font-size: 1.75rem;
    }

    h2 {
      font-size: 1.25rem;
    }

    p {
      margin: 0.35rem 0 0;
      color: var(--color-text-secondary);
      line-height: 1.5;
    }

    .listing-stack {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .listing-panel,
    .empty-state,
    .error-message {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
      padding: 1.25rem;
    }

    .listing-panel {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .eyebrow {
      color: var(--color-info);
      font-size: 0.75rem;
      font-weight: 800;
      letter-spacing: 0.04em;
    }

    .price-box {
      min-width: 160px;
      text-align: right;
    }

    .price-box strong {
      display: block;
      color: var(--color-text-primary);
      font-size: 1rem;
    }

    .price-box span,
    .empty-state {
      color: var(--color-text-secondary);
    }

    .meta-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.85rem;
      margin: 0;
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

    .media-list {
      display: grid;
      gap: 0.5rem;
    }

    .media-row {
      display: grid;
      grid-template-columns: 32px minmax(0, 1fr) auto;
      align-items: center;
      gap: 0.75rem;
      min-height: 44px;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      padding: 0.55rem 0.75rem;
      color: var(--color-text-primary);
    }

    .media-row span {
      color: var(--color-accent);
      font-weight: 800;
    }

    .media-row small {
      color: var(--color-text-muted);
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .field span {
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    textarea {
      width: 100%;
      min-height: 108px;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      background: var(--color-bg-primary);
      color: var(--color-text-primary);
      padding: 0.75rem 0.85rem;
      resize: vertical;
      font: inherit;
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

    .approve-btn {
      background: var(--color-success);
      color: #07110b;
    }

    .secondary-btn {
      border: 1px solid var(--color-border);
      background: var(--color-bg-secondary);
      color: var(--color-text-primary);
    }

    .danger-btn {
      background: var(--color-danger);
      color: #16070a;
    }

    .error-message {
      color: var(--color-danger);
      border-color: rgba(244, 63, 94, 0.45);
      background: rgba(244, 63, 94, 0.08);
    }

    @media (max-width: 820px) {
      .page-header,
      .listing-summary,
      .decision-bar {
        flex-direction: column;
      }

      .price-box {
        text-align: left;
      }

      .meta-grid {
        grid-template-columns: 1fr;
      }

      .media-row {
        grid-template-columns: 28px minmax(0, 1fr);
      }

      .media-row small {
        grid-column: 2;
      }
    }
  `],
})
export class AdminListingModerationComponent implements OnInit {
  private readonly listingService = inject(ListingService);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);

  listings = signal<ListingDraft[]>([]);
  loading = signal(false);
  savingId = signal('');
  errorMsg = signal('');

  private reasons: Record<string, string> = {};

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.loading.set(true);
    this.errorMsg.set('');

    this.listingService.getPendingModerationListings().subscribe({
      next: listings => {
        this.listings.set(listings);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        this.handleError(error, 'Listing review queue could not be loaded.');
      },
    });
  }

  reasonFor(listingId: string): string {
    return this.reasons[listingId] || '';
  }

  setReason(listingId: string, reason: string): void {
    this.reasons[listingId] = reason;
  }

  decide(listing: ListingDraft, decision: ListingModerationDecision): void {
    const reason = this.reasonFor(listing.id).trim().replace(/\s+/g, ' ');
    if (!reason) {
      this.errorMsg.set('Decision reason is required.');
      return;
    }

    this.savingId.set(listing.id);
    this.errorMsg.set('');

    this.listingService.decideListing(listing.id, listing.version, { decision, reason }).subscribe({
      next: () => {
        this.listings.update(items => items.filter(item => item.id !== listing.id));
        delete this.reasons[listing.id];
        this.savingId.set('');
        this.toastService.success('Listing moderation decision saved.');
      },
      error: error => {
        this.savingId.set('');
        this.handleError(error, 'Listing moderation decision could not be saved.');
      },
    });
  }

  private handleError(error: { status?: number }, fallback: string): void {
    if (error.status === 401) {
      this.router.navigate(['/login']);
      return;
    }
    if (error.status === 403) {
      this.errorMsg.set('You need platform admin access for this action.');
      return;
    }
    if (error.status === 404) {
      this.errorMsg.set('Listing was not found.');
      return;
    }
    if (error.status === 409) {
      this.errorMsg.set('Listing changed while you were reviewing it. Refresh the queue and try again.');
      return;
    }
    this.errorMsg.set(fallback);
  }
}
