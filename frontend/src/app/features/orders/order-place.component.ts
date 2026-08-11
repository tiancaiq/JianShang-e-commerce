import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-order-place',
  standalone: true,
  imports: [RouterLink],
  template: `<p>Orders are created only from verified checkout payment. <a routerLink="/cart">Open cart</a>.</p>`,
})
export class OrderPlaceComponent {}
