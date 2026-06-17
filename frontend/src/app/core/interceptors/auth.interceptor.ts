import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';
import { environment } from '../../../environments/environment.development';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const gatewayUrl = environment.apiGatewayUrl;

  if (!isGatewayRequest(req.url, gatewayUrl)) {
    return next(req);
  }

  let headers = req.headers;
  const csrf = authService.csrf();

  if (csrf && isStateChanging(req.method)) {
    headers = headers.set(csrf.headerName, csrf.token);
  }

  return next(req.clone({ headers, withCredentials: true }));
};

function isGatewayRequest(url: string, gatewayUrl: string): boolean {
  if (!gatewayUrl) {
    return url.startsWith('/api/');
  }
  return url.startsWith(gatewayUrl) || url.startsWith('/api/');
}

function isStateChanging(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase());
}
