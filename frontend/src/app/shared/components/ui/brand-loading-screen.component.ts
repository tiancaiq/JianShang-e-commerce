import { Component, Input } from '@angular/core';
import { BrandMascotComponent } from './brand-mascot.component';

@Component({
  selector: 'app-brand-loading-screen',
  standalone: true,
  imports: [BrandMascotComponent],
  template: `
    <section class="brand-loading-screen" aria-live="polite" aria-busy="true">
      <app-brand-mascot variant="badge" alt="MSB marketplace mascot" />
      <div>
        <strong>{{ label }}</strong>
        <p>{{ detail }}</p>
      </div>
    </section>
  `,
  styles: [`
    .brand-loading-screen {
      min-height: 260px;
      display: grid;
      place-items: center;
      gap: 0.85rem;
      padding: 1.5rem;
      border: 1px dashed rgba(244, 114, 182, 0.35);
      border-radius: 8px;
      background:
        linear-gradient(135deg, rgba(255, 248, 252, 0.94), rgba(246, 239, 255, 0.94)),
        #fff8fc;
      color: var(--market-muted, #7e6d96);
      text-align: center;
    }

    strong {
      display: block;
      color: var(--market-ink, #37214b);
      font-size: 1rem;
      font-weight: 950;
    }

    p {
      max-width: 340px;
      margin: 0.25rem auto 0;
      font-size: 0.88rem;
      font-weight: 750;
    }
  `],
})
export class BrandLoadingScreenComponent {
  @Input() label = 'Loading';
  @Input() detail = 'The marketplace mascot is arranging the newest finds.';
}
