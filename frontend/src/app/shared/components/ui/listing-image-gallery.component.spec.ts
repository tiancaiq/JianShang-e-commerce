import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ListingService } from '../../../core/services/listing.service';
import { publicListingImage } from '../../../testing/listing-test-fixtures';
import { ListingImageGalleryComponent } from './listing-image-gallery.component';

describe('ListingImageGalleryComponent', () => {
  let fixture: ComponentFixture<ListingImageGalleryComponent>;
  let component: ListingImageGalleryComponent;
  let listingService: jasmine.SpyObj<ListingService>;

  const firstImage = publicListingImage();
  const secondImage = publicListingImage({
    id: '01I00000000000000000000002',
    displayOrder: 1,
    url: '/api/v1/public/listing-media/01I00000000000000000000002',
  });

  beforeEach(async () => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', ['mediaUrl']);
    listingService.mediaUrl.and.callFake(url => url || '');

    await TestBed.configureTestingModule({
      imports: [ListingImageGalleryComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ListingService, useValue: listingService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ListingImageGalleryComponent);
    component = fixture.componentInstance;
    component.fallbackAlt = 'Used bicycle';
  });

  it('renders a placeholder when there are no public images', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No public images');
    expect(fixture.nativeElement.querySelector('.image-arrow')).toBeNull();
  });

  it('changes the primary image from the dark next arrow', () => {
    component.images = [firstImage, secondImage];
    fixture.detectChanges();

    const nextButton = fixture.nativeElement.querySelector('.primary-image .image-arrow.next') as HTMLButtonElement;
    nextButton.click();
    fixture.detectChanges();

    const imageTrack = fixture.nativeElement.querySelector('.image-track') as HTMLElement;
    expect(component.selectedImage()?.id).toBe(secondImage.id);
    expect(imageTrack.style.transform).toBe('translateX(-100%)');
  });

  it('slides the image track when the image changes', () => {
    component.images = [firstImage, secondImage];
    fixture.detectChanges();

    const imageTrack = fixture.nativeElement.querySelector('.image-track') as HTMLElement;
    expect(imageTrack.style.transform).toBe('translateX(0%)');

    const nextButton = fixture.nativeElement.querySelector('.primary-image .image-arrow.next') as HTMLButtonElement;
    nextButton.click();
    fixture.detectChanges();

    expect(imageTrack.style.transform).toBe('translateX(-100%)');
  });

  it('auto-advances the primary image every five seconds', () => {
    jasmine.clock().install();
    try {
      component.images = [firstImage, secondImage];
      fixture.detectChanges();

      jasmine.clock().tick(5000);
      fixture.detectChanges();

      const imageTrack = fixture.nativeElement.querySelector('.image-track') as HTMLElement;
      expect(component.selectedImage()?.id).toBe(secondImage.id);
      expect(imageTrack.style.transform).toBe('translateX(-100%)');
    } finally {
      jasmine.clock().uninstall();
    }
  });
});
