import { describe, expect, it } from 'vitest';
import {
  isPriceChange,
  parsePricePesewas,
  validateProductEdit,
} from '../src/lib/product-management';

describe('parsePricePesewas', () => {
  it('converts decimal GHS strings to integer pesewas', () => {
    expect(parsePricePesewas('12.50')).toBe(1250);
    expect(parsePricePesewas('0.99')).toBe(99);
  });

  it('accepts pre-parsed integer pesewa values', () => {
    expect(parsePricePesewas('1250')).toBe(1250);
  });

  it('rounds fractional pesewas (no floating point residue)', () => {
    expect(parsePricePesewas('19.999999')).toBe(2000);
  });
});

describe('validateProductEdit', () => {
  it('rejects a price change without a reason (server requires one too)', () => {
    const v = validateProductEdit({ priceText: '12.50', category: 'Audio', reason: '' });
    expect(v.ok).toBe(false);
    expect(v.reasonError).toContain('at least 3 characters');
  });

  it('rejects malformed price text', () => {
    const v = validateProductEdit({ priceText: '12,50', category: '', reason: 'repricing' });
    expect(v.ok).toBe(false);
    expect(v.priceTextError).toBeDefined();
  });

  it('passes through an unchanged price (empty text) with only the category set', () => {
    const v = validateProductEdit({ priceText: '', category: ' Cables ', reason: 'regrouping' });
    expect(v.ok).toBe(true);
    expect(v.newPrice).toBeNull();
    expect(v.category).toBe('Cables');
  });

  it('parses and clamps the category to 64 characters', () => {
    const long = 'x'.repeat(80);
    const v = validateProductEdit({ priceText: '', category: long, reason: 'typo fix' });
    expect(v.category?.length).toBe(64);
  });
});

describe('isPriceChange', () => {
  it('is false when the new price is null or identical', () => {
    expect(isPriceChange(1250, null)).toBe(false);
    expect(isPriceChange(1250, 1250)).toBe(false);
    expect(isPriceChange(1250, 1300)).toBe(true);
  });
});
