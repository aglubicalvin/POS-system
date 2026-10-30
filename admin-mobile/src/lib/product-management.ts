/**
 * Pure validation for the product/price management screens. The Supabase RPC
 * re-validates everything server-side (see
 * supabase/migrations/0002_product_management.sql); this is the same contract
 * client-side so the operator gets field-level errors without a round trip.
 *
 * Money = integer pesewas, exactly like the canonical server schema.
 */

export interface ProductEditDraft {
  /** Selling price in pesewas, empty string = unchanged. */
  priceText: string;
  category: string;
  reason: string;
}

export interface EditValidation {
  ok: boolean;
  priceTextError?: string;
  categoryError?: string;
  reasonError?: string;
  /** Parsed values once ok; null = unchanged. */
  newPrice?: number | null;
  category?: string | null;
  reason?: string;
}

export function validateProductEdit(draft: ProductEditDraft): EditValidation {
  const result: EditValidation = { ok: true, newPrice: null };

  const price = draft.priceText.trim();
  if (price !== '') {
    if (!/^\d+(\.\d{1,2})?$/.test(price)) {
      result.ok = false;
      result.priceTextError = 'Use a decimal GHS amount like 12.50 or an integer pesewa value';
    }
  }

  if (price !== '') {
    const reason = draft.reason.trim();
    if (reason.length < 3) {
      result.ok = false;
      result.reasonError = 'A reason of at least 3 characters is required';
    } else {
      result.reason = reason;
    }
    result.newPrice = parsePricePesewas(price);
  }
  const category = draft.category.trim();
  result.category = category === '' ? null : category.slice(0, 64);

  return result;
}

/** "12.50" -> 1250 pesewas; "1250" (already pesewas) -> 1250. */
export function parsePricePesewas(text: string): number {
  const trimmed = text.trim();
  if (trimmed === '') return NaN;
  if (trimmed.includes('.')) return Math.round(parseFloat(trimmed) * 100);
  return parseInt(trimmed, 10);
}

/** Anything that must be true before we ask the server. */
export function isPriceChange(currentPrice: number, newPrice: number | null): boolean {
  return newPrice !== null && newPrice !== currentPrice;
}
