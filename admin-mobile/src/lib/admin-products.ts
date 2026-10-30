import { getSupabaseClient } from './supabase';

/**
 * Admin-side reads/writes for product & price management. Every write goes
 * through the authorized RPCs from supabase/migrations/0002 — the client has
 * no UPDATE grant on `products` at all, so the server records the audit row
 * itself in the same transaction.
 */

export interface CatalogProduct {
  product_id: string;
  sku: string;
  name: string;
  unit_price: number;
  cost_price: number;
  low_stock_threshold: number;
  is_active: boolean;
  category: string | null;
  quantity: number | null;
  description: string | null;
  image_url: string | null;
  thumbnail_url: string | null;
}

export async function fetchCatalog(): Promise<CatalogProduct[]> {
  const supabase = getSupabaseClient();
  const { data, error } = await supabase
    .from('admin_product_catalog')
    .select('*')
    .order('name', { ascending: true });
  if (error) throw new Error(`catalog query failed: ${error.message}`);
  return (data ?? []) as CatalogProduct[];
}

export function categoriesOf(products: CatalogProduct[]): string[] {
  return [...new Set(products.map((p) => p.category).filter((c): c is string => !!c))].sort();
}

export async function applyPriceChange(
  productId: string,
  newPrice: number,
  reason: string,
): Promise<void> {
  const supabase = getSupabaseClient();
  const { error } = await supabase.rpc('admin_update_product_price', {
    p_product_id: productId,
    p_new_price: newPrice,
    p_reason: reason,
  });
  if (error) throw new Error(error.message); // RPC messages are user-facing by design
}

export async function applyCategory(
  productId: string,
  category: string | null,
): Promise<void> {
  if (category === null) return; // clearing categories is a later-phase feature
  const supabase = getSupabaseClient();
  const { error } = await supabase.rpc('admin_set_product_category', {
    p_product_id: productId,
    p_category: category,
  });
  if (error) throw new Error(error.message);
}
