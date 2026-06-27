import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ListingDraft } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';

@Component({
  selector: 'app-listing-management',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="listing-page">
      <header class="page-header">
        <div>
          <h1>Listings</h1>
          <p>Drafts and seller-managed listings.</p>
        </div>
        <a routerLink="/seller/listings/new" class="primary-btn">New listing</a>
      </header>

      @if (loading()) {
        <div class="empty-state">Loading listings...</div>
      } @else if (errorMsg()) {
        <div class="error-message">{{ errorMsg() }}</div>
      } @else if (listings().length === 0) {
        <div class="empty-state">
          <h2>No drafts yet</h2>
          <a routerLink="/seller/listings/new" class="primary-btn">Create draft</a>
        </div>
      } @else {
        <div class="listing-table">
          @for (listing of listings(); track listing.id) {
            <a class="listing-row" [routerLink]="['/seller/listings', listing.id, 'edit']">
              <div>
                <strong>{{ listing.title }}</strong>
                <span>{{ listing.sellerType }} · {{ listing.condition }} · {{ listing.currency }} {{ listing.priceAmount }}</span>
              </div>
              <div class="status">
                <span>{{ listing.status }}</span>
                <small>v{{ listing.version }}</small>
              </div>
            </a>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .listing-page {
      max-width: 960px;
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
    }

    .page-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }

    .page-header h1 {
      font-size: 1.75rem;
      margin-bottom: 0.25rem;
    }

    .page-header p {
      color: var(--color-text-muted);
      font-size: 0.875rem;
    }

    .primary-btn {
      min-height: 40px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 0.875rem;
      border-radius: var(--radius-md);
      background: var(--color-accent);
      color: #0c0c0e;
      font-weight: 800;
      text-decoration: none;
    }

    .listing-table {
      display: flex;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      background: var(--color-bg-secondary);
    }

    .listing-row {
      min-height: 72px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.875rem 1rem;
      color: var(--color-text-primary);
      text-decoration: none;
      border-bottom: 1px solid var(--color-border);
    }

    .listing-row:last-child {
      border-bottom: 0;
    }

    .listing-row:hover {
      background: var(--color-accent-muted);
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

    .listing-row span,
    .status small {
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
    }

    .status {
      align-items: flex-end;
      flex: 0 0 auto;
    }

    .status span {
      color: var(--color-accent);
      font-weight: 800;
    }

    .empty-state,
    .error-message {
      border-radius: var(--radius-lg);
      padding: 1rem;
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      color: var(--color-text-secondary);
    }

    .empty-state h2 {
      margin-bottom: 0.75rem;
      color: var(--color-text-primary);
      font-size: 1rem;
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
}
