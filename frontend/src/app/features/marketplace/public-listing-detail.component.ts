import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ListingImageGalleryComponent } from '../../shared/components/ui/listing-image-gallery.component';

@Component({
  selector: 'app-public-listing-detail',
  standalone: true,
  imports: [DecimalPipe, ListingImageGalleryComponent, RouterLink],
  template: `
    <section class="listing-detail">
      @if (loading()) {
        <div class="empty-state">Loading listing...</div>
      } @else if (errorMsg()) {
        <div class="error-state">
          <h1>Listing unavailable</h1>
          <p>{{ errorMsg() }}</p>
          <a routerLink="/">Back to marketplace</a>
        </div>
      } @else if (listing()) {
        <nav class="crumbs" aria-label="Breadcrumb">
          <a routerLink="/">Marketplace</a>
          <span>/</span>
          <span>{{ listing()?.categoryName }}</span>
        </nav>

        <div class="detail-grid">
          <section class="gallery" aria-label="Listing images">
            <app-listing-image-gallery [images]="listing()?.images || []" [fallbackAlt]="listing()?.title || 'Listing image'" />
          </section>

          <article class="purchase-panel">
            <div class="badge-row">
              <span>{{ listing()?.categoryName }}</span>
              <strong [class.business]="listing()?.sellerType === 'BUSINESS'">
                {{ listing()?.sellerType === 'INDIVIDUAL' ? 'Individual seller' : 'Business seller' }}
              </strong>
            </div>

            <h1>{{ listing()?.title }}</h1>

            <div class="price-line">
              <strong>{{ listing()?.priceAmount | number: '1.2-2' }} {{ listing()?.currency }}</strong>
              @if (listing()?.negotiable) {
                <span>Negotiable</span>
              }
            </div>

            <dl class="facts">
              <div>
                <dt>Condition</dt>
                <dd>{{ conditionLabel(listing()?.condition || '') }}</dd>
              </div>
              <div>
                <dt>Location</dt>
                <dd>{{ locationLabel(listing()) }}</dd>
              </div>
              <div>
                <dt>Quantity</dt>
                <dd>{{ listing()?.quantity }}</dd>
              </div>
            </dl>

            <aside class="notice-panel" [class.business]="listing()?.sellerType === 'BUSINESS'">
              {{ marketplaceNotice(listing()) }}
            </aside>
          </article>
        </div>

        <section class="description-panel">
          <div>
            <h2>Description</h2>
            <p>{{ listing()?.description }}</p>
          </div>

          @if (listing()?.conditionNotes) {
            <div>
              <h2>Condition Notes</h2>
              <p>{{ listing()?.conditionNotes }}</p>
            </div>
          }
        </section>
      }
    </section>
  `,
  styles: [`
    .listing-detail {
      display: flex;
      flex-direction: column;
      gap: 1rem;
      color: var(--market-ink);
    }

    .crumbs {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      color: var(--market-muted);
      font-size: 0.875rem;
      font-weight: 800;
    }

    .crumbs a {
      color: var(--market-accent-dark);
      text-decoration: none;
    }

    .detail-grid {
      display: grid;
      grid-template-columns: minmax(0, 1.12fr) minmax(330px, 0.72fr);
      gap: 1rem;
      align-items: start;
    }

    .gallery,
    .purchase-panel,
    .description-panel,
    .empty-state,
    .error-state {
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 14px 34px rgba(143, 92, 144, 0.1);
    }

    .gallery {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      padding: 0.75rem;
    }

    .purchase-panel {
      position: sticky;
      top: 92px;
      display: flex;
      flex-direction: column;
      gap: 1rem;
      padding: 1.15rem;
    }

    .badge-row,
    .price-line {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: center;
    }

    .badge-row span,
    .badge-row strong {
      min-height: 28px;
      display: inline-flex;
      align-items: center;
      border-radius: 999px;
      padding: 0 0.65rem;
      font-size: 0.78rem;
      font-weight: 900;
    }

    .badge-row span {
      background: #fff0f7;
      color: var(--market-accent-dark);
    }

    .badge-row strong {
      background: #eafaf6;
      color: var(--market-mint);
    }

    .badge-row strong:not(.business) {
      background: #f1ecff;
      color: var(--market-lavender);
    }

    h1,
    h2 {
      margin: 0;
      color: var(--market-ink);
      letter-spacing: 0;
    }

    h1 {
      font-size: clamp(1.8rem, 4vw, 3rem);
      line-height: 1.05;
      font-weight: 900;
    }

    h2 {
      font-size: 1.05rem;
    }

    .price-line strong {
      color: var(--market-accent-dark);
      font-size: 1.75rem;
      font-weight: 950;
    }

    .price-line span {
      color: var(--market-lavender);
      font-size: 0.88rem;
      font-weight: 900;
    }

    .facts {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.65rem;
      margin: 0;
    }

    .facts div {
      min-height: 78px;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      padding: 0.75rem;
      background: #fff8fc;
    }

    dt {
      margin-bottom: 0.3rem;
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 850;
    }

    dd {
      margin: 0;
      color: var(--market-ink);
      overflow-wrap: anywhere;
      font-weight: 850;
    }

    .notice-panel {
      border: 1px solid rgba(244, 114, 182, 0.28);
      border-radius: 8px;
      background: #fff6fb;
      color: #774163;
      padding: 0.9rem;
      line-height: 1.45;
      font-weight: 700;
    }

    .notice-panel.business {
      border-color: rgba(56, 168, 149, 0.24);
      background: #effbf8;
      color: #246558;
    }

    .description-panel {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 1rem;
      padding: 1.15rem;
    }

    .description-panel div {
      display: flex;
      flex-direction: column;
      gap: 0.45rem;
    }

    .description-panel p,
    .error-state p {
      margin: 0;
      color: var(--market-muted);
      line-height: 1.6;
      white-space: pre-wrap;
    }

    .empty-state,
    .error-state {
      padding: 2rem;
      color: var(--market-muted);
    }

    .error-state {
      display: flex;
      flex-direction: column;
      gap: 0.8rem;
    }

    .error-state a {
      color: var(--market-accent-dark);
      font-weight: 850;
      text-decoration: none;
    }

    @media (max-width: 980px) {
      .detail-grid {
        grid-template-columns: 1fr;
      }

      .purchase-panel {
        position: static;
      }

    }

    @media (max-width: 640px) {
      .facts {
        grid-template-columns: 1fr;
      }

      .badge-row,
      .price-line {
        align-items: flex-start;
        flex-direction: column;
      }

    }
  `],
})
export class PublicListingDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly listingService = inject(ListingService);

  listing = signal<PublicListing | null>(null);
  loading = signal(false);
  errorMsg = signal('');

  ngOnInit(): void {
    const listingId = this.route.snapshot.paramMap.get('listingId') || '';
    if (!listingId) {
      this.errorMsg.set('This listing could not be found.');
      return;
    }

    this.loading.set(true);
    this.listingService.getPublicListing(listingId).subscribe({
      next: listing => {
        this.listing.set(listing);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.errorMsg.set('This listing is not available.');
      },
    });
  }

  conditionLabel(condition: string): string {
    return condition.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
  }

  locationLabel(listing: PublicListing | null): string {
    if (!listing) {
      return 'Not set';
    }
    return [listing.publicCity, listing.publicRegion].filter(Boolean).join(', ') || 'Not set';
  }

  marketplaceNotice(listing: PublicListing | null): string {
    if (!listing) {
      return '';
    }
    if (listing.sellerType === 'BUSINESS') {
      return 'Business listings can be viewed here. Platform checkout is not part of the MVP yet.';
    }
    return listing.transactionNotice
      || 'Payment and delivery are arranged directly between buyer and seller. The platform does not process or verify off-platform payment.';
  }

}
