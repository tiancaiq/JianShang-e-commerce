import { provideZonelessChangeDetection, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ToastService } from '../../../core/services/toast.service';
import { ToastContainerComponent } from './toast-container.component';

describe('ToastContainerComponent', () => {
  it('announces success and error notifications with appropriate live semantics', () => {
    const toasts = signal([
      { id: 1, message: 'Case saved.', type: 'success' as const },
      { id: 2, message: 'Case failed.', type: 'error' as const },
    ]);
    const toastService = {
      toasts: toasts.asReadonly(),
      dismiss: jasmine.createSpy('dismiss'),
    };

    TestBed.configureTestingModule({
      imports: [ToastContainerComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: ToastService, useValue: toastService },
      ],
    });
    const fixture = TestBed.createComponent(ToastContainerComponent);
    fixture.detectChanges();

    const renderedToasts = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.toast'),
    ) as HTMLElement[];
    expect(renderedToasts[0].getAttribute('role')).toBe('status');
    expect(renderedToasts[0].getAttribute('aria-live')).toBe('polite');
    expect(renderedToasts[1].getAttribute('role')).toBe('alert');
    expect(renderedToasts[1].getAttribute('aria-live')).toBe('assertive');

    (renderedToasts[0].querySelector('.toast-dismiss') as HTMLButtonElement).click();
    expect(toastService.dismiss).toHaveBeenCalledOnceWith(1);
  });
});
