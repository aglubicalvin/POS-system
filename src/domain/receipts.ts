import type { SaleItemRow, SaleRow } from './checkout.ts';
import { formatGhs } from './money.ts';
import { parseReceiptNumber } from './receipt-number.ts';

/**
 * Receipt rendering.
 *
 * The layout is plain text sized for an 80mm thermal roll (32 columns), which is
 * what an ESC/POS printer consumes directly and what a PDF/SMS/email adapter can
 * wrap unchanged. A reprint is never a fresh document: it is the same body
 * stamped COPY so a duplicate cannot be passed off as the original.
 */

export interface ReceiptPayment {
  method: string;
  amount: number;
  status: string;
}

export interface ReceiptInput {
  sale: SaleRow;
  items: SaleItemRow[];
  storeName: string;
  storeAddress?: string;
  storePhone?: string;
  cashierName?: string;
  payments?: ReceiptPayment[];
  /** True for a reprint. */
  copy?: boolean;
  width?: number;
}

function padColumns(left: string, right: string, width: number): string {
  const space = Math.max(1, width - left.length - right.length);
  return `${left}${' '.repeat(space)}${right}`;
}

function centre(text: string, width: number): string {
  if (text.length >= width) return text;
  const left = Math.floor((width - text.length) / 2);
  return `${' '.repeat(left)}${text}`;
}

export function renderReceipt(input: ReceiptInput): string {
  const width = input.width ?? 32;
  const rule = '-'.repeat(width);
  const lines: string[] = [];
  const { sale, items } = input;

  lines.push(centre(input.storeName.toUpperCase(), width));
  if (input.storeAddress) lines.push(centre(input.storeAddress, width));
  if (input.storePhone) lines.push(centre(`Tel: ${input.storePhone}`, width));
  lines.push(rule);

  if (input.copy) lines.push(centre('*** COPY ***', width));

  const kind = sale.kind === 'sale' ? 'SALE RECEIPT' : sale.kind === 'void' ? 'VOID RECEIPT' : 'REFUND RECEIPT';
  lines.push(centre(kind, width));
  lines.push(rule);

  lines.push(`Receipt : ${sale.receipt_number}`);
  lines.push(`Date    : ${sale.created_at.replace('T', ' ').slice(0, 19)}`);
  if (input.cashierName) lines.push(`Cashier : ${input.cashierName}`);
  if (sale.original_sale_id) lines.push(`Original: ${sale.original_sale_id.slice(0, 8)}...`);
  lines.push(rule);

  for (const item of items) {
    lines.push(item.name.slice(0, width));
    const qty = `${item.quantity} x ${formatGhs(item.unit_price)}`;
    lines.push(padColumns(`  ${qty}`, formatGhs(item.line_total), width));
    if (item.discount > 0) lines.push(padColumns('  discount', `-${formatGhs(item.discount)}`, width));
  }
  lines.push(rule);

  lines.push(padColumns('Subtotal', formatGhs(sale.subtotal), width));
  if (sale.discount_total > 0) lines.push(padColumns('Discount', `-${formatGhs(sale.discount_total)}`, width));
  if (sale.tax_total > 0) lines.push(padColumns('VAT', formatGhs(sale.tax_total), width));
  lines.push(padColumns('TOTAL', formatGhs(sale.total), width));

  if (input.payments?.length) {
    lines.push(rule);
    for (const payment of input.payments) {
      lines.push(padColumns(`${payment.method} (${payment.status})`, formatGhs(payment.amount), width));
    }
  }

  lines.push(rule);
  const { seq } = parseReceiptNumber(sale.receipt_number);
  lines.push(centre(`Txn #${seq}  ${sale.currency}`, width));
  lines.push(centre('Thank you — medaase', width));
  if (input.copy) lines.push(centre('COPY — not the original receipt', width));

  return lines.join('\n');
}
