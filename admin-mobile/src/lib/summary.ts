/**
 * Pure aggregation of the dashboard's daily-rolls view
 * (dashboard_sales_daily, see supabase/migrations/0001_admin_foundation.sql)
 * into the business-overview windows: today, this ISO week, this month.
 *
 * All money is integer pesewas — the same convention as the canonical
 * server schema (BIGINT responses), never floats.
 */

export interface DailyRoll {
  /** ISO date (yyyy-mm-dd) produced by the view. */
  day: string;
  sales_count: number;
  revenue: number;
  discounts: number;
  tax: number;
  refunds_total: number;
  gross_profit: number;
}

export interface SalesWindow {
  salesCount: number;
  revenue: number;
  discounts: number;
  tax: number;
  refundsTotal: number;
  grossProfit: number;
}

export interface SalesSummary {
  today: SalesWindow | null;
  thisWeek: SalesWindow | null;
  thisMonth: SalesWindow | null;
  daily: DailyRoll[];
}

function emptyWindow(): SalesWindow {
  return { salesCount: 0, revenue: 0, discounts: 0, tax: 0, refundsTotal: 0, grossProfit: 0 };
}

export function summarizeSales(daily: DailyRoll[], today: Date): SalesSummary {
  // Windows are computed here, not in the view, so the timezone boundary is
  // controlled by the app and fully unit-testable in node.
  const dayStr = toLocalDateString(today);
  const monthPrefix = dayStr.slice(0, 7);

  const summary: SalesSummary = {
    today: null,
    thisWeek: null,
    thisMonth: null,
    daily: [...daily].sort((a, b) => (a.day < b.day ? 1 : -1)),
  };

  let week: SalesWindow = emptyWindow();
  let hadWeek = false;
  const weekDays = new Set(isoWeekDates(today));
  for (const roll of daily) {
    if (roll.day === dayStr) {
      summary.today = toWindow(roll);
    }
    if (roll.day.slice(0, 7) === monthPrefix) {
      summary.thisMonth = addWindow(summary.thisMonth ?? emptyWindow(), roll);
    }
    if (weekDays.has(roll.day)) {
      hadWeek = true;
      week = addWindow(week, roll);
    }
  }
  summary.thisWeek = hadWeek ? week : null;
  return summary;
}

function toWindow(roll: DailyRoll): SalesWindow {
  return {
    salesCount: roll.sales_count,
    revenue: roll.revenue,
    discounts: roll.discounts,
    tax: roll.tax,
    refundsTotal: roll.refunds_total,
    grossProfit: roll.gross_profit,
  };
}

function addWindow(acc: SalesWindow, roll: DailyRoll): SalesWindow {
  acc.salesCount += roll.sales_count;
  acc.revenue += roll.revenue;
  acc.discounts += roll.discounts;
  acc.tax += roll.tax;
  acc.refundsTotal += roll.refunds_total;
  acc.grossProfit += roll.gross_profit;
  return acc;
}

/** Local (device-timezone) yyyy-mm-dd for a Date, matching the view's day column. */
export function toLocalDateString(date: Date): string {
  const y = date.getFullYear();
  const m = `${date.getMonth() + 1}`.padStart(2, '0');
  const d = `${date.getDate()}`.padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** The seven yyyy-mm-dd strings of the ISO week (Mon–Sun) containing `date`. */
export function isoWeekDates(date: Date): string[] {
  const result: string[] = [];
  const cursor = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  // ISO weeks start Monday (1). Roll the cursor back to this week's Monday.
  const shift = (cursor.getDay() + 6) % 7;
  cursor.setDate(cursor.getDate() - shift);
  for (let i = 0; i < 7; i += 1) {
    result.push(toLocalDateString(cursor));
    cursor.setDate(cursor.getDate() + 1);
  }
  return result;
}

/** Prompt display helper: integer pesewas -> "GHS 1,234.56". */
export function formatPeso(pesewas: number): string {
  const sign = pesewas < 0 ? '-' : '';
  const abs = Math.abs(pesewas);
  const whole = Math.floor(abs / 100).toLocaleString('en-US');
  const frac = `${abs % 100}`.padStart(2, '0');
  return `${sign}GHS ${whole}.${frac}`;
}
