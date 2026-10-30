import { getSupabaseClient } from './supabase';

export type PaymentChannel = 'cash' | 'ghana_card' | 'payroll_deduction' | 'momo';
export interface Customer {
  customer_id: string;
  staff_number: string | null;
  full_name: string;
  company: string | null;
  phone: string | null;
  email: string | null;
  payment_method_preference: PaymentChannel;
  is_active: boolean;
}
export interface PayrollDeduction {
  deduction_id: string;
  customer_id: string;
  sale_id: string | null;
  amount: number;
  deduction_month: string;
  status: 'pending' | 'processed' | 'paid' | 'cancelled';
  notes: string | null;
  customers?: Pick<Customer, 'staff_number' | 'full_name' | 'company'>;
}
export interface Purchase {
  sale_id: string;
  receipt_number: string;
  customer_id: string | null;
  staff_number: string | null;
  payment_channel: PaymentChannel;
  total: number;
  created_at: string;
  kind: string;
}
export interface HirePurchaseContract {
  contract_id: string;
  customer_id: string;
  staff_number: string | null;
  full_name: string;
  product_id: string;
  product_name: string;
  description: string | null;
  quantity: number;
  retail_price: number;
  duration_months: number;
  installment_amount: number;
  deposit: number;
  total_amount: number;
  remaining_balance: number;
  status: 'active' | 'completed' | 'defaulted' | 'cancelled';
  start_date: string;
  end_date: string | null;
  days_remaining: number | null;
  display_status: string;
}
export interface ProductOption { product_id: string; sku: string; name: string; description: string | null; unit_price: number; }
export async function listProductsForHirePurchase(): Promise<ProductOption[]> {
  const { data, error } = await client().from('products').select('product_id,sku,name,description,unit_price').eq('is_active', true).order('name').limit(1000);
  if (error) throw new Error(`product choices query failed: ${error.message}`);
  return (data ?? []) as ProductOption[];
}

export interface InventoryRow {
  product_id: string;
  sku: string;
  name: string;
  image_url: string | null;
  thumbnail_url: string | null;
  quantity: number;
  low_stock_threshold: number;
  reorder_point: number;
  cost_price: number;
  inventory_value: number;
  low_stock: boolean;
}

function client() { return getSupabaseClient(); }

export async function listCustomers(search = ''): Promise<Customer[]> {
  const { data, error } = await client().from('customers').select('*').order('full_name').limit(500);
  if (error) throw new Error(`customers query failed: ${error.message}`);
  const rows = (data ?? []) as Customer[];
  const term = search.trim().toLocaleLowerCase();
  return term ? rows.filter((row) => [row.staff_number, row.full_name, row.company].some((v) => v?.toLocaleLowerCase().includes(term))) : rows;
}

export async function saveCustomer(input: Omit<Customer, 'customer_id'>, customerId?: string): Promise<void> {
  const query = customerId
    ? client().from('customers').update({ ...input, updated_at: new Date().toISOString() }).eq('customer_id', customerId)
    : client().from('customers').insert(input);
  const { error } = await query;
  if (error) throw new Error(`customer save failed: ${error.message}`);
}

export async function listPayroll(month: string): Promise<PayrollDeduction[]> {
  const { data, error } = await client().from('payroll_deductions')
    .select('deduction_id,customer_id,sale_id,amount,deduction_month,status,notes,customers(staff_number,full_name,company)')
    .eq('deduction_month', month).order('created_at', { ascending: false }).limit(1000);
  if (error) throw new Error(`payroll report failed: ${error.message}`);
  return (data ?? []) as unknown as PayrollDeduction[];
}

export async function recordPayrollDeduction(input: Pick<PayrollDeduction, 'customer_id' | 'sale_id' | 'amount' | 'deduction_month' | 'notes'>): Promise<void> {
  if (!Number.isSafeInteger(input.amount) || input.amount <= 0) throw new Error('Deduction must be a positive amount in pesewas.');
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(input.deduction_month)) throw new Error('Choose a valid deduction month (YYYY-MM).');
  const { error } = await client().from('payroll_deductions').insert({ ...input, status: 'pending' });
  if (error) throw new Error(`deduction save failed: ${error.message}`);
}

export async function setPayrollStatus(id: string, status: PayrollDeduction['status']): Promise<void> {
  const { error } = await client().from('payroll_deductions').update({ status, updated_at: new Date().toISOString(), ...(status === 'processed' || status === 'paid' ? { processed_at: new Date().toISOString() } : {}) }).eq('deduction_id', id);
  if (error) throw new Error(`deduction status update failed: ${error.message}`);
}

export async function listPurchases(search = ''): Promise<Purchase[]> {
  let query = client().from('sales').select('sale_id,receipt_number,customer_id,staff_number,payment_channel,total,created_at,kind')
    .order('created_at', { ascending: false }).limit(500);
  const term = search.trim().replace(/[%,()]/g, '');
  if (term) query = query.or(`staff_number.ilike.%${term}%,receipt_number.ilike.%${term}%`);
  const { data, error } = await query;
  if (error) throw new Error(`purchase history failed: ${error.message}`);
  return (data ?? []) as Purchase[];
}

export async function createHirePurchase(input: { customerId: string; productId: string; quantity: number; retailPrice: number; deposit: number; durationMonths: number; startDate: string }): Promise<string> {
  const { data, error } = await client().rpc('admin_create_hire_purchase_contract', {
    p_customer_id: input.customerId,
    p_product_id: input.productId,
    p_quantity: input.quantity,
    p_retail_price: input.retailPrice,
    p_deposit: input.deposit,
    p_duration_months: input.durationMonths,
    p_start_date: input.startDate,
  });
  if (error) throw new Error(`hire-purchase contract creation failed: ${error.message}`);
  return data as string;
}

export async function listHirePurchasePayments(contractId: string): Promise<Array<{ hp_payment_id: string; amount: number; due_date: string; paid_at: string | null; status: string }>> {
  const { data, error } = await client().from('hire_purchase_payments').select('hp_payment_id,amount,due_date,paid_at,status').eq('contract_id', contractId).order('due_date');
  if (error) throw new Error(`installments query failed: ${error.message}`);
  return (data ?? []) as Array<{ hp_payment_id: string; amount: number; due_date: string; paid_at: string | null; status: string }>;
}

export async function payHirePurchaseInstallment(paymentId: string): Promise<void> {
  const { error } = await client().rpc('admin_pay_hire_purchase_installment', { p_payment_id: paymentId });
  if (error) throw new Error(`installment payment failed: ${error.message}`);
}

export async function listHirePurchase(): Promise<HirePurchaseContract[]> {
  const { data, error } = await client().from('hire_purchase_overview').select('*').order('end_date', { ascending: true }).limit(500);
  if (error) throw new Error(`hire-purchase query failed: ${error.message}`);
  return (data ?? []) as HirePurchaseContract[];
}

export function contractCountdown(endDate: string | null, status: string, today = new Date()): { label: string; overdue: boolean } {
  if (status !== 'active') return { label: status, overdue: false };
  if (!endDate) return { label: 'No end date', overdue: false };
  const end = new Date(`${endDate.slice(0, 10)}T00:00:00Z`);
  const now = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
  const days = Math.ceil((end.getTime() - now.getTime()) / 86_400_000);
  return days < 0 ? { label: `${Math.abs(days)} days overdue`, overdue: true } : { label: `${days} days left`, overdue: false };
}

export async function listInventory(): Promise<InventoryRow[]> {
  const { data, error } = await client().from('admin_inventory_overview').select('*').order('low_stock', { ascending: false }).order('name').limit(1000);
  if (error) throw new Error(`inventory query failed: ${error.message}`);
  return (data ?? []) as InventoryRow[];
}

export async function updateProductMedia(input: { productId: string; description: string; imageUrl: string; thumbnailUrl: string }): Promise<void> {
  const { error } = await client().rpc('admin_update_product_media', {
    p_product_id: input.productId,
    p_description: input.description || null,
    p_image_url: input.imageUrl || null,
    p_thumbnail_url: input.thumbnailUrl || null,
  });
  if (error) throw new Error(`product media update failed: ${error.message}`);
}

export const MOBILE_MONEY_PROVIDER_HOOK = {
  selectionRequired: true,
  callbackRoute: '/api/payments/mobile-money/webhook',
  reconciliationRpc: 'admin_reconcile_mobile_money_payment',
  credentialsMustStayOnServer: true,
} as const;

export interface PaymentReconciliationResult {
  payment_id: string;
  matched: boolean;
  reason: 'matched' | 'amount-mismatch' | 'reference-mismatch' | 'not-confirmed';
}

/**
 * Request a server-side reconciliation check. The mobile app never changes
 * payment status: the server owns provider verification and audit logging.
 * Configure `MOMO_RECONCILIATION_URL` on the trusted backend, not in Expo.
 */
export async function updatePaymentReconciliation(paymentId: string, providerRef: string, amount: number): Promise<PaymentReconciliationResult> {
  if (!paymentId.trim() || !providerRef.trim() || !Number.isSafeInteger(amount) || amount <= 0) {
    throw new Error('Provide a payment, provider reference and positive amount in pesewas.');
  }
  const { data, error } = await client().rpc('admin_reconcile_mobile_money_payment', {
    p_payment_id: paymentId,
    p_provider_ref: providerRef.trim(),
    p_amount: amount,
  });
  if (error) throw new Error(`payment reconciliation failed: ${error.message}`);
  const raw = Array.isArray(data) ? data[0] : data;
  if (!raw || typeof raw !== 'object') {
    throw new Error('The reconciliation service returned an invalid result. Apply migration 0007 and configure the server provider hook.');
  }
  const result = raw as Partial<PaymentReconciliationResult>;
  const validReasons: PaymentReconciliationResult['reason'][] = ['matched', 'amount-mismatch', 'reference-mismatch', 'not-confirmed'];
  if (result.payment_id !== paymentId || typeof result.matched !== 'boolean' || !validReasons.includes(result.reason as PaymentReconciliationResult['reason'])) {
    throw new Error('The reconciliation service returned an invalid result. Apply migration 0007 and configure the server provider hook.');
  }
  if (result.matched !== (result.reason === 'matched')) {
    throw new Error('The reconciliation service returned an inconsistent result.');
  }
  return result as PaymentReconciliationResult;
}

export async function listMobileMoneyPayments(): Promise<Array<{ payment_id: string; sale_id: string; amount: number; status: string; provider: string | null; provider_ref: string | null; reconciled_at: string | null; created_at: string }>> {
  const { data, error } = await client().from('payments').select('payment_id,sale_id,amount,status,provider,provider_ref,reconciled_at,created_at')
    .eq('method', 'mobile_money').order('created_at', { ascending: false }).limit(500);
  if (error) throw new Error(`MoMo status query failed: ${error.message}`);
  return (data ?? []) as Array<{ payment_id: string; sale_id: string; amount: number; status: string; provider: string | null; provider_ref: string | null; reconciled_at: string | null; created_at: string }>;
}
