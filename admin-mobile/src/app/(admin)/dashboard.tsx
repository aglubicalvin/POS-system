import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'expo-router';
import {
  ActivityIndicator,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useAdminAuth } from '@/context/AuthContext';
import { fetchDashboard, type DashboardData } from '@/lib/queries';
import { formatPeso } from '@/lib/summary';

export default function Dashboard() {
  const { signOut, profile } = useAdminAuth();
  const router = useRouter();
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      setError(null);
      setData(await fetchDashboard());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load dashboard');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

  const s = data?.summary;
  const refetched = data ? `Updated ${new Date(data.refetchedAt).toLocaleTimeString()}` : '';

  return (
    <ScrollView
      style={styles.container}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
    >
      <View style={styles.header}>
        <Text style={styles.greeting}>{profile?.role === 'admin' ? 'Business overview' : 'Overview'}</Text>
        <View style={styles.headerActions}>
          <TouchableOpacity onPress={() => router.push('/(admin)/products')}>
            <Text style={styles.signOut}>Products</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => router.push('/(admin)/customers')}>
            <Text style={styles.signOut}>Customers</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => router.push('/(admin)/hire-purchase')}>
            <Text style={styles.signOut}>Hire purchase</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => router.push('/(admin)/inventory')}>
            <Text style={styles.signOut}>Inventory</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => router.push('/(admin)/pos')}>
            <Text style={styles.signOut}>POS (preview)</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={signOut}>
            <Text style={styles.signOut}>Sign out</Text>
          </TouchableOpacity>
        </View>
      </View>
      <Text style={styles.updated}>{refetched}</Text>

      {error ? <Text style={styles.error}>{error}</Text> : null}
      {!s && !error ? (
        <ActivityIndicator style={{ marginTop: 32 }} size="large" />
      ) : null}

      {s ? (
        <>
          <Window title="Today" window={s.today} />
          <Window title="This week" window={s.thisWeek} />
          <Window title="This month" window={s.thisMonth} />

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Low stock ({data?.lowStock.length ?? 0})</Text>
            {data?.lowStock.slice(0, 8).map((row) => (
              <View key={row.sku} style={styles.rowLine}>
                <Text style={styles.rowText}>{row.name}</Text>
                <Text style={styles.rowText}>
                  {row.quantity}/{row.low_stock_threshold}
                </Text>
              </View>
            ))}
          </View>
        </>
      ) : null}
    </ScrollView>
  );
}

function Window({ title, window: w }: { title: string; window: DashboardData['summary']['today'] }) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      {w ? (
        <>
          <Metric label="Revenue" value={formatPeso(w.revenue)} />
          <Metric label="Gross profit" value={formatPeso(w.grossProfit)} />
          <Metric label="Discounts" value={formatPeso(w.discounts)} />
          <Metric label="Tax" value={formatPeso(w.tax)} />
          <Metric label="Refunds" value={formatPeso(w.refundsTotal)} />
          <Metric label="Sales" value={`${w.salesCount}`} />
        </>
      ) : (
        <Text style={styles.muted}>No sales recorded in this window.</Text>
      )}
    </View>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.rowLine}>
      <Text style={styles.rowText}>{label}</Text>
      <Text style={[styles.rowText, styles.money]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f7f8fa' },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 16 },
  headerActions: { flexDirection: 'row', gap: 16, alignItems: 'center' },
  greeting: { fontSize: 22, fontWeight: '700' },
  signOut: { color: '#208AEF', fontWeight: '600' },
  updated: { paddingHorizontal: 16, fontSize: 12, color: '#777' },
  card: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginHorizontal: 16,
    marginTop: 12,
  },
  cardTitle: { fontSize: 16, fontWeight: '600', marginBottom: 8 },
  rowLine: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  rowText: { fontSize: 14, color: '#333' },
  money: { fontVariant: ['tabular-nums'] },
  muted: { color: '#888', fontSize: 13 },
  error: { color: '#c0392b', paddingHorizontal: 16, marginTop: 12 },
});
