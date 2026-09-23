import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-editorial-artwork',
  standalone: true,
  template: `
    <figure
      class="editorial-artwork"
      [class.decorative]="decorative"
      [class.overlay-left]="overlay === 'left'"
      [class.overlay-bottom]="overlay === 'bottom'"
      [class.mobile-lower-panel]="mobileComposition === 'lower-panel'"
      [style.--art-focal-point]="focalPoint"
      [style.--art-mobile-focal-point]="mobileFocalPoint"
      [style.--art-overlay-strength]="overlayStrength"
    >
      <img [src]="src" [alt]="decorative ? '' : alt" [attr.aria-hidden]="decorative ? 'true' : null" />
      <span class="artwork-veil" aria-hidden="true"></span>
      <span class="artwork-glow" aria-hidden="true"></span>
    </figure>
  `,
  styles: [`
    :host,
    .editorial-artwork {
      display: block;
      width: 100%;
      height: 100%;
      min-width: 0;
      min-height: 0;
    }

    .editorial-artwork {
      --art-focal-point: 72% center;
      --art-mobile-focal-point: 72% center;
      --art-overlay-strength: 0.76;
      position: relative;
      margin: 0;
      overflow: hidden;
      isolation: isolate;
    }

    img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      display: block;
      object-fit: cover;
      object-position: var(--art-focal-point);
      filter: saturate(.92) contrast(.98);
      transform: scale(1.002);
    }

    .artwork-veil,
    .artwork-glow {
      position: absolute;
      inset: 0;
      pointer-events: none;
    }

    .artwork-veil {
      z-index: 1;
      background: linear-gradient(
        90deg,
        rgba(255, 252, 248, var(--art-overlay-strength)) 0%,
        rgba(255, 252, 248, .38) 20%,
        transparent 48%
      );
    }

    .overlay-bottom .artwork-veil {
      background: linear-gradient(0deg, rgba(255, 252, 248, .72), transparent 45%);
    }

    .artwork-glow {
      z-index: 2;
      background:
        radial-gradient(circle at 66% 18%, rgba(255, 252, 241, .25), transparent 27%),
        linear-gradient(180deg, rgba(255,255,255,.08), transparent 55%, rgba(76,46,63,.08));
      mix-blend-mode: screen;
    }

    @media (max-width: 700px) {
      img { object-position: var(--art-mobile-focal-point); }

      .mobile-lower-panel img {
        top: auto;
        bottom: 0;
        height: 42%;
        object-position: var(--art-mobile-focal-point);
      }

      .artwork-veil {
        background: linear-gradient(180deg, rgba(255,252,248,.82) 0%, transparent 30%, transparent 74%, rgba(76,46,63,.16) 100%);
      }

      .mobile-lower-panel .artwork-veil {
        background: linear-gradient(180deg, #fffdf9 0%, #fffdf9 50%, rgba(255,253,249,.62) 61%, transparent 76%, rgba(49,33,45,.12) 100%);
      }
    }
  `],
})
export class EditorialArtworkComponent {
  @Input({ required: true }) src = '';
  @Input() alt = '';
  @Input() decorative = false;
  @Input() focalPoint = '72% center';
  @Input() mobileFocalPoint = '72% center';
  @Input() mobileComposition: 'full' | 'lower-panel' = 'full';
  @Input() overlay: 'left' | 'bottom' | 'none' = 'left';
  @Input() overlayStrength = '0.76';
}
