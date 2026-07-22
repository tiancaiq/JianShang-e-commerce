import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

export const CART_DEFAULT_ENABLED = environment.features.cart;

export const CART_ENABLED = new InjectionToken<boolean>(
  'CART_ENABLED',
  {
    providedIn: 'root',
    factory: () => CART_DEFAULT_ENABLED,
  },
);
