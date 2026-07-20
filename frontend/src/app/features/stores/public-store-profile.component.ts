import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BusinessStore } from '../../core/models/business-store.model';
import { BusinessStoreService } from '../../core/services/business-store.service';

@Component({
  selector: 'app-public-store-profile',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="store-page">
      @if (loading()) {
        <p class="state">Loading store profile.</p>
      } @else if (errorMsg()) {
        <div class="state error">
          <strong>Store unavailable</strong>
          <p>{{ errorMsg() }}</p>
          <a routerLink="/stores">Browse business items</a>
        </div>
      } @else if (store(); as current) {
        <header class="store-header" [style.background-image]="bannerStyle(current)">
          <div class="store-identity">
            <div class="store-logo">
              @if (current.logoUrl) {
                <img [src]="current.logoUrl" [alt]="current.name + ' logo'" />
              } @else {
                <span>{{ storeInitial(current) }}</span>
              }
            </div>
            <div>
              <p class="verification">Verified business</p>
              <h1>{{ current.name }}</h1>
              <p class="slug">{{ '@' + current.slug }}</p>
            </div>
          </div>
        </header>

        <section class="store-body">
          <div>
            <h2>About this store</h2>
            <p>{{ current.description || 'This verified business has not added a public description yet.' }}</p>
          </div>

          <dl>
            <div>
              <dt>Status</dt>
              <dd>Verified</dd>
            </div>
            <div>
              <dt>Location</dt>
              <dd>{{ current.publicCity }}, {{ current.publicRegion }}</dd>
            </div>
            @if (current.supportEmail) {
              <div>
                <dt>Support email</dt>
                <dd><a [href]="'mailto:' + current.supportEmail">{{ current.supportEmail }}</a></dd>
              </div>
            }
            @if (current.supportPhone) {
              <div>
                <dt>Support phone</dt>
                <dd>{{ current.supportPhone }}</dd>
              </div>
            }
          </dl>

          <a routerLink="/stores" class="catalog-link">Browse business catalog</a>
        </section>
      }
    </section>
  `,
  styles: [`
    :host {
      display: block;
    }

    .store-page {
      width: min(100%, 1120px);
      margin: 0 auto;
      display: grid;
      gap: 1rem;
    }

    .store-header,
    .store-body,
    .state {
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.96);
    }

    .store-header {
      min-height: 260px;
      display: flex;
      align-items: flex-end;
      padding: 1.5rem;
      background-color: #f7eef8;
      background-position: center;
      background-size: cover;
    }

    .store-identity {
      width: 100%;
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid rgba(255, 255, 255, 0.72);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
    }

    .store-logo {
      width: 80px;
      aspect-ratio: 1;
      display: grid;
      place-items: center;
      flex: 0 0 auto;
      overflow: hidden;
      border: 1px solid var(--market-line);
      border-radius: 8px;
      background: #fff;
      color: var(--market-accent-dark);
      font-size: 1.8rem;
      font-weight: 950;
    }

    .store-logo img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    h1,
    h2,
    p,
    dl {
      margin: 0;
    }

    h1 {
      color: var(--market-ink);
      font-size: clamp(1.8rem, 4vw, 3rem);
      letter-spacing: 0;
    }

    h2 {
      color: var(--market-ink);
      font-size: 1.1rem;
      letter-spacing: 0;
    }

    .verification {
      color: #14785f;
      font-size: 0.75rem;
      font-weight: 950;
      text-transform: uppercase;
    }

    .slug,
    .store-body p,
    dt {
      color: var(--market-muted);
      font-weight: 700;
    }

    .store-body {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(260px, 0.8fr);
      gap: 1.5rem;
      padding: 1.5rem;
    }

    .store-body > div {
      display: grid;
      gap: 0.6rem;
      align-content: start;
    }

    .store-body p {
      line-height: 1.6;
    }

    dl {
      display: grid;
      gap: 0.8rem;
    }

    dl div {
      display: grid;
      gap: 0.2rem;
    }

    dt {
      font-size: 0.75rem;
      text-transform: uppercase;
    }

    dd {
      margin: 0;
      color: var(--market-ink);
      font-weight: 850;
      overflow-wrap: anywhere;
    }

    dd a,
    .catalog-link,
    .state a {
      color: var(--market-accent-dark);
      font-weight: 900;
      text-underline-offset: 3px;
    }

    .catalog-link {
      grid-column: 1 / -1;
      justify-self: start;
    }

    .state {
      padding: 1.5rem;
      color: var(--market-muted);
    }

    .state.error {
      display: grid;
      gap: 0.5rem;
      color: #b4234f;
    }

    @media (max-width: 720px) {
      .store-header {
        min-height: 220px;
        padding: 1rem;
      }

      .store-identity,
      .store-body {
        grid-template-columns: 1fr;
      }

      .store-identity {
        align-items: flex-start;
      }

      .store-body {
        padding: 1rem;
      }
    }
  `],
})
export class PublicStoreProfileComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly storeService = inject(BusinessStoreService);

  readonly loading = signal(true);
  readonly errorMsg = signal('');
  readonly store = signal<BusinessStore | null>(null);

  ngOnInit(): void {
    const slug = this.route.snapshot.paramMap.get('storeSlug') || '';
    if (!slug) {
      this.loading.set(false);
      this.errorMsg.set('This store could not be found.');
      return;
    }
    this.storeService.getPublicStore(slug).subscribe({
      next: store => {
        this.store.set(store);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.errorMsg.set('This store profile is not available.');
      },
    });
  }

  bannerStyle(store: BusinessStore): string {
    return store.bannerUrl ? `url("${store.bannerUrl.replace(/"/g, '%22')}")` : 'none';
  }

  storeInitial(store: BusinessStore): string {
    return store.name.trim().charAt(0).toUpperCase() || 'S';
  }
}
