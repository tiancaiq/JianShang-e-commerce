import { Component, Input } from '@angular/core';

type BrandMascotVariant = 'hero' | 'login' | 'panel' | 'badge' | 'footer' | 'card';

@Component({
  selector: 'app-brand-mascot',
  standalone: true,
  template: `
    <figure
      class="brand-mascot"
      role="img"
      [attr.aria-label]="alt"
      [class.hero]="variant === 'hero'"
      [class.login]="variant === 'login'"
      [class.panel]="variant === 'panel'"
      [class.badge]="variant === 'badge'"
      [class.footer]="variant === 'footer'"
      [class.card]="variant === 'card'"
    ></figure>
  `,
  styles: [`
    .brand-mascot {
      width: 100%;
      min-width: 0;
      margin: 0;
      overflow: hidden;
      border: 1px solid rgba(255, 255, 255, 0.78);
      border-radius: 8px;
      background:
        linear-gradient(135deg, rgba(255, 229, 242, 0.18), rgba(239, 232, 255, 0.18)),
        url('/marketplace/brand-mascot.png') 0% 0% / 315% auto no-repeat;
      box-shadow: 0 18px 42px rgba(132, 83, 143, 0.16);
    }

    .hero {
      min-height: 320px;
      height: 100%;
      aspect-ratio: 4 / 3;
    }

    .login {
      aspect-ratio: 4 / 3;
      min-height: 260px;
    }

    .panel {
      aspect-ratio: 16 / 9;
      min-height: 180px;
    }

    .badge {
      width: 92px;
      height: 92px;
      border-radius: 999px;
      box-shadow: 0 12px 28px rgba(190, 58, 131, 0.2);
    }

    .footer {
      aspect-ratio: 16 / 7;
      min-height: 170px;
    }

    .card {
      aspect-ratio: 4 / 3;
      min-height: 132px;
    }

    @media (max-width: 640px) {
      .hero,
      .login {
        min-height: 230px;
      }

      .footer {
        min-height: 150px;
      }
    }
  `],
})
export class BrandMascotComponent {
  @Input() variant: BrandMascotVariant = 'panel';
  @Input() alt = 'MSB marketplace brand mascot';
}
