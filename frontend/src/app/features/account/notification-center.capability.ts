import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

export const NOTIFICATION_CENTER_DEFAULT_ENABLED = environment.features.notifications;

export const NOTIFICATION_CENTER_ENABLED = new InjectionToken<boolean>(
  'NOTIFICATION_CENTER_ENABLED',
  {
    providedIn: 'root',
    factory: () => NOTIFICATION_CENTER_DEFAULT_ENABLED,
  },
);
