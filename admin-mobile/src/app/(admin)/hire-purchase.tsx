import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { formatPeso } from '@/lib/summary';
import { contractCountdown, createHirePurchase, listCustomers, listHirePurchase, listHirePurchasePayments, listProductsForHirePurchase, payHirePurchaseInstallment, type Customer, type HirePurchaseContract, type ProductOption } from '@/lib/phase43';

type Installment = { hp_payment_id: string; amount: number; due_date: string; paid_at: string | null; status: string };
export default function HirePurchaseScreen() {
  const [contracts, setContracts] = useState<HirePurchaseContract[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [products, setProducts] = useState<ProductOption[]>([]);
  const [customerId, setCustomerId] = useState('');
  const [productId, setProductId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [price, setPrice] = useState('');
  const [deposit, setDeposit] = useState('0');
  const [months, setMonths] = useState('6');
  const [month, setMonth] = useState(new Date().toISOString().slice(0, 10));
  const [open, setOpen] = useState<string | null>(null);
  const [installments, setInstallments] = useState<Record<string, Installment[]>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [h, c, p] = await Promise.all([listHirePurchase(), listCustomers(), listProductsForHirePurchase()]);
      setContracts(h); setCustomers(c); setProducts(p);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not load hire-purchase contracts'); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  function selectProduct(id: string) { setProductId(id); const p = products.find((item) => item.product_id === id); if (p) setPrice((p.unit_price / 100).toFixed(2)); }
  async function create() {
    if (!customerId || !productId) { setError('Select both a customer and product.'); return; }
    const qty = Number(quantity), duration = Number(months), retail = Math.round(Number(price) * 100), down = Math.round(Number(deposit) * 100);
    if (!Number.isInteger(qty) || qty < 1 || !Number.isInteger(duration) || duration < 1 || !Number.isSafeInteger(retail) || retail < 0 || !Number.isSafeInteger(down) || down < 0) { setError('Enter valid quantity, duration, GHS retail price and deposit.'); return; }
    setBusy(true); setError(null);
    try { await createHirePurchase({ customerId, productId, quantity: qty, retailPrice: retail, deposit: down, durationMonths: duration, startDate: month }); await load(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Contract creation failed'); }
    finally { setBusy(false); }
  }
  async function toggle(contractId: string) {
    if (open === contractId) { setOpen(null); return; }
    setOpen(contractId);
    if (!installments[contractId]) try { const rows = await listHirePurchasePayments(contractId); setInstallments((old) => ({ ...old, [contractId]: rows })); }
    catch (e) { Alert.alert('Installments unavailable', e instanceof Error ? e.message : 'Could not load schedule'); }
  }
  async function markPaid(row: Installment, contractId: string) {
    setBusy(true);
    try { await payHirePurchaseInstallment(row.hp_payment_id); const rows = await listHirePurchasePayments(contractId); setInstallments((old) => ({ ...old, [contractId]: rows })); await load(); }
    catch (e) { Alert.alert('Payment not recorded', e instanceof Error ? e.message : 'Could not record payment'); }
    finally { setBusy(false); }
  }

  return <ScrollView style={styles.container} contentContainerStyle={styles.content}>
    <Text style={styles.title}>Hire Purchase</Text>
    <Text style={styles.subtitle}>Contracts are priced in GHS and scheduled into monthly installments.</Text>
    {error ? <Text style={styles.error}>{error}</Text> : null}
    <View style={styles.card}>
      <Text style={styles.cardTitle}>Create contract</Text>
      <Text style={styles.label}>Customer</Text>
      <ScrollView horizontal style={styles.picker}>{customers.map((c) => <Pressable key={c.customer_id} onPress={() => setCustomerId(c.customer_id)} style={[styles.chip, customerId === c.customer_id && styles.chipActive]}><Text style={customerId === c.customer_id && styles.white}>{c.full_name}{c.staff_number ? ` · ${c.staff_number}` : ''}</Text></Pressable>)}</ScrollView>
      <Text style={styles.label}>Product</Text>
      <ScrollView horizontal style={styles.picker}>{products.map((p) => <Pressable key={p.product_id} onPress={() => selectProduct(p.product_id)} style={[styles.chip, productId === p.product_id && styles.chipActive]}><Text style={productId === p.product_id && styles.white}>{p.name} · {p.sku}</Text></Pressable>)}</ScrollView>
      <TextInput style={styles.input} placeholder="Quantity" keyboardType="number-pad" value={quantity} onChangeText={setQuantity} />
      <TextInput style={styles.input} placeholder="Retail price per item (GHS)" keyboardType="decimal-pad" value={price} onChangeText={setPrice} />
      <TextInput style={styles.input} placeholder="Deposit (GHS)" keyboardType="decimal-pad" value={deposit} onChangeText={setDeposit} />
      <TextInput style={styles.input} placeholder="Duration in months" keyboardType="number-pad" value={months} onChangeText={setMonths} />
      <TextInput style={styles.input} placeholder="Start date YYYY-MM-DD" value={month} onChangeText={setMonth} maxLength={10} />
      <Pressable style={styles.button} disabled={busy} onPress={() => void create()}><Text style={styles.white}>{busy ? 'Saving…' : 'Create contract & schedule installments'}</Text></Pressable>
    </View>
    <Text style={styles.cardTitle}>Contracts ({contracts.length})</Text>
    {contracts.map((row) => {
      const countdown = contractCountdown(row.end_date, row.status);
      return <View key={row.contract_id} style={styles.card}>
        <Pressable onPress={() => void toggle(row.contract_id)}>
          <Text style={styles.cardTitle}>{row.product_name} · Qty {row.quantity}</Text>
          <Text style={styles.body}>{row.description ?? 'No product description'} · {row.full_name}{row.staff_number ? ` (#${row.staff_number})` : ''}</Text>
          <Text style={styles.body}>Retail {formatPeso(Number(row.retail_price))} each · term {row.duration_months} months · installment {formatPeso(Number(row.installment_amount))}</Text>
          <Text style={styles.body}>Deposit {formatPeso(Number(row.deposit))} · remaining {formatPeso(Number(row.remaining_balance))}</Text>
          <Text style={[styles.countdown, countdown.overdue && styles.error]}>{countdown.label} · {row.display_status}</Text>
          <Text style={styles.link}>{open === row.contract_id ? 'Hide payment schedule' : 'View payment schedule'}</Text>
        </Pressable>
        {open === row.contract_id ? (installments[row.contract_id] ?? []).map((p) => <View key={p.hp_payment_id} style={styles.installment}><Text>{p.due_date} · {formatPeso(Number(p.amount))} · {p.status}</Text>{p.status === 'pending' || p.status === 'overdue' ? <Pressable style={styles.smallButton} disabled={busy} onPress={() => void markPaid(p, row.contract_id)}><Text style={styles.white}>Record payment</Text></Pressable> : null}</View>) : null}
      </View>;
    })}
    {!contracts.length ? <Text style={styles.body}>No hire-purchase contracts recorded.</Text> : null}
    {busy ? <ActivityIndicator /> : null}
  </ScrollView>;
}

const styles = StyleSheet.create({ container: { flex: 1, backgroundColor: '#f7f7f7' }, content: { padding: 16, gap: 12 }, title: { fontSize: 22, fontWeight: '700' }, subtitle: { color: '#555' }, card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, gap: 9 }, cardTitle: { fontSize: 16, fontWeight: '600' }, label: { fontWeight: '600', marginTop: 4 }, picker: { maxHeight: 48 }, chip: { backgroundColor: '#e7eaf0', borderRadius: 16, paddingHorizontal: 12, paddingVertical: 8, marginRight: 8 }, chipActive: { backgroundColor: '#1f6feb' }, white: { color: '#fff', fontWeight: '600' }, input: { backgroundColor: '#fff', borderWidth: 1, borderColor: '#ddd', borderRadius: 8, padding: 11 }, button: { backgroundColor: '#1f6feb', borderRadius: 8, padding: 12, alignItems: 'center' }, body: { color: '#444' }, countdown: { color: '#238636', fontWeight: '700' }, error: { color: '#c0392b' }, link: { color: '#1f6feb', marginTop: 4 }, installment: { borderTopWidth: 1, borderColor: '#eee', paddingTop: 8, gap: 6 }, smallButton: { backgroundColor: '#238636', borderRadius: 7, padding: 8, alignSelf: 'flex-start' } });
