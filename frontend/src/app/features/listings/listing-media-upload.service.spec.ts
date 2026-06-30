import { of, throwError } from 'rxjs';
import { ListingImage, ListingMedia } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { ListingMediaUploadError, ListingMediaUploadService } from './listing-media-upload.service';

describe('ListingMediaUploadService', () => {
  let listingService: jasmine.SpyObj<ListingService>;
  let service: ListingMediaUploadService;

  const listingId = '01L00000000000000000000001';
  const file = new File(['x'], 'bike.png', { type: 'image/png' });
  const pendingMedia = {
    id: '01M00000000000000000000001',
    uploadUrl: 'local-demo://listing-media-local/bike.png',
    originalFileName: 'bike.png',
  } as ListingMedia;
  const confirmedMedia = {
    ...pendingMedia,
    uploadStatus: 'UPLOADED',
  } as ListingMedia;
  const attachedImage = {
    id: '01I00000000000000000000001',
    mediaObjectId: pendingMedia.id,
  } as ListingImage;

  beforeEach(() => {
    listingService = jasmine.createSpyObj<ListingService>('ListingService', [
      'requestMediaUpload',
      'uploadMediaFile',
      'confirmMediaUpload',
      'updateListingImages',
    ]);
    listingService.requestMediaUpload.and.returnValue(of(pendingMedia));
    listingService.uploadMediaFile.and.returnValue(of(undefined));
    listingService.confirmMediaUpload.and.returnValue(of(confirmedMedia));
    listingService.updateListingImages.and.returnValue(of([attachedImage]));

    service = new ListingMediaUploadService(listingService);
  });

  it('requests, uploads, confirms, and attaches listing media', done => {
    service.uploadAndAttach(listingId, file, []).subscribe(images => {
      expect(images).toEqual([attachedImage]);
      expect(listingService.requestMediaUpload).toHaveBeenCalledOnceWith(listingId, {
        contentType: 'image/png',
        fileName: 'bike.png',
        sizeBytes: 1,
      });
      expect(listingService.uploadMediaFile).toHaveBeenCalledOnceWith(pendingMedia.uploadUrl, file);
      expect(listingService.confirmMediaUpload).toHaveBeenCalledOnceWith(listingId, pendingMedia.id, {
        sizeBytes: 1,
      });
      expect(listingService.updateListingImages).toHaveBeenCalledOnceWith(listingId, {
        images: [{ mediaId: confirmedMedia.id, altText: confirmedMedia.originalFileName }],
      });
      done();
    });
  });

  it('labels the failed upload workflow step', done => {
    listingService.uploadMediaFile.and.returnValue(throwError(() => new Error('storage failed')));

    service.uploadAndAttach(listingId, file, []).subscribe({
      next: () => fail('expected upload to fail'),
      error: error => {
        expect(error instanceof ListingMediaUploadError).toBeTrue();
        expect((error as ListingMediaUploadError).step).toBe('upload');
        done();
      },
    });
  });
});
