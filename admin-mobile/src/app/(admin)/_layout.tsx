import { useEffect } from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAdminAuth } from '@/context/AuthContext';

/**
 * Guard for the administrative area. This runs on EVERY navigation into the
 * group — deep links, hardware back-snapshots, anything — so a hand-typed
 * route cannot reach a screen without the backend-verified gate being green.
 * The server backs this up with RLS on every table the screens read.
 */
export default function AdminLayout() {
  const { status } = useAdminAuth();
  if (status !== 'authorized') return <Redirect href="/login" />;
  return (
    <Stack>
      <Stack.Screen name="dashboard" options={{ title: 'Dashboard', headerShown: true }} />
      <Stack.Screen name="products" options={{ title: 'Products & prices', headerShown: true }} />
    </Stack>
  );
}
