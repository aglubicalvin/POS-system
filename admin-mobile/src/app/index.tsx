import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { Redirect } from 'expo-router';
import { useAdminAuth } from '@/context/AuthContext';

/**
 * Entry point. Every route the user can land on funnels through the gate —
 * there is no UI state a user can reach that the backend has not verified.
 */
export default function Entry() {
  const { status } = useAdminAuth();
  if (status === 'authorized') return <Redirect href="/(admin)/dashboard" />;
  if (status === 'pending-mfa' || status === 'denied') return <Redirect href="/login" />;
  if (status === 'signed-out') return <Redirect href="/login" />;
  return (
    <View style={styles.center}>
      <ActivityIndicator size="large" />
    </View>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
