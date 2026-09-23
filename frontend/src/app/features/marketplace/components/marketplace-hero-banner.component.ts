import { DecimalPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EditorialArtworkComponent } from '../../../shared/components/ui/editorial-artwork.component';
import { MarketplaceUiProduct } from './marketplace-ui.model';

@Component({
  selector: 'app-marketplace-hero-banner',
  standalone: true,
  imports: [DecimalPipe, EditorialArtworkComponent, FormsModule, RouterLink],
  template: `
    <section class="hero-section" aria-labelledby="marketplace-title">
      <app-editorial-artwork
        class="hero-artwork"
        src="/assets/brand/anime/marketplace-hero-moon-fox-v3.webp"
        alt="Bright blossom marketplace scene with a snow-fox heroine, moon, and white fox companion"
        focalPoint="72% center"
        mobileFocalPoint="76% 26%"
        mobileComposition="lower-panel"
        overlay="left"
        overlayStrength="0.92"
      />

      <div class="hero-copy">
        <p class="eyebrow"><span aria-hidden="true">✦</span> The blossom marketplace</p>
        <h1 id="marketplace-title">Find something<br /><em>worth keeping.</em></h1>
        <p class="summary">Discover beautiful objects, independent sellers, and local stories—clearly labeled and easy to explore.</p>

        <form class="hero-search" role="search" (submit)="submitSearch(); $event.preventDefault()">
          <label>
            <span>Search marketplace</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m20.7 19.3-4.1-4.1a7 7 0 1 0-1.4 1.4l4.1 4.1 1.4-1.4zM5 11a6 6 0 1 1 12 0 6 6 0 0 1-12 0z"/></svg>
            <input name="heroMarketplaceSearch" type="search" [(ngModel)]="searchTerm" placeholder="Try ‘silver pendant’ or ‘vintage camera’" />
          </label>
          <button type="submit">Search</button>
        </form>

        <div class="hero-actions">
          <a href="#listings" class="primary-link">Browse Listings <span aria-hidden="true">→</span></a>
          @if (authenticated) {
            <a routerLink="/account/listings/new" class="secondary-link">Sell an Item</a>
          } @else {
            <button type="button" class="secondary-link" (click)="loginRequested.emit()">Sell an Item</button>
          }
        </div>

        <div class="path-pills" aria-label="Marketplace purchase paths">
          <span><i class="dot individual" aria-hidden="true"></i><strong>Individual</strong> arrange with the seller</span>
          <span><i class="dot business" aria-hidden="true"></i><strong>Business</strong> checkout when available</span>
        </div>
      </div>

      <a class="featured-card" [routerLink]="featuredProduct ? ['/listings', featuredProduct.id] : '/marketplace'">
        <span class="featured-kicker">Today's featured find</span>
        @if (featuredProduct) {
          <strong>{{ featuredProduct.title }}</strong>
          <small>Offered by {{ featuredProduct.sellerName }}</small>
          <p>{{ featuredProduct.priceAmount | number: '1.2-2' }} {{ featuredProduct.currency }}</p>
        } @else {
          <strong>Fresh finds arrive here</strong>
          <small>Browse approved local listings</small>
          <p>Explore</p>
        }
      </a>

      <span class="hero-frame hero-frame-top" aria-hidden="true"></span>
      <span class="hero-frame hero-frame-bottom" aria-hidden="true"></span>
    </section>
  `,
  styles: [`
    :host { display: block; }

    .hero-section {
      position: relative;
      min-height: clamp(560px, 44vw, 660px);
      overflow: hidden;
      border: 1px solid rgba(143, 106, 127, .28);
      border-radius: 34px 10px 34px 10px;
      background: #fff4f9;
      box-shadow: var(--market-shadow-lg);
      isolation: isolate;
    }

    .hero-artwork { position: absolute; inset: 0; z-index: 0; }

    .hero-section::before {
      position: absolute;
      z-index: 1;
      inset: 0;
      background:
        linear-gradient(90deg, rgba(255,255,255,.97) 0%, rgba(255,248,252,.9) 34%, rgba(255,244,250,.18) 59%, transparent 76%),
        linear-gradient(180deg, transparent 70%, rgba(255,205,225,.13));
      content: '';
      pointer-events: none;
    }

    .hero-copy {
      position: relative;
      z-index: 3;
      width: min(53%, 760px);
      min-height: inherit;
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1.05rem;
      padding: clamp(2.4rem, 5vw, 5.5rem) clamp(1.5rem, 4.5vw, 4.75rem);
    }

    .eyebrow {
      width: max-content;
      display: inline-flex;
      align-items: center;
      gap: .55rem;
      margin: 0;
      color: var(--market-accent-dark);
      font-family: var(--font-market-utility);
      font-size: .72rem;
      font-weight: 760;
      letter-spacing: .18em;
      text-transform: uppercase;
    }

    .eyebrow span { color: var(--market-rose-gold); font-size: .95rem; }

    h1 {
      margin: 0;
      color: var(--market-ink);
      font-family: var(--font-market-display);
      font-size: clamp(3.6rem, 6.2vw, 6.7rem);
      font-weight: 500;
      letter-spacing: -.055em;
      line-height: .88;
      text-wrap: balance;
    }

    h1 em { color: var(--market-accent-dark); font-style: italic; font-weight: 500; letter-spacing: -.045em; }

    .summary {
      max-width: 610px;
      margin: .15rem 0 .1rem;
      color: var(--market-muted);
      font-size: clamp(.98rem, 1.4vw, 1.13rem);
      line-height: 1.65;
    }

    .hero-search {
      width: min(100%, 690px);
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: .5rem;
      padding: .42rem;
      border: 1px solid rgba(143,106,127,.36);
      border-radius: var(--market-radius-control);
      background: rgba(255,255,255,.93);
      box-shadow: 0 16px 38px rgba(208,75,126,.12);
      backdrop-filter: blur(14px);
    }

    .hero-search:focus-within { border-color: var(--market-accent); box-shadow: 0 0 0 4px rgba(233,79,138,.14), 0 16px 38px rgba(208,75,126,.12); }
    .hero-search label { min-width: 0; display: grid; grid-template-columns: 40px minmax(0,1fr); align-items: center; }
    .hero-search label > span { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
    .hero-search svg { width: 1.1rem; height: 1.1rem; justify-self: center; fill: var(--market-accent-dark); }
    .hero-search input, .hero-search button, .secondary-link { border: 0; font: inherit; }
    .hero-search input { min-width: 0; min-height: 48px; padding: 0 .5rem 0 0; background: transparent; color: var(--market-ink); outline: none; }
    .hero-search input::placeholder { color: #927f8a; }

    .hero-search button,
    .primary-link,
    .secondary-link {
      min-height: 48px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: .5rem;
      padding: 0 1.35rem;
      border-radius: var(--market-radius-control);
      cursor: pointer;
      font-weight: 760;
      text-decoration: none;
      transition: transform 180ms ease, box-shadow 180ms ease, background 180ms ease;
    }

    .hero-search button, .primary-link { background: linear-gradient(135deg, #d92e72, #c82062); color: #fff; box-shadow: 0 12px 26px rgba(220,54,119,.2); }
    .hero-search button:hover, .primary-link:hover { transform: translateY(-2px); box-shadow: 0 16px 30px rgba(220,54,119,.26); }
    .hero-actions { display: flex; flex-wrap: wrap; gap: .7rem; }
    .secondary-link { border: 1px solid rgba(143,106,127,.38); background: rgba(255,253,249,.78); color: var(--market-accent-dark); }
    .secondary-link:hover { background: var(--market-accent-soft); transform: translateY(-2px); }

    .path-pills { display: flex; flex-wrap: wrap; gap: .85rem 1.2rem; margin-top: .2rem; }
    .path-pills span { display: inline-flex; align-items: center; gap: .38rem; color: var(--market-muted); font-size: .74rem; }
    .path-pills strong { color: var(--market-ink); }
    .dot { width: 7px; height: 7px; border-radius: 50%; }
    .dot.individual { background: var(--market-accent); }
    .dot.business { background: var(--market-jade); }

    .featured-card {
      position: absolute;
      z-index: 4;
      right: clamp(1rem, 3vw, 2.5rem);
      bottom: clamp(1rem, 3vw, 2.5rem);
      width: min(340px, 34%);
      display: grid;
      grid-template-columns: 1fr auto;
      gap: .2rem .8rem;
      padding: 1rem 1.1rem;
      border: 1px solid rgba(255,255,255,.72);
      border-radius: 22px 6px 22px 6px;
      border-color: rgba(233,79,138,.2);
      background: rgba(255,255,255,.88);
      color: var(--market-ink);
      text-decoration: none;
      box-shadow: 0 20px 48px rgba(208,75,126,.18);
      backdrop-filter: blur(18px);
    }

    .featured-kicker { grid-column: 1 / -1; color: var(--market-accent-dark); font-size: .65rem; font-weight: 760; letter-spacing: .12em; text-transform: uppercase; }
    .featured-card strong, .featured-card small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .featured-card strong { color: var(--market-ink); font-family: var(--font-market-display); font-size: 1.05rem; font-weight: 600; }
    .featured-card small { color: var(--market-muted); }
    .featured-card p { grid-column: 2; grid-row: 2 / 4; align-self: center; margin: 0; color: var(--market-accent-dark); font-weight: 780; }

    .hero-frame { position: absolute; z-index: 3; width: 86px; height: 86px; pointer-events: none; }
    .hero-frame-top { top: 20px; right: 20px; border-top: 1px solid rgba(255,255,255,.58); border-right: 1px solid rgba(255,255,255,.58); }
    .hero-frame-bottom { bottom: 20px; left: 20px; border-bottom: 1px solid rgba(143,106,127,.32); border-left: 1px solid rgba(143,106,127,.32); }

    @media (max-width: 1050px) {
      .hero-copy { width: 62%; }
      .hero-section::before { background: linear-gradient(90deg, rgba(255,255,255,.98) 0%, rgba(255,247,251,.9) 43%, rgba(255,242,248,.14) 72%); }
      .featured-card { width: min(300px, 31%); }
    }

    @media (max-width: 760px) {
      .hero-section { min-height: 770px; border-radius: 26px 8px 26px 8px; }
      .hero-section::before { background: linear-gradient(180deg, rgba(255,255,255,.99) 0%, rgba(255,247,251,.96) 43%, rgba(255,239,247,.16) 68%, rgba(255,206,226,.1) 100%); }
      .hero-copy { width: 100%; min-height: auto; justify-content: flex-start; padding: 1.7rem 1.25rem; }
      h1 { font-size: clamp(3rem, 13vw, 4.5rem); }
      .summary { max-width: 520px; }
      .hero-search { grid-template-columns: 1fr; }
      .hero-search button { width: 100%; }
      .hero-actions > * { flex: 1 1 145px; padding-inline: .8rem; }
      .path-pills { display: none; }
      .featured-card { display: none; }
      .hero-frame { display: none; }
    }

    @media (max-width: 430px) {
      .hero-section { min-height: 745px; }
      .hero-copy { gap: .82rem; padding: 1.35rem 1rem; }
      .eyebrow { font-size: .62rem; }
      h1 { font-size: clamp(2.7rem, 14vw, 3.65rem); }
      .summary { font-size: .92rem; line-height: 1.5; }
      .hero-search input { font-size: .9rem; }
      .featured-card { padding: .78rem .85rem; }
    }

    @media (prefers-reduced-motion: reduce) {
      .hero-search button, .primary-link, .secondary-link { transition: none; }
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
