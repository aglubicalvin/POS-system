import { describe, expect, it } from 'vitest';
import {
  add,
  applyRate,
  changeDue,
  formatGhs,
  lineGross,
  MoneyError,
  priceLine,
  roundHalfAwayFromZero,
  sum,
} from '../src/domain/money.ts';

describe('money', () => {
  it('adds and sums integer minor units exactly', () => {
    expect(add(150, 800, 4500)).toBe(5450);
    expect(sum([1, 2, 3])).toBe(6);
    // The classic float trap: 0.1 + 0.2 !== 0.3. In minor units it is exact.
    expect(add(10, 20)).toBe(30);
  });

  it('multiplies price by quantity without drift', () => {
    expect(lineGross(150, 3)).toBe(450);
    expect(lineGross(4500, 7)).toBe(31_500);
  });

  it('applies basis-point rates with half-away-from-zero rounding', () => {
    // 12.5% VAT on GHS 8.00 = 100 pesewas.
    expect(applyRate(800, 1250)).toBe(100);
    // 0.5 rounds up, not to even.
    expect(roundHalfAwayFromZero(0.5)).toBe(1);
    expect(roundHalfAwayFromZero(1.5)).toBe(2);
    expect(roundHalfAwayFromZero(-0.5)).toBe(-1);
    expect(applyRate(150, 0)).toBe(0);
  });

  it('prices a line with discount then tax on the discounted net', () => {
    const line = priceLine({ unitPrice: 800, quantity: 2, discount: 160, taxRateBp: 1250 });
    expect(line.gross).toBe(1600);
    expect(line.discount).toBe(160);
    expect(line.net).toBe(1440);
    expect(line.tax).toBe(180); // 12.5% of 1440
    expect(line.total).toBe(1620);
  });

  it('computes change for a cash tender', () => {
    expect(changeDue(2000, 1620)).toBe(380);
  });

  it('rejects non-integer money at the boundary', () => {
    expect(() => add(10.5)).toThrow(MoneyError);
    expect(() => lineGross(99.99, 1)).toThrow(MoneyError);
    expect(() => applyRate(100, 12.5)).toThrow(MoneyError);
  });

  it('refuses a discount larger than the line gross', () => {
    expect(() => priceLine({ unitPrice: 100, quantity: 1, discount: 101 })).toThrow(MoneyError);
  });

  it('formats GHS for receipts without depending on locale data', () => {
    expect(formatGhs(1620)).toBe('GHS 16.20');
    expect(formatGhs(150)).toBe('GHS 1.50');
    expect(formatGhs(123_456_789)).toBe('GHS 1,234,567.89');
    expect(formatGhs(-250)).toBe('-GHS 2.50');
  });
});
