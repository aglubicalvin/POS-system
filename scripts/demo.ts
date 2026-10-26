/**
 * End-to-end device demo: stock in, sell, pay, print, drain the outbox, report.
 *
 * The apply logic lives in the Kotlin server, so this drains to a recording
 * transport and shows the exact envelopes that would be POSTed to `/sync`.
 * Run the server with `cd server && gradle run` to see them applied for real.
 *
 * Run with:  npm run demo
 */
import { checkout } from '../src/domain/checkout.ts';
import { receiveGoods, disburseStock } from '../src/domain/inventory.ts';
import { formatGhs } from '../src/domain/money.ts';
import { confirmPayment, createPayment } from '../src/domain/payments.ts';
import { renderReceipt } from '../src/domain/receipts.ts';
import { openShift } from '../src/domain/shifts.ts';
import { dailySales, lowStockAlerts, stockLevels } from '../src/reports/reports.ts';
import { drainUntilEmpty } from '../src/sync/client.ts';
import { recordingTransport, seedStore } from '../tests/helpers.ts';

const section = (label: string): void => console.log(`\n=== ${label} ===`);

// ---------------------------------------------------------------- the device
const store = seedStore({ deviceName: 'TILL-1' });
const { db, deviceId, manager, cashier, supplier, products } = store;
console.log(`Device ${deviceId.slice(0, 8)} registered for the shop.`);

section('Goods received');
const delivery = receiveGoods(db, {
  deviceId,
  supplierId: supplier.supplier_id,
  receivedBy: manager.user_id,
  reference: 'INV-1043',
  lines: [
    { productId: products['WATER-500']!.product_id, quantity: 120, unitCost: 90 },
    { productId: products['MILO-400']!.product_id, quantity: 24, unitCost: 3050 },
  ],
});
console.log(`Received stock from ${supplier.name}, cost ${formatGhs(delivery.totalCost)}.`);

section('Shift open');
const shift = openShift(db, { deviceId, userId: cashier.user_id, openingFloat: 10_000 });
console.log(`Shift ${shift.shift_id.slice(0, 8)} opened with float ${formatGhs(shift.opening_float)}.`);

section('Disbursement (reason code required)');
disburseStock(db, {
  deviceId,
  productId: products['WATER-500']!.product_id,
  quantity: 4,
  userId: manager.user_id,
  reason: 'damaged in transit',
});
console.log('4 sachets written off, with a reason and an audit entry.');

section('Checkout');
const { sale, items, receiptNumber } = checkout(db, {
  deviceId,
  cashierId: cashier.user_id,
  shiftId: shift.shift_id,
  lines: [
    { productId: products['WATER-500']!.product_id, quantity: 6 },
    { productId: products['MILO-400']!.product_id, quantity: 2, discount: 200 },
  ],
});
console.log(`Sale ${receiptNumber} total ${formatGhs(sale.total)} (VAT ${formatGhs(sale.tax_total)}).`);

section('Payment');
const payment = createPayment(db, { saleId: sale.sale_id, method: 'cash', amount: sale.total });
confirmPayment(db, payment.payment_id);
console.log(`Payment ${payment.status} — a retry with the same idempotency key did not double-charge.`);

section('Thermal receipt (reprint, marked COPY)');
console.log(
  renderReceipt({
    sale,
    items,
    storeName: 'Business Store',
    storeAddress: 'Osu, Accra',
    storePhone: '0302 000 000',
    cashierName: cashier.full_name,
    payments: [{ method: 'cash', amount: sale.total, status: 'confirmed' }],
    copy: true,
  }),
);

section('Draining the outbox (these are the /sync envelopes)');
const recorder = recordingTransport();
const drained = await drainUntilEmpty(db, deviceId, recorder.transport);
console.log(`Uploaded ${drained.acked} event(s) in sequence order:`);
for (const event of recorder.received) {
  console.log(`  seq ${String(event.seq).padStart(2)}  ${event.eventType.padEnd(24)} -> ${event.aggregateId.slice(0, 8)}`);
}
console.log('The Kotlin server applies each of these exactly once and reprices from its own catalog.');

section('Reports');
const today = new Date().toISOString().slice(0, 10);
for (const day of dailySales(db, today, today)) {
  console.log(`${day.day}: ${day.salesCount} sale(s), net ${formatGhs(day.netTotal)}, VAT ${formatGhs(day.taxTotal)}`);
}
for (const level of stockLevels(db)) {
  console.log(`  ${level.sku.padEnd(10)} on hand ${String(level.onHand).padStart(4)}${level.low ? '  <-- LOW' : ''}`);
}
console.log(`Low-stock alerts: ${lowStockAlerts(db).map((a) => a.sku).join(', ') || 'none'}`);

console.log('\nDemo complete.');
