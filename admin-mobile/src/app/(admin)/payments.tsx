import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { formatPeso } from '@/lib/summary';
import { listMobileMoneyPayments, updatePaymentReconciliation, type PaymentReconciliationResult } from '@/lib/phase43';

type Payment = Awaited<ReturnType<typeof listMobileMoneyPayments>>[number];
export default function PaymentsScreen() {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [refs, setRefs] = useState<Record<string, string>>({});
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [results, setResults] = useState<Record<string, PaymentReconciliationResult>>({});
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    setError(null);
    try { setPayments(await listMobileMoneyPayments()); }
    catch (e) { setError(e instanceof Error ? e.message : 'MoMo payments could not be loaded'); }
    finally { setLoading(false); setRefreshing(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  async function reconcile(payment: Payment) {
    setError(null); setBusyId(payment.payment_id);
    try {
      const amount = Number(amounts[payment.payment_id]);
      const result = await updatePaymentReconciliation(payment.payment_id, refs[payment.payment_id] ?? '', amount);
      setResults((old) => ({ ...old, [payment.payment_id]: result }));
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : 'Reconciliation request failed'); }
    finally { setBusyId(null); }
  }
  if (loading) return <View style={styles.center}><ActivityIndicator size="large" /></View>;
  return <ScrollView style={styles.container} contentContainerStyle={styles.content} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); void load(); }} />}>
    <Text style={styles.title}>Mobile Money (MoMo)</Text>
    <Text style={styles.muted}>Payment state is confirmed by the configured provider webhook. Statement reconciliation is server-side and never edits payment status.</Text>
    {error ? <Text style={styles.error}>{error}</Text> : null}
    {payments.length === 0 ? <Text style={styles.muted}>No mobile-money attempts recorded.</Text> : null}
    {payments.map((payment) => {
      const result = results[payment.payment_id];
      return <View key={payment.payment_id} style={styles.card}>
        <Text style={styles.paymentTitle}>{formatPeso(Number(payment.amount))} · {payment.status.toUpperCase()}</Text>
        <Text style={styles.muted}>Sale {payment.sale_id} · {payment.provider ?? 'provider not assigned'} · {payment.provider_ref ?? 'no provider reference'}</Text>
        <Text style={styles.muted}>Created {new Date(payment.created_at).toLocaleString()}{payment.reconciled_at ? ` · reconciled ${new Date(payment.reconciled_at).toLocaleString()}` : ''}</Text>
        <TextInput style={styles.input} placeholder="Provider statement reference" value={refs[payment.payment_id] ?? ''} onChangeText={(value) => setRefs((old) => ({ ...old, [payment.payment_id]: value }))} autoCapitalize="none" />
        <TextInput style={styles.input} placeholder="Statement amount (pesewas)" value={amounts[payment.payment_id] ?? ''} onChangeText={(value) => setAmounts((old) => ({ ...old, [payment.payment_id]: value }))} keyboardType="number-pad" />
        <TouchableOpacity style={styles.button} disabled={busyId === payment.payment_id} onPress={() => void reconcile(payment)}>
          <Text style={styles.buttonText}>{busyId === payment.payment_id ? 'Checking…' : 'Check reconciliation'}</Text>
        </TouchableOpacity>
        {result ? <Text style={result.matched ? styles.success : styles.error}>Statement result: {result.reason.replaceAll('-', ' ')}</Text> : null}
      </View>;
    })}
  </ScrollView>;
}
const styles = StyleSheet.create({ container: { flex: 1, backgroundColor: '#f7f8fa' }, content: { padding: 16, gap: 12 }, center: { flex: 1, alignItems: 'center', justifyContent: 'center' }, title: { fontSize: 22, fontWeight: '700' }, paymentTitle: { fontSize: 16, fontWeight: '700' }, card: { backgroundColor: '#fff', borderRadius: 10, padding: 14, gap: 8 }, muted: { color: '#666', fontSize: 12 }, input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 8, padding: 10 }, button: { backgroundColor: '#208AEF', borderRadius: 8, padding: 12, alignItems: 'center' }, buttonText: { color: '#fff', fontWeight: '600' }, error: { color: '#c0392b' }, success: { color: '#17803d' } });
