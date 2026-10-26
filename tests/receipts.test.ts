import { beforeEach, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/db.ts';
import { checkout, listSaleItems } from '../src/domain/checkout.ts';
import { renderReceipt } from '../src/domain/receipts.ts';
import { seedStore, type SeededStore } from './helpers.ts';

describe('receipts', () => {
  let store: SeededStore;
  let db: Db;

  beforeEach(() => {
    store = seedStore();
    db = store.db;
  });

  function sale() {
    return checkout(db, {
      deviceId: store.deviceId,
      cashierId: store.cashier.user_id,
      lines: [
        { productId: store.products['WATER-500']!.product_id, quantity: 2 },
        { productId: store.products['MILO-400']!.product_id, quantity: 1 },
      ],
    });
  }

  it('renders a receipt with the number, lines and authoritative totals', () => {
    const { sale: s, items } = sale();
    const text = renderReceipt({
      sale: s,
      items,
      storeName: 'Business Store',
      cashierName: 'Akosua Mensah',
    });

    expect(text).toContain('SALE RECEIPT');
    expect(text).toContain(s.receipt_number);
    expect(text).toContain('TOTAL');
    expect(text).toContain('GHS 53.63');
    expect(text).not.toContain('COPY');
  });

  it('marks every reprint as COPY', () => {
    const { sale: s, items } = sale();
    const original = renderReceipt({ sale: s, items, storeName: 'Business Store' });
    const reprint = renderReceipt({ sale: s, items, storeName: 'Business Store', copy: true });

    expect(reprint).toContain('*** COPY ***');
    expect(reprint).toContain('not the original');
    // The body is otherwise identical, so a copy cannot be doctored into an original.
    expect(reprint.replace(/\*\*\* COPY \*\*\*\n/, '')).toContain(s.receipt_number);
    expect(original).not.toContain('COPY');
  });

  it('never exceeds the configured roll width on any line', () => {
    const { sale: s } = sale();
    const text = renderReceipt({ sale: s, items: listSaleItems(db, s.sale_id), storeName: 'Business Store', width: 32 });
    for (const line of text.split('\n')) {
      expect(line.length).toBeLessThanOrEqual(32);
    }
  });
});
