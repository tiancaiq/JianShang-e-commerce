import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BrandMascotComponent } from '../../../shared/components/ui/brand-mascot.component';
import { MarketplaceUiProduct } from './marketplace-ui.model';

@Component({
  selector: 'app-marketplace-hero-banner',
  standalone: true,
  imports: [BrandMascotComponent, DecimalPipe, FormsModule, RouterLink],
  template: `
    <section class="hero-section" aria-labelledby="marketplace-title">
      <div class="hero-copy">
        <p class="eyebrow">MSB cute market</p>
        <h1 id="marketplace-title">Find it. List it. Trade locally.</h1>
        <p class="summary">
          Browse local listings with clear seller labels, image-forward cards, and public pickup areas.
        </p>

        <form class="hero-search" role="search" (submit)="submitSearch(); $event.preventDefault()">
          <label>
            <span>Search marketplace</span>
            <input
              name="heroMarketplaceSearch"
              type="search"
              [(ngModel)]="searchTerm"
              placeholder="Search figures, manga, plushies, decor..."
            />
          </label>
          <button type="submit">Search</button>
        </form>

        <div class="hero-actions">
          <a href="#listings" class="primary-link">Browse Listings</a>
          @if (authenticated) {
            <a routerLink="/account/listings/new" class="secondary-link">Sell an Item</a>
          } @else {
            <button type="button" class="secondary-link" (click)="loginRequested.emit()">Sell an Item</button>
          }
        </div>

      </div>

      <div class="hero-art" aria-label="Marketplace mascot feature">
        <div class="mascot-card">
          <app-brand-mascot variant="hero" alt="MSB marketplace brand mascot in a pastel room" />
          <span class="floating-badge badge-new">New finds</span>
          <span class="floating-badge badge-trust">Seller labels</span>
          <span class="floating-badge badge-local">Local pickup</span>
        </div>

        <a class="featured-card" [routerLink]="featuredProduct ? ['/listings', featuredProduct.id] : '/marketplace'">
          @if (featuredProduct) {
            <div>
              <span>Featured find</span>
              <strong>{{ featuredProduct.title }}</strong>
              <small>by {{ featuredProduct.sellerName }}</small>
            </div>
            <p>{{ featuredProduct.priceAmount | number: '1.2-2' }} {{ featuredProduct.currency }}</p>
          } @else {
            <div>
              <span>Featured find</span>
              <strong>Approved listings appear here</strong>
              <small>Start with clear listing details</small>
            </div>
            <p>View Details</p>
          }
        </a>
      </div>
    </section>
  `,
  styles: [`
    .hero-section {
      position: relative;
      min-height: 480px;
      display: grid;
      grid-template-columns: minmax(0, 1.05fr) minmax(320px, 0.95fr);
      gap: clamp(1rem, 3vw, 2rem);
      align-items: center;
      overflow: hidden;
      padding: clamp(1.25rem, 4vw, 2.5rem);
      border: 1px solid rgba(234, 215, 242, 0.9);
      border-radius: 28px;
      background:
        radial-gradient(circle at 12% 12%, rgba(244, 114, 182, 0.18) 0 16%, transparent 17%),
        radial-gradient(circle at 86% 8%, rgba(139, 111, 232, 0.18) 0 14%, transparent 15%),
        linear-gradient(135deg, rgba(255, 240, 247, 0.96), rgba(237, 231, 255, 0.92));
      box-shadow: 0 18px 44px rgba(159, 91, 144, 0.14);
    }

    .hero-section::after {
      content: '';
      position: absolute;
      inset: 0;
      pointer-events: none;
      opacity: 0.35;
      background-image:
        linear-gradient(45deg, rgba(244, 114, 182, 0.08) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(139, 111, 232, 0.07) 25%, transparent 25%);
      background-size: 30px 30px;
    }

    .hero-copy,
    .hero-art {
      position: relative;
      z-index: 1;
    }

    .hero-copy {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1rem;
      max-width: 780px;
    }

    .eyebrow {
      margin: 0;
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 950;
      letter-spacing: 0.1em;
      text-transform: uppercase;
    }

    h1 {
      max-width: 780px;
      margin: 0;
      color: var(--market-ink);
      font-size: clamp(2.5rem, 6.2vw, 5rem);
      line-height: 0.95;
      font-weight: 950;
      letter-spacing: 0;
      text-shadow: 0 2px 0 rgba(255, 255, 255, 0.88);
    }

    .summary {
      max-width: 620px;
      margin: 0;
      color: var(--market-muted);
      font-size: 1.08rem;
      font-weight: 750;
      line-height: 1.55;
    }

    .hero-search {
      max-width: 760px;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 0.75rem;
      align-items: end;
      padding: 0.45rem;
      border: 1px solid rgba(234, 215, 242, 0.92);
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.82);
      box-shadow: 0 14px 32px rgba(143, 92, 144, 0.11);
    }

    .hero-search label {
      min-width: 0;
      display: grid;
    }

    .hero-search label > span {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }

    .hero-search input {
      width: 100%;
      min-height: 46px;
      border: 0;
      border-radius: 999px;
      background: transparent;
      color: var(--market-ink);
      padding: 0 1rem;
      font: inherit;
      font-weight: 750;
      outline: none;
    }

    .hero-search button,
    .primary-link,
    .secondary-link {
      min-height: 46px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 999px;
      font: inherit;
      font-weight: 950;
      text-decoration: none;
      cursor: pointer;
      transition: transform 220ms ease, box-shadow 220ms ease;
      white-space: nowrap;
    }

    .hero-search button,
    .primary-link {
      border: 0;
      padding: 0 1.2rem;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      box-shadow: 0 14px 28px rgba(190, 58, 131, 0.22);
    }

    .secondary-link {
      border: 1px solid rgba(234, 215, 242, 0.95);
      padding: 0 1.1rem;
      background: rgba(255, 255, 255, 0.92);
      color: var(--market-accent-dark);
    }

    .hero-search button:hover,
    .primary-link:hover,
    .secondary-link:hover {
      transform: translateY(-2px);
      box-shadow: 0 18px 38px rgba(190, 58, 131, 0.2);
    }

    .hero-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      align-items: center;
    }

    .hero-art {
      display: grid;
      gap: 0.85rem;
      align-self: stretch;
      align-content: center;
    }

    .mascot-card {
      position: relative;
      min-height: 340px;
      border-radius: 24px;
      animation: float 6s ease-in-out infinite;
    }

    .floating-badge {
      position: absolute;
      min-height: 34px;
      display: inline-flex;
      align-items: center;
      border: 1px solid rgba(255, 255, 255, 0.82);
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.88);
      color: var(--market-accent-dark);
      font-size: 0.78rem;
      font-weight: 950;
      padding: 0 0.85rem;
      box-shadow: 0 12px 28px rgba(132, 83, 143, 0.16);
      backdrop-filter: blur(14px);
    }

    .badge-new {
      top: 1rem;
      left: -0.35rem;
    }

    .badge-trust {
      right: -0.45rem;
      top: 28%;
    }

    .badge-local {
      left: 1rem;
      bottom: 1rem;
    }

    .featured-card {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid rgba(234, 215, 242, 0.95);
      border-radius: 20px;
      background: rgba(255, 255, 255, 0.9);
      color: inherit;
      text-decoration: none;
      box-shadow: 0 16px 38px rgba(100, 63, 120, 0.14);
      backdrop-filter: blur(16px);
    }

    .featured-card div {
      min-width: 0;
      display: grid;
      gap: 0.15rem;
    }

    .featured-card span,
    .featured-card small {
      color: var(--market-muted);
      font-size: 0.75rem;
      font-weight: 900;
    }

    .featured-card strong {
      color: var(--market-ink);
      font-weight: 950;
      overflow-wrap: anywhere;
    }

    .featured-card p {
      margin: 0;
      color: var(--market-accent-dark);
      font-weight: 950;
      white-space: nowrap;
    }

    @keyframes float {
      0%, 100% { transform: translateY(0); }
      50% { transform: translateY(-8px); }
    }

    @media (prefers-reduced-motion: reduce) {
      .mascot-card {
        animation: none;
      }
    }

    @media (max-width: 980px) {
      .hero-section {
        grid-template-columns: 1fr;
      }
    }

    @media (max-width: 640px) {
      .hero-section {
        min-height: auto;
        padding: 1rem;
        border-radius: 20px;
      }

      .hero-search,
      .hero-actions {
        grid-template-columns: 1fr;
      }

      .hero-search {
        border-radius: 20px;
      }

      .hero-search button,
      .primary-link,
      .secondary-link {
        width: 100%;
      }

      .mascot-card {
        min-height: 250px;
      }

      .floating-badge {
        display: none;
      }

      .featured-card {
        align-items: flex-start;
        flex-direction: column;
      }
    }
  `],
})
export class MarketplaceHeroBannerComponent {
  @Input() featuredProduct: MarketplaceUiProduct | null = null;
  @Input() authenticated = false;
  @Output() searchRequested = new EventEmitter<string>();
  @Output() loginRequested = new EventEmitter<void>();

  searchTerm = '';

  submitSearch(): void {
    this.searchRequested.emit(this.searchTerm.trim());
  }
}
