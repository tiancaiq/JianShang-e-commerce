import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { PublicListing } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import {
  publicListingConditionLabel,
  publicListingLocationLabel,
  publicListingPrimaryImageUrl,
} from '../../shared/listing/public-listing-display';
import {
  AgentListingSelection,
  safeAgentListingTitle,
  toAgentListingSelection,
} from './agent-listing-context.model';

@Component({
  selector: 'app-agent-listing-context-picker',
  standalone: true,
  imports: [DecimalPipe, FormsModule, RouterLink],
  template: `
    <section class="context-picker" aria-labelledby="agent-listing-picker-heading">
      <span class="picker-eyebrow">Listing-bound help</span>
      <div class="picker-heading">
        <div>
          <h3 id="agent-listing-picker-heading">Choose a listing</h3>
          <p>The assistant checks only the public listing you select.</p>
        </div>
        <a routerLink="/marketplace">Browse all</a>
      </div>

      <form class="search-form" role="search" (ngSubmit)="search()">
        <label for="agent-listing-search">Search public individual listings</label>
        <div>
          <input
            id="agent-listing-search"
            name="agentListingSearch"
            type="search"
            maxlength="120"
            autocomplete="off"
            [disabled]="disabled"
            [ngModel]="query()"
            (ngModelChange)="query.set($event)"
            placeholder="Search by listing title" />
          <button type="submit" [disabled]="disabled || searching()">Search</button>
        </div>
      </form>

      @if (searching()) {
        <div class="picker-state" role="status" aria-live="polite">
          <span class="loading-pulse" aria-hidden="true"></span>
          <span>Loading public listings...</span>
        </div>
      } @else if (errorMessage()) {
        <div class="picker-state error-state" role="alert">
          <strong>Listings could not be loaded.</strong>
          <span>{{ errorMessage() }}</span>
          <button type="button" (click)="search()" [disabled]="disabled">Retry</button>
        </div>
      } @else if (!listings().length) {
        <div class="picker-state empty-state" role="status">
          <strong>No matching listings</strong>
          <span>Try another title or browse the marketplace.</span>
        </div>
      } @else {
        <ul class="listing-results" aria-label="Available individual listings">
          @for (listing of listings(); track listing.id) {
            <li>
              <button
                class="listing-result"
                type="button"
                [disabled]="disabled"
                [attr.aria-label]="'Use ' + safeTitle(listing.title) + ' for AI listing help'"
                (click)="select(listing)">
                @if (imageUrl(listing); as image) {
                  <img [src]="image" [alt]="listing.images[0]?.altText || ''" />
                } @else {
                  <span class="image-fallback" aria-hidden="true">Item</span>
                }
                <span class="result-copy">
                  <strong>{{ safeTitle(listing.title) }}</strong>
                  <span>{{ conditionLabel(listing.condition) }} · {{ locationLabel(listing) }}</span>
                </span>
                <span class="result-price">
                  {{ listing.priceAmount | number: '1.2-2' }}
                  <small>{{ listing.currency }}</small>
                </span>
              </button>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
      width: 100%;
      max-width: 560px;
      align-self: center;
      margin: auto;
      box-sizing: border-box;
      padding: 1rem;
    }

    .context-picker {
      min-width: 0;
      overflow: hidden;
      border: 1px solid rgba(190, 47, 118, 0.18);
      border-radius: 8px;
      background: #fffafd;
      box-sizing: border-box;
      padding: 1rem;
    }

    .picker-eyebrow {
      color: var(--market-accent-dark);
      font-size: 0.68rem;
      font-weight: 950;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    .picker-heading {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 0.75rem;
      margin-top: 0.35rem;
    }

    h3,
    p {
      margin: 0;
    }

    h3 {
      color: var(--market-ink);
      font-size: 1rem;
      font-weight: 950;
    }

    p {
      margin-top: 0.18rem;
      color: var(--market-muted);
      font-size: 0.78rem;
      font-weight: 750;
      line-height: 1.45;
    }

    a {
      flex: 0 0 auto;
      color: var(--market-accent-dark);
      font-size: 0.74rem;
      font-weight: 900;
      text-decoration: none;
    }

    .search-form {
      display: grid;
      gap: 0.32rem;
      margin-top: 0.85rem;
    }

    .search-form label {
      color: var(--market-muted);
      font-size: 0.7rem;
      font-weight: 900;
    }

    .search-form > div {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.45rem;
      min-width: 0;
    }

    input {
      min-width: 0;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-ink);
      font: inherit;
      padding: 0.62rem 0.7rem;
    }

    input:focus,
    button:focus-visible,
    a:focus-visible {
      outline: 2px solid rgba(190, 47, 118, 0.3);
      outline-offset: 2px;
    }

    button {
      min-height: 38px;
      border: 0;
      border-radius: 8px;
      background: var(--market-accent-dark);
      color: #fff;
      cursor: pointer;
      font: inherit;
      font-size: 0.74rem;
      font-weight: 900;
      padding: 0 0.8rem;
    }

    button:disabled {
      cursor: not-allowed;
      opacity: 0.58;
    }

    .listing-results {
      display: grid;
      gap: 0.45rem;
      max-height: 330px;
      overflow-x: hidden;
      overflow-y: auto;
      margin: 0.8rem 0 0;
      padding: 0 0.15rem 0 0;
      list-style: none;
    }

    .listing-result {
      width: 100%;
      min-width: 0;
      min-height: 68px;
      display: grid;
      grid-template-columns: 52px minmax(0, 1fr) max-content;
      gap: 0.65rem;
      align-items: center;
      overflow: hidden;
      border: 1px solid var(--market-line);
      border-left: 4px solid #f7b84b;
      background: #fff;
      color: var(--market-ink);
      padding: 0.45rem 0.55rem 0.45rem 0.45rem;
      text-align: left;
    }

    .listing-result:hover {
      border-color: rgba(190, 47, 118, 0.35);
      background: #fffdf8;
    }

    .listing-result img,
    .image-fallback {
      width: 52px;
      height: 52px;
      border-radius: 7px;
    }

    .listing-result img {
      object-fit: cover;
    }

    .image-fallback {
      display: grid;
      place-items: center;
      background: #f7eef6;
      color: var(--market-muted);
      font-size: 0.65rem;
      font-weight: 900;
    }

    .result-copy {
      display: grid;
      gap: 0.2rem;
      min-width: 0;
    }

    .result-copy strong {
      overflow: hidden;
      font-size: 0.8rem;
      font-weight: 950;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .result-copy span {
      overflow: hidden;
      color: var(--market-muted);
      font-size: 0.67rem;
      font-weight: 800;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .result-price {
      display: grid;
      justify-items: end;
      color: var(--market-accent-dark);
      font-size: 0.76rem;
      font-weight: 950;
      white-space: nowrap;
    }

    .result-price small {
      font-size: 0.58rem;
      letter-spacing: 0.04em;
    }

    .picker-state {
      min-height: 150px;
      display: grid;
      place-items: center;
      align-content: center;
      gap: 0.5rem;
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 800;
      text-align: center;
    }

    .picker-state strong {
      color: var(--market-ink);
      font-size: 0.84rem;
      font-weight: 950;
    }

    .picker-state button {
      margin-top: 0.2rem;
    }

    .loading-pulse {
      width: 28px;
      height: 28px;
      border: 3px solid #f4d8e8;
      border-top-color: var(--market-accent-dark);
      border-radius: 999px;
      animation: spin 0.9s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    @media (max-width: 480px) {
      :host {
        padding: 0.65rem;
      }

      .context-picker {
        padding: 0.8rem;
      }

      .picker-heading {
        align-items: flex-start;
        flex-direction: column;
      }

      .listing-result {
        grid-template-columns: 44px minmax(0, 1fr);
      }

      .listing-result img,
      .image-fallback {
        width: 44px;
        height: 44px;
      }

      .result-price {
        grid-column: 2;
        justify-items: start;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-pulse {
        animation: none;
      }
    }
  `],
})
export class AgentListingContextPickerComponent implements OnInit {
  @Input() disabled = false;
  @Output() readonly listingSelected = new EventEmitter<AgentListingSelection>();

  private readonly listingService = inject(ListingService);

  readonly query = signal('');
  readonly listings = signal<PublicListing[]>([]);
  readonly searching = signal(false);
  readonly errorMessage = signal('');

  ngOnInit(): void {
    this.search();
  }

  /** Loads only the approved public individual-listing projection for context selection. */
  search(): void {
    if (this.disabled || this.searching()) {
      return;
    }
    this.searching.set(true);
    this.errorMessage.set('');
    this.listingService.searchMarketplaceListings({
      q: this.query().trim() || null,
      sort: 'newest',
      limit: 8,
    })
      .pipe(finalize(() => this.searching.set(false)))
      .subscribe({
        next: page => this.listings.set(
          page.data.filter(listing => listing.sellerType === 'INDIVIDUAL'),
        ),
        error: () => {
          this.listings.set([]);
          this.errorMessage.set('Marketplace browsing still works. Try this search again.');
        },
      });
  }

  /** Emits the minimum display-safe context; no seller or private listing fields cross this boundary. */
  select(listing: PublicListing): void {
    if (this.disabled || listing.sellerType !== 'INDIVIDUAL') {
      return;
    }
    const selection = toAgentListingSelection(listing.id, listing.title);
    if (selection) {
      this.listingSelected.emit(selection);
    }
  }

  imageUrl(listing: PublicListing): string {
    return publicListingPrimaryImageUrl(listing, url => this.listingService.mediaUrl(url));
  }

  safeTitle(title: string): string {
    return safeAgentListingTitle(title);
  }

  conditionLabel(condition: string): string {
    return publicListingConditionLabel(condition);
  }

  locationLabel(listing: PublicListing): string {
    return publicListingLocationLabel(listing);
  }
}
