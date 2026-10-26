/**
 * Money math in integer minor units (1 GHS = 100 pesewas).
 *
 * Floating point is never used for money: 0.1 + 0.2 !== 0.3 would silently
 * corrupt totals and tax. Every function here takes and returns integers and
 * throws on a non-integer input so a stray float surfaces at the boundary
 * instead of accumulating in the ledger.
 */

export type Minor = number;

export class MoneyError extends Error {
  override name = 'MoneyError';
}

function assertInt(value: number, label: string): void {
  if (!Number.isFinite(value) || !Number.isInteger(value)) {
    throw new MoneyError(`${label} must be an integer number of minor units, got ${value}`);
  }
}

/** Round half away from zero — the convention Ghanaian retail receipts expect. */
export function roundHalfAwayFromZero(value: number): number {
  if (!Number.isFinite(value)) throw new MoneyError(`cannot round ${value}`);
  return value >= 0 ? Math.round(value) : -Math.round(-value);
}

export function add(...amounts: Minor[]): Minor {
  let total = 0;
  for (const amount of amounts) {
    assertInt(amount, 'amount');
    total += amount;
  }
  return total;
}

export function sub(a: Minor, b: Minor): Minor {
  assertInt(a, 'a');
  assertInt(b, 'b');
  return a - b;
}

export function sum(amounts: readonly Minor[]): Minor {
  return add(...amounts);
}

export function negate(amount: Minor): Minor {
  assertInt(amount, 'amount');
  return -amount;
}

/** unit price x quantity, exact because both are integers. */
export function lineGross(unitPrice: Minor, quantity: number): Minor {
  assertInt(unitPrice, 'unitPrice');
  assertInt(quantity, 'quantity');
  if (quantity <= 0) throw new MoneyError(`quantity must be positive, got ${quantity}`);
  return unitPrice * quantity;
}

/** Apply a basis-point rate (10000 bp = 100%) with half-away-from-zero rounding. */
export function applyRate(amount: Minor, rateBp: number): Minor {
  assertInt(amount, 'amount');
  assertInt(rateBp, 'rateBp');
  return roundHalfAwayFromZero((amount * rateBp) / 10000);
}

/**
 * Line pricing. `discount` is an absolute amount in minor units (already capped
 * by the caller); tax is charged on the post-discount net.
 */
export function priceLine(input: {
  unitPrice: Minor;
  quantity: number;
  discount?: Minor;
  taxRateBp?: number;
}): { gross: Minor; discount: Minor; net: Minor; tax: Minor; total: Minor } {
  const gross = lineGross(input.unitPrice, input.quantity);
  const discount = input.discount ?? 0;
  assertInt(discount, 'discount');
  if (discount < 0) throw new MoneyError('discount cannot be negative');
  if (discount > gross) throw new MoneyError('discount cannot exceed the line gross amount');
  const net = gross - discount;
  const tax = applyRate(net, input.taxRateBp ?? 0);
  return { gross, discount, net, tax, total: net + tax };
}

/** Banker-free change calculation for a cash tender. */
export function changeDue(tendered: Minor, total: Minor): Minor {
  assertInt(tendered, 'tendered');
  assertInt(total, 'total');
  return tendered - total;
}

/** Human-readable GHS string for receipts and reports only — never for storage. */
export function formatGhs(amount: Minor, currency = 'GHS'): string {
  assertInt(amount, 'amount');
  const sign = amount < 0 ? '-' : '';
  const abs = Math.abs(amount);
  const units = Math.floor(abs / 100);
  const minor = String(abs % 100).padStart(2, '0');
  // Group thousands manually so the output never depends on ICU locale data.
  const grouped = String(units).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${currency} ${grouped}.${minor}`;
}
