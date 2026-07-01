import { Component, OnInit, inject, signal } from '@angular/core';
import { IndividualSellerService } from '../../core/services/individual-seller.service';
import { EmptyStateComponent } from '../../shared/components/ui/empty-state.component';
import { ListingManagementComponent } from './listing-management.component';
import { IndividualSellerActivationComponent } from '../seller/individual-seller-activation.component';

@Component({
  selector: 'app-account-listings-entry',
  standalone: true,
  imports: [EmptyStateComponent, IndividualSellerActivationComponent, ListingManagementComponent],
  template: `
    @if (loading()) {
      <app-ui-empty-state>Checking seller profile...</app-ui-empty-state>
    } @else if (activeIndividualSeller()) {
      <app-listing-management />
    } @else {
      <app-individual-seller-activation (sellerActivated)="showListings()" />
    }
  `,
})
export class AccountListingsEntryComponent implements OnInit {
  private individualSellerService = inject(IndividualSellerService);

  loading = signal(false);
  activeIndividualSeller = signal(false);

  ngOnInit(): void {
    this.loading.set(true);
    this.individualSellerService.getMe().subscribe({
      next: profile => {
        this.activeIndividualSeller.set(profile.status === 'ACTIVE');
        this.loading.set(false);
      },
      error: () => {
        this.activeIndividualSeller.set(false);
        this.loading.set(false);
      },
    });
  }

  showListings(): void {
    this.activeIndividualSeller.set(true);
  }
}
