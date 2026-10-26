import { getSupabaseClient } from './supabase';
import type { DailyRoll, SalesSummary } from './summary';
import { summarizeSales } from './summary';

/**
 * Reads the shared canonical sales tables ONLY through the dashboard views
 * (dashboard_sales_daily, low_stock_overview) — never by hand-writing SQL
 * rules in the client. RLS + is_admin() decide readability server-side.
 */
export interface LowStockRow {
  sku: string;
  name: string;
  quantity: number;
  low_stock_threshold: number;
}

export interface DashboardData {
  summary: SalesSummary;
  refetchedAt: string; // ISO timestamp, shown so the operator knows data age
  lowStock: LowStockRow[];
}

export async function fetchDashboard(): Promise<DashboardData> {
  const supabase = getSupabaseClient();
  const since = new Date(Date.now() - 90 * 24 * 3600 * 1000).toISOString().slice(0, 10);

  const [dailyResult, lowStockResult] = await Promise.all([
    supabase.from('dashboard_sales_daily').select('*').gte('day', since).order('day', { ascending: false }),
    supabase.from('low_stock_overview').select('sku, name, quantity, low_stock_threshold').order('quantity', { ascending: true }).limit(50),
  ]);

  if (dailyResult.error) throw new Error(`dashboard query failed: ${dailyResult.error.message}`);
  if (lowStockResult.error) throw new Error(`low-stock query failed: ${lowStockResult.error.message}`);

  const daily = (dailyResult.data ?? []) as DailyRoll[];
  return {
    summary: summarizeSales(daily, new Date()),
    refetchedAt: new Date().toISOString(),
    lowStock: (lowStockResult.data ?? []) as LowStockRow[],
  };
}
