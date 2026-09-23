import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-brand-loading-screen',
  standalone: true,
  template: `
    <section class="brand-loading-screen" aria-live="polite" aria-busy="true">
      <div class="loading-heading">
        <span class="loading-mark" aria-hidden="true"></span>
        <strong>{{ label }}</strong>
        <p>{{ detail }}</p>
      </div>
      <div class="skeleton-grid" aria-hidden="true">
        @for (item of skeletonItems; track item) {
          <article class="skeleton-card">
            <span class="skeleton-image"></span>
            <span class="skeleton-line short"></span>
            <span class="skeleton-line"></span>
            <span class="skeleton-line price"></span>
          </article>
        }
      </div>
    </section>
  `,
  styles: [`
    .brand-loading-screen {
      display: grid;
      gap: 1rem;
      padding: 0.25rem 0;
      color: var(--market-muted, #607275);
    }

    .loading-heading {
      display: grid;
      grid-template-columns: 0.75rem minmax(0, 1fr);
      column-gap: 0.65rem;
      align-items: center;
    }

    .loading-mark {
      width: 0.65rem;
      height: 0.65rem;
      grid-row: 1 / 3;
      border: 2px solid var(--market-line-strong, #b8c8c5);
      border-top-color: var(--market-accent, #0d7c75);
      border-radius: 50%;
      animation: loading-spin 900ms linear infinite;
    }

    strong {
      color: var(--market-ink, #142f32);
      font-size: 1rem;
      font-weight: 800;
    }

    p {
      margin: 0.08rem 0 0;
      font-size: 0.82rem;
    }

    .skeleton-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.85rem;
    }

    .skeleton-card {
      display: grid;
      gap: 0.65rem;
      padding: 0.65rem;
      border: 1px solid var(--market-line, #d6e0de);
      border-radius: var(--market-radius-md, 14px);
      background: var(--market-surface, #fff);
    }

    .skeleton-image,
    .skeleton-line {
      display: block;
      border-radius: 7px;
      background: linear-gradient(100deg, #edf2f1 25%, #f8faf9 42%, #edf2f1 58%);
      background-size: 240% 100%;
      animation: loading-shimmer 1.4s ease-in-out infinite;
    }

    .skeleton-image {
      aspect-ratio: 4 / 3;
    }

    .skeleton-line {
      height: 0.75rem;
    }

    .skeleton-line.short {
      width: 46%;
    }

    .skeleton-line.price {
      width: 64%;
      height: 1rem;
    }

    @keyframes loading-spin {
      to { transform: rotate(360deg); }
    }

    @keyframes loading-shimmer {
      to { background-position-x: -240%; }
    }

    @media (max-width: 900px) {
      .skeleton-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }

    @media (max-width: 540px) {
      .skeleton-grid { grid-template-columns: 1fr; }
      .skeleton-card:nth-child(n + 3) { display: none; }
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-mark,
      .skeleton-line,
      .skeleton-image { animation: none; }
    }
  `],
})
export class BrandLoadingScreenComponent {
  @Input() label = 'Loading';
  @Input() detail = 'Loading current marketplace information.';
  readonly skeletonItems = [1, 2, 3, 4];
}
