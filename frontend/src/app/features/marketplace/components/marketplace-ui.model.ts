export interface MarketplaceUiProduct {
  id: string;
  title: string;
  sellerName: string;
  priceAmount: number;
  currency: string;
  categoryName: string;
  conditionLabel: string;
  locationLabel: string;
  imageUrl: string | null;
  imageAlt: string;
  badge: 'NEW' | 'HOT' | 'SALE';
  favoriteCount: number;
  visitCount: number;
}
