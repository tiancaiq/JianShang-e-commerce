import { isReportEvidenceTimestamp, rawReportEvidenceValue, reportEvidenceLabel } from './report-evidence-presentation';

describe('report evidence presentation', () => {
  it('uses staff-friendly labels for allow-listed snapshot fields', () => {
    expect(reportEvidenceLabel('businessId')).toBe('Business ID');
    expect(reportEvidenceLabel('capturedAt')).toBe('Captured at');
    expect(reportEvidenceLabel('categoryId')).toBe('Category ID');
    expect(reportEvidenceLabel('sellerType')).toBe('Seller type');
  });

  it('preserves camel-case boundaries for future safe fields', () => {
    expect(reportEvidenceLabel('publicRegion')).toBe('Public Region');
  });

  it('identifies timestamp evidence and preserves its raw tooltip value', () => {
    const capturedAt = '2026-08-15T14:30:00Z';
    expect(isReportEvidenceTimestamp('capturedAt', capturedAt)).toBeTrue();
    expect(isReportEvidenceTimestamp('title', capturedAt)).toBeFalse();
    expect(rawReportEvidenceValue(capturedAt)).toBe(capturedAt);
  });
});
