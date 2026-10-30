import { describe, expect, it } from 'vitest';
import {
  contractCountdown,
  recordPayrollDeduction,
  updatePaymentReconciliation,
} from '../src/lib/phase43';

describe('contractCountdown', () => {
  it('counts down to the end date in whole UTC days', () => {
    expect(contractCountdown('2026-03-23', 'active', new Date(2026, 2, 22))).toEqual({ label: '1 days left', overdue: false });
  });

  it('marks overdue when the end date has passed', () => {
    expect(contractCountdown('2026-03-21', 'active', new Date(2026, 2, 22))).toEqual({ label: '1 days overdue', overdue: true });
  });

  it('returns the raw status and no overdue flag for non-active contracts', () => {
    expect(contractCountdown('2026-03-23', 'completed', new Date(2026, 2, 22))).toEqual({ label: 'completed', overdue: false });
  });

  it('handles a missing end date on an active contract', () => {
    expect(contractCountdown(null, 'active', new Date(2026, 2, 22))).toEqual({ label: 'No end date', overdue: false });
  });
});

describe('recordPayrollDeduction validation', () => {
  it('rejects non-positive amounts before any network call', async () => {
    await expect(recordPayrollDeduction({ customer_id: 'c1', sale_id: null, amount: 0, deduction_month: '2026-03', notes: null })).rejects.toThrow('Deduction must be a positive amount in pesewas.');
  });

  it('rejects a malformed deduction month before any network call', async () => {
    await expect(recordPayrollDeduction({ customer_id: 'c1', sale_id: null, amount: 100, deduction_month: 'March 2026', notes: null })).rejects.toThrow('Choose a valid deduction month (YYYY-MM).');
  });
});

describe('updatePaymentReconciliation validation', () => {
  it('rejects empty ids or references before any network call', async () => {
    await expect(updatePaymentReconciliation('  ', 'ref', 100)).rejects.toThrow('Provide a payment, provider reference and positive amount in pesewas.');
    await expect(updatePaymentReconciliation('p1', '', 100)).rejects.toThrow('Provide a payment, provider reference and positive amount in pesewas.');
  });

  it('rejects non-positive or non-integer amounts before any network call', async () => {
    await expect(updatePaymentReconciliation('p1', 'ref', 0)).rejects.toThrow('Provide a payment, provider reference and positive amount in pesewas.');
    await expect(updatePaymentReconciliation('p1', 'ref', 10.5)).rejects.toThrow('Provide a payment, provider reference and positive amount in pesewas.');
  });
});
