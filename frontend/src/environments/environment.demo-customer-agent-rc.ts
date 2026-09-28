// Disposable RC profile: existing customer commerce pages and Agent V2 together.
export const environment = {
  production: true,
  apiGatewayUrl: '',
  authServiceUrl: '',
  features: {
    cart: true,
    buyerAddresses: true,
    categoryGuidance: false,
    sellerInventory: false,
    businessOrders: true,
    businessOrderFulfillment: true,
    buyerCheckout: true,
    orderCancellation: true,
    aiAssistant: true,
    aiDiscovery: true,
    marketplaceAgentV2: true,
    notifications: true,
    returns: true,
    adminSearchMaintenance: false,
  },
};
