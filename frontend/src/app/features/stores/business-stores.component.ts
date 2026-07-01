import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BusinessStoreListing, PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import {
  publicListingLocationLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';

interface StorePreview {
  name: string;
  city: string;
  region: string;
  listings: BusinessStoreListing[];
}

@Component({
  selector: 'app-business-stores',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="stores-page">
      <header class="stores-hero">
        <p class="eyebrow">Business stores</p>
        <h1>Browse verified store listings.</h1>
        <p>
          Business storefronts are separate from individual trades, with a calmer
          store-focused browsing experience.
        </p>
      </header>

      @if (loading()) {
        <div class="empty-state">Loading stores...</div>
      } @else if (errorMsg()) {
        <div class="empty-state">{{ errorMsg() }}</div>
      } @else if (stores().length === 0) {
        <div class="empty-state">No approved business storefront listings yet.</div>
      } @else {
        <div class="store-grid">
          @for (store of stores(); track store.name) {
            <article class="store-card">
              <div class="store-heading">
                <div>
                  <p class="eyebrow">Verified business</p>
                  <h2>{{ store.name }}</h2>
                </div>
                <span>{{ store.listings.length }} listings</span>
              </div>
              <p class="store-location">{{ locationLabel(store) }}</p>

              <div class="listing-strip">
                @for (listing of store.listings.slice(0, 3); track listing.id) {
                  <a [routerLink]="['/listings', listing.id]" class="store-listing">
                    <div class="listing-image">
                      @if (listing.images[0]?.url || listing.images[0]?.uploadUrl) {
                        <img [src]="imageUrl(listing)" [alt]="listing.images[0]?.altText || listing.title" />
                      } @else {
                        <span>{{ listing.categoryName }}</span>
                      }
                    </div>
                    <strong>{{ listing.title }}</strong>
                    <span>{{ listing.priceAmount | number: '1.2-2' }} {{ listing.currency }}</span>
                  </a>
                }
              </div>
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .stores-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .stores-hero,
    .store-card,
    .empty-state {
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .stores-hero {
      padding: clamp(1rem, 4vw, 2rem);
      background:
        linear-gradient(135deg, rgba(241, 235, 255, 0.95), rgba(239, 251, 248, 0.9)),
        #fff;
    }

    .eyebrow {
      margin: 0 0 0.4rem;
      color: var(--market-lavender);
      font-size: 0.78rem;
      font-weight: 900;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1,
    h2,
    p {
      margin: 0;
    }

    h1 {
      max-width: 760px;
      color: var(--market-ink);
      font-size: clamp(2.2rem, 5vw, 4.4rem);
      line-height: 0.98;
      letter-spacing: 0;
    }

    .stores-hero p:not(.eyebrow) {
      max-width: 760px;
      margin-top: 0.9rem;
      color: var(--market-muted);
      font-weight: 700;
      line-height: 1.6;
    }

    .store-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 1rem;
    }

    .store-card {
      display: flex;
      flex-direction: column;
      gap: 0.9rem;
      padding: 1rem;
    }

    .store-heading {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
    }

    .store-heading h2 {
      color: var(--market-ink);
      font-size: 1.35rem;
      letter-spacing: 0;
    }

    .store-heading > span {
      border-radius: 999px;
      background: #effbf8;
      color: var(--market-mint);
      padding: 0.35rem 0.65rem;
      font-size: 0.78rem;
      font-weight: 900;
      white-space: nowrap;
    }

    .store-location {
      color: var(--market-muted);
      font-weight: 800;
    }

    .listing-strip {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.65rem;
    }

    .store-listing {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 0.35rem;
      color: inherit;
      text-decoration: none;
    }

    .listing-image {
      display: grid;
      place-items: center;
      aspect-ratio: 4 / 3;
      overflow: hidden;
      border-radius: 8px;
      background: linear-gradient(135deg, #eafaf6, #efe8ff);
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 900;
      text-align: center;
    }

    .listing-image img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .store-listing strong {
      color: var(--market-ink);
      font-size: 0.9rem;
      overflow-wrap: anywhere;
    }

    .store-listing span {
      color: var(--market-accent-dark);
      font-size: 0.84rem;
      font-weight: 900;
    }

    .empty-state {
      min-height: 260px;
      display: grid;
      place-items: center;
      padding: 1.5rem;
      color: var(--market-muted);
      font-weight: 850;
      text-align: center;
    }

    @media (max-width: 900px) {
      .store-grid,
      .listing-strip {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class BusinessStoresComponent implements OnInit {
  private readonly listingService = inject(ListingService);

  stores = signal<StorePreview[]>([]);
  loading = signal(false);
  errorMsg = signal('');

  ngOnInit(): void {
    this.loading.set(true);
    this.listingService.getPublicListings().subscribe({
      next: listings => {
        this.stores.set(this.groupBusinessListings(listings));
        this.loading.set(false);
      },
      error: error => {
        this.errorMsg.set(error.status
          ? `Business stores could not be loaded. HTTP ${error.status}.`
          : 'Business stores could not be loaded.');
        this.loading.set(false);
      },
    });
  }

  imageUrl(listing: BusinessStoreListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  locationLabel(store: StorePreview): string {
    return publicListingLocationLabel({ publicCity: store.city, publicRegion: store.region });
  }

  private groupBusinessListings(listings: PublicListing[]): StorePreview[] {
    const stores = new Map<string, StorePreview>();
    for (const listing of this.businessStoreListings(listings)) {
      const name = listing.sellerDisplayName?.trim() || 'Business seller';
      const store = stores.get(name) || {
        name,
        city: listing.publicCity || '',
        region: listing.publicRegion || '',
        listings: [],
      };
      store.listings.push(listing);
      stores.set(name, store);
    }
    return [...stores.values()].sort((left, right) => left.name.localeCompare(right.name));
  }

  private businessStoreListings(listings: PublicListing[]): BusinessStoreListing[] {
    return listings.filter(listing => listing.sellerType === 'BUSINESS');
  }
}
