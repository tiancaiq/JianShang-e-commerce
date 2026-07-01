import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, inject, signal } from '@angular/core';
import { PublicListingImage } from '../../../core/models/listing.model';
import { ListingService } from '../../../core/services/listing.service';
import { publicListingImageUrl } from '../../listing/public-listing-display';

@Component({
  selector: 'app-listing-image-gallery',
  standalone: true,
  template: `
    @if (images.length) {
      <figure class="primary-image">
        <div class="image-track" [style.transform]="trackTransform()">
          @for (image of images; track image.id) {
            <img [src]="imageUrl(image)" [alt]="image.altText || fallbackAlt || 'Listing image'" />
          }
        </div>
        @if (hasMultipleImages()) {
          <button type="button" class="image-arrow previous" aria-label="Previous listing image" (click)="showPreviousImage()">
            &#8249;
          </button>
          <button type="button" class="image-arrow next" aria-label="Next listing image" (click)="showNextImage()">
            &#8250;
          </button>
        }
      </figure>
      @if (hasMultipleImages()) {
        <div class="thumb-grid">
          @for (image of images; track image.id; let index = $index) {
            <button
              type="button"
              [class.active]="selectedImageIndex() === index"
              [attr.aria-current]="selectedImageIndex() === index ? 'true' : null"
              (click)="selectImage(index)"
            >
              <img [src]="imageUrl(image)" [alt]="image.altText || image.originalFileName || fallbackAlt || 'Listing image'" />
            </button>
          }
        </div>
      }
    } @else {
      <div class="image-placeholder">No public images</div>
    }
  `,
  styles: [`
    figure {
      margin: 0;
    }

    .primary-image,
    .image-placeholder {
      position: relative;
      min-height: 520px;
      overflow: hidden;
      border-radius: 8px;
      background: linear-gradient(135deg, rgba(255, 226, 240, 0.92), rgba(239, 232, 255, 0.92));
    }

    .image-track {
      display: flex;
      width: 100%;
      min-height: inherit;
      transition: transform 360ms cubic-bezier(0.22, 1, 0.36, 1);
      will-change: transform;
    }

    .primary-image img,
    .thumb-grid img {
      width: 100%;
      height: 100%;
      display: block;
      flex: 0 0 100%;
      object-fit: cover;
    }

    .primary-image img {
      min-height: inherit;
    }

    .image-arrow {
      position: absolute;
      top: 50%;
      z-index: 2;
      width: 42px;
      height: 52px;
      transform: translateY(-50%);
      border: 0;
      border-radius: 8px;
      background: rgba(35, 22, 43, 0.78);
      color: #fff;
      cursor: pointer;
      font-size: 2rem;
      font-weight: 900;
      line-height: 1;
    }

    .image-arrow.previous {
      left: 0.75rem;
    }

    .image-arrow.next {
      right: 0.75rem;
    }

    .image-arrow:hover,
    .image-arrow:focus-visible {
      background: rgba(35, 22, 43, 0.92);
      outline: 2px solid rgba(255, 255, 255, 0.8);
      outline-offset: 2px;
    }

    .thumb-grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.75rem;
    }

    .thumb-grid button {
      aspect-ratio: 1 / 1;
      overflow: hidden;
      border-radius: 8px;
      border: 2px solid transparent;
      padding: 0;
      background: linear-gradient(135deg, #ffe5f0, #efe8ff);
      cursor: pointer;
    }

    .thumb-grid button.active {
      border-color: var(--market-accent);
      box-shadow: 0 0 0 3px rgba(255, 126, 179, 0.2);
    }

    .image-placeholder {
      display: grid;
      place-items: center;
      color: var(--market-muted);
      font-weight: 850;
    }

    @media (max-width: 980px) {
      .primary-image,
      .image-placeholder {
        min-height: 360px;
      }
    }

    @media (max-width: 640px) {
      .thumb-grid {
        grid-template-columns: 1fr;
      }

      .primary-image,
      .image-placeholder {
        min-height: 260px;
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .image-track {
        transition: none;
      }
    }
  `],
})
export class ListingImageGalleryComponent implements OnChanges, OnDestroy, OnInit {
  private readonly listingService = inject(ListingService);
  private carouselTimer: ReturnType<typeof setInterval> | null = null;

  @Input() images: PublicListingImage[] = [];
  @Input() fallbackAlt = '';

  selectedImageIndex = signal(0);

  ngOnInit(): void {
    this.startImageCarousel();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['images']) {
      this.selectedImageIndex.set(0);
      this.startImageCarousel();
    }
  }

  ngOnDestroy(): void {
    this.stopImageCarousel();
  }

  imageUrl(image: PublicListingImage | undefined): string {
    return publicListingImageUrl(image, url => this.listingService.mediaUrl(url));
  }

  selectedImage(): PublicListingImage | undefined {
    return this.images[this.selectedImageIndex()] || this.images[0];
  }

  trackTransform(): string {
    return `translateX(-${this.selectedImageIndex() * 100}%)`;
  }

  selectImage(index: number): void {
    this.selectedImageIndex.set(index);
  }

  hasMultipleImages(): boolean {
    return this.images.length > 1;
  }

  showPreviousImage(): void {
    this.shiftSelectedImage(-1);
  }

  showNextImage(): void {
    this.shiftSelectedImage(1);
  }

  private startImageCarousel(): void {
    this.stopImageCarousel();
    if (this.hasMultipleImages()) {
      this.carouselTimer = setInterval(() => this.shiftSelectedImage(1), 5000);
    }
  }

  private stopImageCarousel(): void {
    if (this.carouselTimer) {
      clearInterval(this.carouselTimer);
      this.carouselTimer = null;
    }
  }

  private shiftSelectedImage(direction: 1 | -1): void {
    if (!this.hasMultipleImages()) {
      return;
    }
    this.selectedImageIndex.set((this.selectedImageIndex() + direction + this.images.length) % this.images.length);
  }
}
