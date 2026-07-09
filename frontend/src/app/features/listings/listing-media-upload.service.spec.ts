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
      'requestBusinessStoreItemMediaUpload',
      'uploadMediaFile',
      'confirmMediaUpload',
      'confirmBusinessStoreItemMediaUpload',
      'updateListingImages',
      'updateBusinessStoreItemImages',
    ]);
    listingService.requestMediaUpload.and.returnValue(of(pendingMedia));
    listingService.requestBusinessStoreItemMediaUpload.and.returnValue(of(pendingMedia));
    listingService.uploadMediaFile.and.returnValue(of(undefined));
    listingService.confirmMediaUpload.and.returnValue(of(confirmedMedia));
    listingService.confirmBusinessStoreItemMediaUpload.and.returnValue(of(confirmedMedia));
    listingService.updateListingImages.and.returnValue(of([attachedImage]));
    listingService.updateBusinessStoreItemImages.and.returnValue(of([attachedImage]));

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

  it('uses business-scoped store item media endpoints when a business id is supplied', done => {
    const businessId = '01B00000000000000000000001';

    service.uploadAndAttach(listingId, file, [], businessId).subscribe(images => {
      expect(images).toEqual([attachedImage]);
      expect(listingService.requestBusinessStoreItemMediaUpload).toHaveBeenCalledOnceWith(businessId, listingId, {
        contentType: 'image/png',
        fileName: 'bike.png',
        sizeBytes: 1,
      });
      expect(listingService.confirmBusinessStoreItemMediaUpload).toHaveBeenCalledOnceWith(businessId, listingId, pendingMedia.id, {
        sizeBytes: 1,
      });
      expect(listingService.updateBusinessStoreItemImages).toHaveBeenCalledOnceWith(businessId, listingId, {
        images: [{ mediaId: confirmedMedia.id, altText: confirmedMedia.originalFileName }],
      });
      expect(listingService.requestMediaUpload).not.toHaveBeenCalled();
      expect(listingService.confirmMediaUpload).not.toHaveBeenCalled();
      expect(listingService.updateListingImages).not.toHaveBeenCalled();
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
