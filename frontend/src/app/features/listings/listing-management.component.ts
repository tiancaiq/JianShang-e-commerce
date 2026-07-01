import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ListingDraft } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { EmptyStateComponent } from '../../shared/components/ui/empty-state.component';
import { StatusPillComponent } from '../../shared/components/ui/status-pill.component';

@Component({
  selector: 'app-listing-management',
  standalone: true,
  imports: [EmptyStateComponent, RouterLink, StatusPillComponent],
  template: `
    <section class="listing-page">
      <header class="page-header">
        <div>
          <p class="eyebrow">Marketplace account</p>
          <h1>My Listings</h1>
          <p>Create, edit, and submit your marketplace listings for review.</p>
        </div>
        <a [routerLink]="newListingLink()" class="primary-btn">New listing</a>
      </header>

      @if (loading()) {
        <app-ui-empty-state>Loading listings...</app-ui-empty-state>
      } @else if (errorMsg()) {
        <div class="error-message">{{ errorMsg() }}</div>
      } @else if (listings().length === 0) {
        <app-ui-empty-state>
          <h2>No drafts yet</h2>
          <p>Start with an individual listing draft, add photos, then submit it for marketplace review.</p>
          <a [routerLink]="newListingLink()" class="primary-btn">Create draft</a>
        </app-ui-empty-state>
      } @else {
        <div class="listing-list">
          @for (listing of listings(); track listing.id) {
            <a class="listing-row" [routerLink]="editListingLink(listing.id)">
              <div>
                <strong>{{ listing.title }}</strong>
                <span class="owner-line">Owner: {{ ownerLabel(listing) }}</span>
                <span>{{ listing.sellerType }} / {{ listing.condition }} / {{ listing.currency }} {{ listing.priceAmount }}</span>
              </div>
              <div class="status">
                <app-ui-status-pill>{{ listing.status }}</app-ui-status-pill>
                <small>{{ listing.moderationStatus }} / v{{ listing.version }}</small>
              </div>
            </a>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      --listing-surface: var(--color-bg-secondary);
      --listing-row: transparent;
      --listing-hover: var(--color-bg-tertiary);
      --listing-border: var(--color-border);
      --listing-text: var(--color-text-primary);
      --listing-muted: var(--color-text-secondary);
      --listing-subtle: var(--color-text-muted);
      --listing-accent: var(--color-accent);
      --listing-primary-bg: var(--color-accent);
      --listing-primary-text: #0c0c0e;
      --listing-shadow: none;
    }

    :host-context(.marketplace-shell) {
      --listing-surface: rgba(255, 255, 255, 0.92);
      --listing-row: #fff8fc;
      --listing-hover: #fff0f7;
      --listing-border: var(--market-line);
      --listing-text: var(--market-ink);
      --listing-muted: var(--market-muted);
      --listing-subtle: var(--market-muted);
      --listing-accent: var(--market-accent-dark);
      --listing-primary-bg: linear-gradient(135deg, #ff85bd, #8b6fe8);
      --listing-primary-text: #fff;
      --listing-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .listing-page {
      max-width: 960px;
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      margin: 0 auto;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1.25rem;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-lg);
      background: var(--listing-surface);
      box-shadow: var(--listing-shadow);
    }

    .eyebrow {
      margin: 0 0 0.35rem;
      color: var(--listing-accent);
      font-size: 0.75rem;
      font-weight: 850;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    .page-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
      letter-spacing: 0;
      color: var(--listing-text);
    }

    .page-header p {
      color: var(--listing-subtle);
      font-size: 0.875rem;
      margin: 0;
    }

    .primary-btn {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      background: var(--listing-primary-bg);
      color: var(--listing-primary-text);
      font-weight: 800;
      text-decoration: none;
    }

    .listing-list {
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid var(--listing-border);
      border-radius: var(--radius-lg);
      background: var(--listing-surface);
      box-shadow: var(--listing-shadow);
    }

    .listing-row {
      min-height: 72px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.875rem 1rem;
      color: var(--listing-text);
      text-decoration: none;
      border-bottom: 1px solid var(--listing-border);
      background: var(--listing-row);
    }

    .listing-row:last-child {
      border-bottom: 0;
    }

    .listing-row:hover {
      background: var(--listing-hover);
    }

    .listing-row div {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      min-width: 0;
    }

    .listing-row strong,
    .listing-row span {
      overflow-wrap: anywhere;
    }

    .listing-row > div:first-child span,
    .status small {
      color: var(--listing-muted);
      font-size: 0.8125rem;
    }

    .listing-row .owner-line {
      color: var(--listing-accent);
      font-weight: 850;
    }

    .status {
      align-items: flex-end;
      flex: 0 0 auto;
    }

    .error-message {
      border-radius: var(--radius-lg);
      padding: 1rem;
      background: var(--listing-surface);
      border: 1px solid var(--listing-border);
      color: var(--listing-muted);
      box-shadow: var(--listing-shadow);
    }

    .error-message {
      color: var(--color-danger);
      background: rgba(244, 63, 94, 0.08);
      border-color: rgba(244, 63, 94, 0.2);
    }

    @media (max-width: 700px) {
      .page-header,
      .listing-row {
        align-items: stretch;
        flex-direction: column;
      }

      .status {
        align-items: flex-start;
      }
    }
  `],
})
export class ListingManagementComponent implements OnInit {
  private listingService = inject(ListingService);

  listings = signal<ListingDraft[]>([]);
  loading = signal(false);
  errorMsg = signal('');

  ngOnInit(): void {
    this.loading.set(true);
    this.listingService.getMyListings().subscribe({
      next: listings => {
        this.listings.set(listings);
        this.loading.set(false);
      },
      error: error => {
        this.loading.set(false);
        this.errorMsg.set(error.error?.error?.message || 'Listings could not be loaded.');
      },
    });
  }

  // Keeps personal listing creation inside the marketplace account surface.
  newListingLink(): string[] {
    return [this.listingBasePath(), 'new'];
  }

  // Builds the marketplace account edit route for a seller-owned listing draft.
  editListingLink(listingId: string): string[] {
    return [this.listingBasePath(), listingId, 'edit'];
  }

  ownerLabel(listing: ListingDraft): string {
    return listing.sellerDisplayName?.trim()
      || (listing.sellerType === 'BUSINESS' ? 'Business seller' : 'Individual seller');
  }

  // Centralizes the account listing route so legacy seller routes do not leak into templates.
  private listingBasePath(): string {
    return '/account/listings';
  }
}
