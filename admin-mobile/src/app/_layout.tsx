import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AdminAuthProvider } from '@/context/AuthContext';

export default function RootLayout() {
  return (
    <AdminAuthProvider>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="index" />
        <Stack.Screen name="login" />
        <Stack.Screen name="(admin)" />
      </Stack>
    </AdminAuthProvider>
  );
}
