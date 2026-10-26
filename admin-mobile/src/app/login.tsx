import { useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useAdminAuth, AuthFlowError } from '@/context/AuthContext';

/**
 * Administrator sign-in, with the MFA step shown only when the backend's
 * gate says the account needs a second factor. Note what is NOT here: no
 * client-side role switch of any kind — the app does not decide whether a
 * user is an admin, the database does.
 */
export default function Login() {
  const { status, gate, message, signIn, verifyMfa } = useAdminAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSignIn() {
    setBusy(true);
    setError(null);
    try {
      const { mfaRequired } = await signIn(email.trim(), password);
      if (!mfaRequired) return; // context moves us to the dashboard
      // stay on this screen; the context flips to 'pending-mfa'
    } catch (e) {
      setError(e instanceof AuthFlowError ? e.message : 'Sign-in failed. Check your connection.');
    } finally {
      setBusy(false);
    }
  }

  async function handleVerifyMfa() {
    setBusy(true);
    setError(null);
    try {
      await verifyMfa(code.trim());
    } catch (e) {
      setError(e instanceof AuthFlowError ? e.message : 'Verification failed.');
    } finally {
      setBusy(false);
    }
  }

  if (status === 'pending-mfa') {
    return (
      <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Text style={styles.title}>Second factor required</Text>
        <Text style={styles.subtitle}>{message ?? 'Enter the code from your authenticator app.'}</Text>
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <TextInput
          style={[styles.input, styles.codeInput]}
          placeholder="000000"
          placeholderTextColor="#888"
          keyboardType="number-pad"
          maxLength={6}
          value={code}
          onChangeText={setCode}
          autoComplete="one-time-code"
        />
        <TouchableOpacity style={styles.button} onPress={handleVerifyMfa} disabled={busy || code.length < 6}>
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Verify</Text>}
        </TouchableOpacity>
      </KeyboardAvoidingView>
    );
  }

  return (
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Text style={styles.title}>Store Admin</Text>
      <Text style={styles.subtitle}>{message ?? 'Administrator sign-in. Administrator accounts only.'}</Text>
      {error ? <Text style={styles.error}>{error}</Text> : null}
      {gate?.access === 'denied' && (
        <Text style={styles.denied}>Access denied: {gate.reason}</Text>
      )}
      <TextInput
        style={styles.input}
        placeholder="Email"
        placeholderTextColor="#888"
        autoCapitalize="none"
        keyboardType="email-address"
        value={email}
        onChangeText={setEmail}
      />
      <TextInput
        style={styles.input}
        placeholder="Password"
        placeholderTextColor="#888"
        secureTextEntry
        value={password}
        onChangeText={setPassword}
      />
      <TouchableOpacity style={styles.button} onPress={handleSignIn} disabled={busy || !email || !password}>
        {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Sign in</Text>}
      </TouchableOpacity>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 24, backgroundColor: '#fff' },
  title: { fontSize: 28, fontWeight: '700', marginBottom: 8 },
  subtitle: { fontSize: 14, color: '#555', marginBottom: 24 },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    marginBottom: 12,
  },
  codeInput: { letterSpacing: 12, textAlign: 'center' },
  button: {
    backgroundColor: '#208AEF',
    borderRadius: 8,
    padding: 14,
    alignItems: 'center',
    marginTop: 8,
  },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  error: { color: '#c0392b', marginBottom: 12 },
  denied: { color: '#c0392b', marginBottom: 16 },
});
