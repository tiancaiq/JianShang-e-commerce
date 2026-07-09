import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BrandMascotComponent } from '../../shared/components/ui/brand-mascot.component';

@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [BrandMascotComponent, RouterLink],
  template: `
    <main class="not-found-page">
      <section class="not-found-card" aria-labelledby="not-found-title">
        <div class="not-found-copy">
          <p class="eyebrow">Lost shelf</p>
          <h1 id="not-found-title">This page wandered off.</h1>
          <p>
            The mascot could not find that marketplace aisle. Head back to approved individual listings and keep browsing.
          </p>
          <a routerLink="/marketplace">Back to marketplace</a>
        </div>
        <app-brand-mascot variant="login" alt="MSB marketplace mascot on the not found page" />
      </section>
    </main>
  `,
  styles: [`
    .not-found-page {
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: clamp(1rem, 4vw, 2rem);
      background:
        radial-gradient(circle at 12% 8%, rgba(255, 207, 228, 0.38) 0 18%, transparent 19%),
        radial-gradient(circle at 90% 0%, rgba(206, 193, 255, 0.34) 0 16%, transparent 17%),
        linear-gradient(180deg, #fff7fb 0%, #f8f0ff 100%);
      color: #37214b;
    }

    .not-found-card {
      width: min(100%, 980px);
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(280px, 420px);
      gap: 1rem;
      align-items: stretch;
      padding: clamp(1rem, 4vw, 2rem);
      border: 1px solid rgba(234, 215, 242, 0.94);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.92);
      box-shadow: 0 24px 70px rgba(132, 83, 143, 0.18);
    }

    .not-found-copy {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 1rem;
    }

    .eyebrow {
      margin: 0;
      color: #be3a83;
      font-size: 0.78rem;
      font-weight: 900;
      letter-spacing: 0.08em;
      text-transform: uppercase;
    }

    h1 {
      margin: 0;
      color: #37214b;
      font-size: clamp(2.2rem, 6vw, 4.6rem);
      line-height: 0.98;
      letter-spacing: 0;
    }

    p {
      max-width: 520px;
      margin: 0;
      color: #7e6d96;
      font-weight: 750;
      line-height: 1.55;
    }

    a {
      width: fit-content;
      min-height: 42px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 1rem;
      border-radius: 8px;
      background: linear-gradient(135deg, #f472b6, #8b6fe8);
      color: #fff;
      font-weight: 900;
      text-decoration: none;
      box-shadow: 0 14px 26px rgba(190, 58, 131, 0.2);
    }

    @media (max-width: 760px) {
      .not-found-card {
        grid-template-columns: 1fr;
      }
    }
  `],
})
export class NotFoundComponent {}
