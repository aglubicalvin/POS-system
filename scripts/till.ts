/**
 * Run the Till UI against a local SQLite database:
 *
 *   TILL_DB=./till.db TILL_DEVICE_ID=<registered-device-uuid> npm run till
 *
 * The device must be registered with the server first (its token lives beside
 * the database); the UI binds to 127.0.0.1 so only the till itself serves it —
 * put a reverse proxy with TLS in front for anything beyond a pilot.
 */
import { openDb } from '../src/db/db.ts';
import { startTillUi } from '../src/ui/server.ts';

const dbPath = process.env.TILL_DB;
if (!dbPath) {
  console.error('Set TILL_DB to the SQLite file for this till (it is created if missing).');
  process.exit(1);
}

const db = openDb(dbPath);
const till = await startTillUi(db, {
  port: Number(process.env.TILL_PORT ?? 8787),
  deviceId: process.env.TILL_DEVICE_ID,
});

console.log(`Till UI on http://127.0.0.1:${till.port} (device ${process.env.TILL_DEVICE_ID ?? 'unbound, passed at login'})`);
process.on('SIGINT', () => void till.close().then(() => process.exit(0)));
