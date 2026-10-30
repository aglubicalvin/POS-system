import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { formatPeso } from '@/lib/summary';
import { listInventory, type InventoryRow } from '@/lib/phase43';
import { getSupabaseClient } from '@/lib/supabase';

type Movement = { movement_id: string; product_id: string; quantity_delta: number; reason_code: string; occurred_at: string; requires_review: boolean };
export default function InventoryScreen() {
  const [rows, setRows] = useState<InventoryRow[]>([]);
  const [movements, setMovements] = useState<Movement[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    setError(null);
    try {
      const [inventory, ledger] = await Promise.all([
        listInventory(),
        getSupabaseClient().from('stock_movements').select('movement_id,product_id,quantity_delta,reason_code,occurred_at,requires_review').order('occurred_at', { ascending: false }).limit(30),
      ]);
      if (ledger.error) throw new Error(`stock movement query failed: ${ledger.error.message}`);
      setRows(inventory); setMovements((ledger.data ?? []) as Movement[]);
    } catch (e) { setError(e instanceof Error ? e.message : 'Inventory could not be loaded'); }
    finally { setLoading(false); setRefreshing(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  const low = rows.filter((row) => row.low_stock);
  const value = rows.reduce((total, row) => total + Number(row.inventory_value), 0);
  if (loading) return <View style={styles.center}><ActivityIndicator size="large" /></View>;
  return <ScrollView style={styles.container} contentContainerStyle={styles.content} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); void load(); }} />}>
    <Text style={styles.title}>Inventory</Text><Text style={styles.subtitle}>Stock ledger is authoritative; valuation uses current cost price.</Text>
    {error ? <Text style={styles.error}>{error}</Text> : null}
    <View style={styles.metrics}><Metric label="Active products" value={`${rows.length}`} /><Metric label="Low/reorder alerts" value={`${low.length}`} /><Metric label="Stock value" value={formatPeso(value)} /></View>
    <Text style={styles.section}>Low stock & reorder alerts</Text>
    {!low.length ? <Text style={styles.muted}>All active products are above their stock thresholds.</Text> : low.map((row) => <InventoryCard key={row.product_id} row={row} alert />)}
    <Text style={styles.section}>All inventory</Text>
    {rows.map((row) => <InventoryCard key={row.product_id} row={row} />)}
    <Text style={styles.section}>Recent stock movements</Text>
    {movements.map((movement) => {
      const product = rows.find((row) => row.product_id === movement.product_id);
      return <View key={movement.movement_id} style={styles.card}><Text style={styles.name}>{product?.name ?? movement.product_id} · {movement.quantity_delta > 0 ? '+' : ''}{movement.quantity_delta}</Text><Text style={styles.muted}>{movement.reason_code.replaceAll('_', ' ')} · {new Date(movement.occurred_at).toLocaleString()}{movement.requires_review ? ' · requires review' : ''}</Text></View>;
    })}
    {!movements.length ? <Text style={styles.muted}>No stock movements recorded.</Text> : null}
  </ScrollView>;
}
function Metric({ label, value }: { label: string; value: string }) { return <View style={styles.metric}><Text style={styles.muted}>{label}</Text><Text style={styles.metricValue}>{value}</Text></View>; }
function InventoryCard({ row, alert = false }: { row: InventoryRow; alert?: boolean }) { return <View style={[styles.card, alert && styles.alert]}>
  {row.thumbnail_url || row.image_url ? <Image source={{ uri: row.thumbnail_url ?? row.image_url ?? undefined }} style={styles.image} contentFit="cover" /> : null}
  <View style={styles.row}><View style={styles.grow}><Text style={styles.name}>{row.name}</Text><Text style={styles.muted}>{row.sku} · threshold {row.low_stock_threshold} · reorder point {row.reorder_point}</Text></View><Text style={[styles.qty, alert && styles.error]}>{row.quantity}</Text></View>
  <Text style={styles.muted}>Value at cost: {formatPeso(Number(row.inventory_value))} {alert ? '· Reorder needed' : ''}</Text>
</View>; }
const styles = StyleSheet.create({ container: { flex: 1, backgroundColor: '#f7f7f7' }, content: { padding: 16, gap: 10 }, center: { flex: 1, justifyContent: 'center', alignItems: 'center' }, title: { fontSize: 22, fontWeight: '700' }, subtitle: { color: '#555' }, section: { fontSize: 17, fontWeight: '700', marginTop: 8 }, metrics: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' }, metric: { flexGrow: 1, backgroundColor: '#fff', padding: 12, borderRadius: 10 }, metricValue: { fontSize: 17, fontWeight: '700', marginTop: 4 }, card: { backgroundColor: '#fff', borderRadius: 10, padding: 12, gap: 6 }, alert: { borderWidth: 1, borderColor: '#d1242f' }, row: { flexDirection: 'row', alignItems: 'center', gap: 10 }, grow: { flex: 1 }, name: { fontWeight: '600' }, muted: { color: '#666', fontSize: 12 }, qty: { fontWeight: '700', fontSize: 18 }, error: { color: '#c0392b' }, image: { width: 56, height: 56, borderRadius: 8 }, });
