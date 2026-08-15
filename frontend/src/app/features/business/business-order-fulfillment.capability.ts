import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

type FulfillmentFeatures = { businessOrderFulfillment?: boolean };

export const BUSINESS_ORDER_FULFILLMENT_DEFAULT_ENABLED =
  (environment.features as FulfillmentFeatures).businessOrderFulfillment ?? false;

export const BUSINESS_ORDER_FULFILLMENT_ENABLED = new InjectionToken<boolean>(
  'BUSINESS_ORDER_FULFILLMENT_ENABLED',
  { providedIn: 'root', factory: () => BUSINESS_ORDER_FULFILLMENT_DEFAULT_ENABLED },
);
