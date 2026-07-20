import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MarketplaceSidebarComponent } from './marketplace-sidebar.component';

describe('MarketplaceSidebarComponent', () => {
  it('hides the deferred support assistant card in the MVP configuration', async () => {
    await TestBed.configureTestingModule({
      imports: [MarketplaceSidebarComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();

    const fixture = TestBed.createComponent(MarketplaceSidebarComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.help-card')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Support chat is coming soon.');
  });
});
