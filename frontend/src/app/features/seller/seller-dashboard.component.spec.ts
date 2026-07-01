import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SellerDashboardComponent } from './seller-dashboard.component';

describe('SellerDashboardComponent', () => {
  let fixture: ComponentFixture<SellerDashboardComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SellerDashboardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SellerDashboardComponent);
  });

  it('links basic user account management to the marketplace account profile route', () => {
    fixture.detectChanges();

    const accountLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.includes('Account'));

    expect(accountLink?.getAttribute('href')).toBe('/account/profile');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('/seller/profile');
  });
});
