import { Component, Input } from '@angular/core';
import { ProfileCardUser, UserProfileCardComponent } from '../../account/user-profile-card.component';

@Component({
  selector: 'app-marketplace-seller-card',
  standalone: true,
  imports: [UserProfileCardComponent],
  template: `
    <app-user-profile-card [user]="user" [showSellAction]="showSellAction" />
  `,
})
export class MarketplaceSellerCardComponent {
  @Input() user: ProfileCardUser | null = null;
  @Input() showSellAction = true;
}
