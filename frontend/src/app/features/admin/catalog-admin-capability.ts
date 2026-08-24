import { CatalogCapabilities } from '../../core/models/catalog.model';

export function catalogAdminCapability(value:CatalogCapabilities):CatalogCapabilities{
  return {...value,readOnly:!value.canManageCategory&&!value.canManageAttribute&&!value.canManagePolicy,
    readOnlyReason:value.readOnlyReason||(!value.canManageCategory&&!value.canManageAttribute&&!value.canManagePolicy?'Catalog access is read-only for this role.':null)};
}
