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

  it('links account management to the business seller account route', () => {
    fixture.detectChanges();

    const accountLink = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a'))
      .find(link => link.textContent?.includes('Business Account'));

    expect(accountLink?.getAttribute('href')).toBe('/seller/account');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Merchant workspace');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('/account/profile');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('/seller/profile');
  });
});
