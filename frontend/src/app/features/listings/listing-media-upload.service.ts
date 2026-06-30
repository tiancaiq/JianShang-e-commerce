import { Injectable } from '@angular/core';
import { catchError, concatMap, from, map, Observable, reduce, switchMap, tap, throwError } from 'rxjs';
import { ListingImage, ListingMedia } from '../../core/models/listing.model';
import { ListingService } from '../../core/services/listing.service';
import { appendConfirmedMediaToImages } from './listing-draft-form.helpers';

export type ListingMediaUploadFailureStep = 'request' | 'upload' | 'confirm' | 'attach';

export class ListingMediaUploadError extends Error {
  constructor(
    readonly step: ListingMediaUploadFailureStep,
    readonly originalError: unknown,
  ) {
    super(`Listing media upload failed during ${step}.`);
  }
}

@Injectable({ providedIn: 'root' })
export class ListingMediaUploadService {
  constructor(private listingService: ListingService) {}

  // Runs the full media upload workflow and returns the updated listing images.
  uploadAndAttach(listingId: string, file: File, currentImages: ListingImage[]): Observable<ListingImage[]> {
    return this.listingService.requestMediaUpload(listingId, {
      contentType: file.type,
      fileName: file.name,
      sizeBytes: file.size,
    }).pipe(
      catchError(error => this.fail('request', error)),
      switchMap(media => this.uploadBytes(file, media)),
      switchMap(media => this.confirmUpload(listingId, file, media)),
      switchMap(media => this.attachImage(listingId, currentImages, media)),
    );
  }

  // Uploads files one-by-one so the ordered listing image set is updated predictably.
  uploadAndAttachMany(listingId: string, files: File[], currentImages: ListingImage[]): Observable<ListingImage[]> {
    let images = currentImages;
    return from(files).pipe(
      concatMap(file => this.uploadAndAttach(listingId, file, images).pipe(
        tap(updatedImages => {
          images = updatedImages;
        }),
      )),
      reduce((_, updatedImages) => updatedImages, currentImages),
    );
  }

  // Uploads selected bytes to the storage URL issued by the backend.
  private uploadBytes(file: File, media: ListingMedia): Observable<ListingMedia> {
    return this.listingService.uploadMediaFile(media.uploadUrl, file).pipe(
      map(() => media),
      catchError(error => this.fail('upload', error)),
    );
  }

  // Confirms storage upload metadata before the image can be attached to the listing.
  private confirmUpload(listingId: string, file: File, media: ListingMedia): Observable<ListingMedia> {
    return this.listingService.confirmMediaUpload(listingId, media.id, {
      sizeBytes: file.size,
    }).pipe(
      catchError(error => this.fail('confirm', error)),
    );
  }

  // Attaches confirmed media to the listing image set while preserving existing images.
  private attachImage(listingId: string, currentImages: ListingImage[], media: ListingMedia): Observable<ListingImage[]> {
    return this.listingService.updateListingImages(listingId, {
      images: appendConfirmedMediaToImages(currentImages, media),
    }).pipe(
      catchError(error => this.fail('attach', error)),
    );
  }

  private fail(step: ListingMediaUploadFailureStep, error: unknown): Observable<never> {
    return throwError(() => new ListingMediaUploadError(step, error));
  }
}
