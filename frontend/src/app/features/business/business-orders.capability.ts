import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

export const BUSINESS_ORDERS_ENABLED = new InjectionToken<boolean>(
  'BUSINESS_ORDERS_ENABLED',
  { providedIn: 'root', factory: () => environment.features.businessOrders },
);
