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
    >
      <img [src]="assetUrl" alt="" aria-hidden="true" />
    </figure>
  `,
  styles: [`
    .brand-mascot {
      position: relative;
      display: grid;
      place-items: center;
      width: 100%;
      min-width: 0;
      margin: 0;
      overflow: hidden;
      border: 1px solid rgba(139, 114, 232, 0.18);
      border-radius: 30px 12px 30px 12px;
      background: linear-gradient(145deg, #fff8fc, #eee9ff);
      box-shadow: 0 18px 42px rgba(93, 52, 112, 0.14);
    }

    .brand-mascot::after {
      position: absolute;
      right: 8%;
      bottom: 7%;
      width: 34px;
      aspect-ratio: 1;
      border-radius: 50%;
      background: #72d9bd;
      content: '';
      opacity: 0.7;
    }

    img {
      position: relative;
      z-index: 1;
      width: min(100%, 520px);
      height: 100%;
      object-fit: cover;
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
      box-shadow: 0 12px 28px rgba(78, 66, 89, 0.15);
    }

    .badge img,
    .card img {
      object-fit: contain;
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

  get assetUrl(): string {
    if (this.variant === 'badge' || this.variant === 'card') {
      return '/assets/brand/anime/assistant-avatar.webp';
    }
    if (this.variant === 'hero') {
      return '/assets/brand/anime/marketplace-hero-anime.webp';
    }
    if (this.variant === 'login') {
      return '/assets/brand/anime/auth-shopping-scene.webp';
    }
    return '/assets/brand/anime/seller-studio-anime.webp';
  }
}
