package com.businessstore.pos.sync

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.SyncEventEnvelope
import kotlinx.serialization.json.JsonObject

/**
 * The server → device half of sync (the DOWN half).
 *
 * `EventApplier` ingests device events; this relays a whitelisted subset of
 * them to the OTHER tills of the same store, so a catalog or store-global user
 * change made on one device reaches the rest. A receiving till applies them
 * through the ordered applier in `src/sync/ordering.ts` (TypeScript side), so
 * delivery does not need to be in wire order — duplicates and gaps are handled
 * there.
 *
 * The envelope is re-served exactly as it was ingested: the payload belongs to
 * whatever produced it on the sending device, and the receiving device's
 * dispatcher (`src/sync/apply.ts`) is the consumer that knows its shape. That
 * shape is pinned cross-language by `contract/pull.json`, which both sides read.
 *
 * Cursors are per source device and carried by the caller (kept in the device's
 * own `device_sync_state`), so this query is stateless: events at or before a
 * source device's cursor are filtered out before the limit is applied.
 */
class EventPull(private val db: Database) {

    fun pull(storeId: String, ownDeviceId: String, since: Map<String, Long>, limit: Int): List<SyncEventEnvelope> {
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        return db.read { conn ->
            conn.prepareStatement(SQL).use { st ->
                st.setString(1, storeId)
                st.setString(2, ownDeviceId)
                st.setArray(3, conn.createArrayOf("text", RELAYABLE))
                st.setInt(4, SCAN_LIMIT)
                st.executeQuery().use { rs ->
                    val out = ArrayList<SyncEventEnvelope>()
                    while (rs.next() && out.size < limit) {
                        val source = rs.getString("device_id")
                        val seq = rs.getLong("seq")
                        // Callers resume at a delivered seq, so anything at or
                        // before their cursor has already been applied there.
                        if (seq <= (since[source] ?: 0L)) continue
                        out.add(
                            SyncEventEnvelope(
                                eventId = rs.getString("event_id"),
                                deviceId = source,
                                seq = seq,
                                eventType = rs.getString("event_type"),
                                aggregateType = rs.getString("aggregate_type"),
                                aggregateId = rs.getString("aggregate_id"),
                                payload = PosJson.decodeFromString(JsonObject.serializer(), rs.getString("payload")),
                            ),
                        )
                    }
                    out
                }
            }
        }
    }

    companion object {
        /**
         * Event types a pull may hand to other tills. Sale, shift, payment and
         * movement rows are store-level history the SERVER reconciles from; tills
         * do not replicate each other's sales.
         */
        val RELAYABLE = arrayOf("product.upserted", "user.upserted")

        /**
         * Rows fetched per pull before cursor filtering. Relayable events are a
         * store's catalog and store-global users — hundreds at most — so a capped
         * scan beats dynamically shaped cursor SQL.
         */
        const val SCAN_LIMIT = 10_000

        private const val SQL = """
            SELECT pe.event_id, pe.device_id, pe.seq, pe.event_type, pe.aggregate_type,
                   pe.aggregate_id, pe.payload
            FROM processed_events pe
            JOIN devices d ON d.device_id = pe.device_id
            WHERE d.store_id = ?
              AND pe.device_id <> ?
              AND pe.event_type = ANY (?)
            ORDER BY pe.device_id, pe.seq
            LIMIT ?
        """
    }
}
