import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { environment } from '../../../environments/environment';
import {
  AdminAnalyticsOverview,
  AdminAnalyticsOverviewQuery,
  AdminAnalyticsTrendQuery,
  AnalyticsTrend,
} from '../models/admin-analytics.model';

@Injectable({ providedIn: 'root' })
export class AdminAnalyticsService {
  private readonly base = `${environment.apiGatewayUrl}/api/v1/admin/analytics`;

  constructor(private readonly http: HttpClient) {}

  overview(query: AdminAnalyticsOverviewQuery) {
    let params = new HttpParams()
      .set('range', query.range)
      .set('timezone', query.timezone)
      .set('compare', String(query.compare));
    if (query.range === 'CUSTOM') {
      params = params.set('from', query.from).set('to', query.to);
    }
    return this.http.get<AdminAnalyticsOverview>(`${this.base}/overview`, {
      params,
      withCredentials: true,
    });
  }

  trend(query: AdminAnalyticsTrendQuery) {
    const params = new HttpParams()
      .set('range', query.range)
      .set('metric', query.metric)
      .set('from', query.from)
      .set('to', query.to)
      .set('timezone', query.timezone)
      .set('granularity', query.granularity);
    return this.http.get<AnalyticsTrend>(`${this.base}/trends`, {
      params,
      withCredentials: true,
    });
  }
}
