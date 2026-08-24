import { CatalogCapabilities } from '../../core/models/catalog.model';
import { catalogAdminCapability } from './catalog-admin-capability';

describe('catalogAdminCapability', () => {
  const capabilities: CatalogCapabilities = {
    canRead: true,
    canManageCategory: false,
    canManageAttribute: false,
    canManagePolicy: false,
    canManageGuidance: false,
    canDisableCategory: false,
    canPublishRules: false,
    readOnly: false,
    readOnlyReason: null,
  };

  it('marks read-only catalog roles and explains why mutations are unavailable', () => {
    expect(catalogAdminCapability(capabilities)).toEqual({
      ...capabilities,
      readOnly: true,
      readOnlyReason: 'Catalog access is read-only for this role.',
    });
  });

  it('keeps catalog managers writable', () => {
    expect(catalogAdminCapability({ ...capabilities, canManagePolicy: true }).readOnly).toBeFalse();
  });
});
