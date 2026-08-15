import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

export const ADMIN_SEARCH_MAINTENANCE_DEFAULT_ENABLED =
  environment.features.adminSearchMaintenance;

export const ADMIN_SEARCH_MAINTENANCE_ENABLED = new InjectionToken<boolean>(
  'ADMIN_SEARCH_MAINTENANCE_ENABLED',
  {
    providedIn: 'root',
    factory: () => ADMIN_SEARCH_MAINTENANCE_DEFAULT_ENABLED,
  },
);
