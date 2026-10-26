import { describe, expect, it } from 'vitest';
import { summarizeSales, isoWeekDates, toLocalDateString, formatPeso, type DailyRoll } from '../src/lib/summary';

// Wednesday, 2026-03-18 — the middle of the ISO week Mon 2026-03-16 .. Sun 2026-03-22.
const TODAY = new Date(2026, 2, 18, 15, 30);

function roll(day: string, over: Partial<DailyRoll> = {}): DailyRoll {
  return {
    day,
    sales_count: 1,
    revenue: 10_000,
    discounts: 0,
    tax: 0,
    refunds_total: 0,
    gross_profit: 4_000,
    ...over,
  };
}

describe('summarizeSales windows', () => {
  it('splits daily rolls into today, ISO week and calendar month windows', () => {
    const summary = summarizeSales(
      [
        roll('2026-03-18'), // today
        roll('2026-03-16'), // Monday, same ISO week
        roll('2026-03-22'), // Sunday, same ISO week (future day should count in the week window)
        roll('2026-03-05'), // same month, previous week
        roll('2026-02-27'), // previous month
      ],
      TODAY,
    );

    expect(summary.today).toEqual({ salesCount: 1, revenue: 10_000, discounts: 0, tax: 0, refundsTotal: 0, grossProfit: 4_000 });
    expect(summary.thisWeek?.salesCount).toBe(3);
    expect(summary.thisWeek?.revenue).toBe(30_000);
    expect(summary.thisWeek?.grossProfit).toBe(12_000);
    expect(summary.thisMonth?.salesCount).toBe(4);
    expect(summary.daily[0].day).toBe('2026-03-22'); // newest first
    expect(summary.daily.at(-1)!.day).toBe('2026-02-27');
  });

  it('returns null windows with no data except an empty week window', () => {
    const summary = summarizeSales([], TODAY);
    expect(summary.today).toBeNull();
    expect(summary.thisMonth).toBeNull();
    expect(summary.thisWeek).toBeNull();
  });

  it('handles a Sunday (week contains days already behind, ISO Monday start)', () => {
    const sunday = new Date(2026, 2, 22);
    const days = isoWeekDates(sunday);
    expect(days[0]).toBe('2026-03-16');
    expect(days.at(-1)).toBe('2026-03-22');
  });
});

describe('toLocalDateString / formatPeso', () => {
  it('pads the date string', () => {
    expect(toLocalDateString(new Date(2026, 0, 5))).toBe('2026-01-05');
  });

  it('formats integer pesewas', () => {
    expect(formatPeso(123456)).toBe('GHS 1,234.56');
    expect(formatPeso(-999)).toBe('-GHS 9.99');
    expect(formatPeso(0)).toBe('GHS 0.00');
  });
});
