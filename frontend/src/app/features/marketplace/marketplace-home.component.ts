import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';

@Component({
  selector: 'app-marketplace-home',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="marketplace-home">
      <div class="intro">
        <p class="eyebrow">Marketplace</p>
        <h1>Find local listings and trusted stores.</h1>
        <p class="summary">
          Browse is public. Seller tools live in a separate portal.
        </p>
        <div class="actions">
          <a routerLink="/seller" class="primary-link">Seller Portal</a>
          <a routerLink="/login" class="secondary-link">Sign in</a>
        </div>
      </div>

      <div class="browse-panel">
        <div class="panel-header">
          <h2>Public Listings</h2>
          <span>{{ listings().length }} approved</span>
        </div>
        @if (loading()) {
          <div class="empty-list">Loading approved listings...</div>
        } @else if (errorMsg()) {
          <div class="empty-list">{{ errorMsg() }}</div>
        } @else if (listings().length === 0) {
          <div class="empty-list">No approved listings yet.</div>
        } @else {
          <div class="listing-grid">
            @for (listing of listings(); track listing.id) {
              <a class="listing-card" [routerLink]="['/listings', listing.id]">
                <div class="listing-image">
                  @if (listing.images[0]?.url || listing.images[0]?.uploadUrl) {
                    <img [src]="imageUrl(listing)" [alt]="listing.images[0]?.altText || listing.title" />
                  } @else {
                    <span>{{ listing.categoryName }}</span>
                  }
                </div>
                <div class="listing-body">
                  <div class="listing-meta">
                    <span>{{ listing.categoryName }}</span>
                    <strong>{{ listing.sellerType === 'INDIVIDUAL' ? 'Individual' : 'Business' }}</strong>
                  </div>
                  <h3>{{ listing.title }}</h3>
                  <p>{{ locationLabel(listing) }}</p>
                  <div class="listing-price">
                    {{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}
                  </div>
                </div>
              </a>
            }
          </div>
        }
      </div>
    </section>
  `,
  styles: [`
    .marketplace-home {
      display: grid;
      grid-template-columns: minmax(0, 0.85fr) minmax(320px, 1.15fr);
      gap: 2rem;
      align-items: start;
    }

    .intro {
      padding: 3rem 0;
    }

    .eyebrow {
      color: var(--color-accent);
      font-size: 0.8125rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      margin-bottom: 0.75rem;
    }

    h1 {
      max-width: 640px;
      font-size: 3rem;
      line-height: 1.05;
      margin-bottom: 1rem;
      letter-spacing: 0;
    }

    .summary {
      max-width: 560px;
      color: var(--color-text-secondary);
      font-size: 1rem;
      margin-bottom: 1.5rem;
    }

    .actions {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
    }

    .primary-link,
    .secondary-link {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 42px;
      padding: 0 1rem;
      border-radius: var(--radius-md);
      font-weight: 700;
      text-decoration: none;
    }

    .primary-link {
      background: var(--color-accent);
      color: #0c0c0e;
    }

    .secondary-link {
      border: 1px solid var(--color-border);
      color: var(--color-text-secondary);
    }

    .browse-panel {
      background: var(--color-bg-secondary);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem 1.25rem;
      border-bottom: 1px solid var(--color-border);
    }

    .panel-header h2 {
      font-size: 1.125rem;
      letter-spacing: 0;
    }

    .panel-header span {
      color: var(--color-text-muted);
      font-size: 0.8125rem;
      font-weight: 700;
    }

    .empty-list {
      min-height: 260px;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      color: var(--color-text-muted);
      text-align: center;
    }

    .listing-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.9rem;
      padding: 1rem;
    }

    .listing-card {
      min-width: 0;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
      overflow: hidden;
      background: var(--color-bg-tertiary);
      color: inherit;
      text-decoration: none;
    }

    .listing-card:hover {
      border-color: var(--color-accent);
    }

    .listing-image {
      min-height: 128px;
      display: grid;
      place-items: center;
      background:
        linear-gradient(135deg, rgba(255, 145, 0, 0.16), rgba(56, 189, 248, 0.12)),
        var(--color-bg-primary);
      color: var(--color-text-primary);
      font-weight: 800;
      overflow-wrap: anywhere;
      text-align: center;
    }

    .listing-image img {
      width: 100%;
      height: 100%;
      min-height: 128px;
      object-fit: cover;
      display: block;
    }

    .listing-image span {
      padding: 1rem;
    }

    .listing-body {
      display: flex;
      flex-direction: column;
      gap: 0.45rem;
      padding: 0.85rem;
    }

    .listing-meta {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      color: var(--color-text-muted);
      font-size: 0.75rem;
      font-weight: 800;
    }

    .listing-meta span {
      color: var(--color-accent);
    }

    .listing-card h3 {
      margin: 0;
      color: var(--color-text-primary);
      font-size: 1rem;
      letter-spacing: 0;
    }

    .listing-card p {
      margin: 0;
      color: var(--color-text-secondary);
      font-size: 0.8125rem;
    }

    .listing-price {
      color: var(--color-text-primary);
      font-weight: 900;
    }

    @media (max-width: 900px) {
      .marketplace-home {
        grid-template-columns: 1fr;
      }

      h1 {
        font-size: 2.25rem;
      }

      .listing-grid {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class MarketplaceHomeComponent implements OnInit {
  private readonly listingService = inject(ListingService);

  listings = signal<PublicListing[]>([]);
  loading = signal(false);
  errorMsg = signal('');

  ngOnInit(): void {
    this.loading.set(true);
    this.listingService.getPublicListings().subscribe({
      next: listings => {
        this.listings.set(listings);
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(this.publicListingLoadMessage(error));
        this.loading.set(false);
      },
    });
  }

  locationLabel(listing: PublicListing): string {
    return [listing.publicCity, listing.publicRegion].filter(Boolean).join(', ') || 'Location not set';
  }

  imageUrl(listing: PublicListing): string {
    const image = listing.images[0];
    return this.listingService.mediaUrl(image?.url || image?.uploadUrl);
  }

  private publicListingLoadMessage(error: { status?: number }): string {
    return error.status
      ? `Approved listings could not be loaded. HTTP ${error.status}.`
      : 'Approved listings could not be loaded.';
  }
}
