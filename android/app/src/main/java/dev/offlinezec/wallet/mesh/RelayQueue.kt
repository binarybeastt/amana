package dev.offlinezec.wallet.mesh

import dev.offlinezec.wallet.core.hexToBytes
import dev.offlinezec.wallet.core.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The store-and-forward queue: every transaction this phone carries for the mesh (its own
 * payments, payments it received, and ones it relays for strangers), one JSON file each, so a
 * reboot or crash loses nothing. De-duplicated by txid.
 *
 * Bounded by [maxEntries] and [maxBytes]: when full, the oldest settled entries are evicted
 * first; unsettled entries are never evicted, and a new stranger's tx is refused instead.
 * Thread-safe.
 */
class RelayQueue(
    private val dir: File,
    private val maxEntries: Int = 200,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class State { QUEUED, SENT, SETTLED, REJECTED }

    data class Entry(
        val txid: String,
        val raw: ByteArray,
        /** Bluetooth hops from the phone that signed it (0 = signed here). */
        val hops: Int,
        /** Where it came from: "self", "qr", or the peer's node id. */
        val from: String,
        val firstSeen: Long,
        val state: State = State.QUEUED,
        val forUs: Boolean = false,
        val mine: Boolean = false,
        val nullifiers: List<String> = emptyList(),
        val minedHeight: Long? = null,
        val sentAt: Long? = null,
        val settledAt: Long? = null,
        val attempts: Int = 0,
        val lastResult: String? = null,
    ) {
        val unsettled get() = state == State.QUEUED || state == State.SENT
    }

    enum class AddResult { ADDED, DUPLICATE, FULL }

    private val lock = Any()

    /** Called (under the queue's lock) after a new entry is saved. */
    @Volatile var onAdded: ((Entry) -> Unit)? = null
    /** Called (under the queue's lock) when an entry becomes settled. */
    @Volatile var onSettled: ((Entry) -> Unit)? = null
    private val entries = LinkedHashMap<String, Entry>()

    /** Bumped when the set of forwardable entries changes, so the mesh knows peers need a sync. */
    @Volatile var version = 0L
        private set

    init {
        dir.mkdirs()
        // Leftover temp files are writes that never finished; the old file (if any) is intact.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { parse(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.firstSeen }
            .forEach { entries[it.txid] = it }
    }

    fun contains(txid: String): Boolean = synchronized(lock) { txid in entries }

    fun get(txid: String): Entry? = synchronized(lock) { entries[txid] }

    fun all(): List<Entry> = synchronized(lock) { entries.values.toList() }

    fun txids(): List<String> = synchronized(lock) { entries.keys.toList() }

    /** Unsettled entries a peer may receive from us: at most [maxHops] hops once forwarded. */
    fun forwardable(maxHops: Int = Proto.MAX_HOPS): List<Entry> =
        synchronized(lock) { entries.values.filter { it.unsettled && it.hops + 1 <= maxHops } }

    fun unsettled(): List<Entry> = synchronized(lock) { entries.values.filter { it.unsettled } }

    /**
     * Adds [e] unless its txid is already queued. [force] (this phone's own payments and ones
     * it received) skips the cap, evicting what it can.
     */
    fun add(e: Entry, force: Boolean = false): AddResult = synchronized(lock) {
        if (e.txid in entries) return AddResult.DUPLICATE
        val evict = ArrayList<String>()
        var count = entries.size + 1
        var bytes = entries.values.sumOf { it.raw.size.toLong() } + e.raw.size
        val settled = entries.values.filter { !it.unsettled }.sortedBy { it.settledAt ?: it.firstSeen }.iterator()
        while ((count > maxEntries || bytes > maxBytes) && settled.hasNext()) {
            val old = settled.next()
            evict += old.txid
            count--
            bytes -= old.raw.size
        }
        if ((count > maxEntries || bytes > maxBytes) && !force) return AddResult.FULL
        evict.forEach { remove(it) }
        write(e)
        entries[e.txid] = e
        version++
        onAdded?.invoke(e)
        AddResult.ADDED
    }

    /** Applies [f] to the entry for [txid] and saves it. Returns the new entry. */
    fun update(txid: String, f: (Entry) -> Entry): Entry? = synchronized(lock) {
        val old = entries[txid] ?: return null
        val new = f(old)
        if (new == old) return old
        write(new)
        entries[txid] = new
        if (new.unsettled != old.unsettled) version++
        if (new.state == State.SETTLED && old.state != State.SETTLED) onSettled?.invoke(new)
        new
    }

    fun markSettled(txid: String, height: Long?): Entry? = update(txid) {
        if (it.state == State.SETTLED && (height == null || it.minedHeight == height)) it
        else it.copy(state = State.SETTLED, minedHeight = height ?: it.minedHeight, settledAt = it.settledAt ?: now())
    }

    fun clear() = synchronized(lock) {
        entries.keys.toList().forEach { remove(it) }
        version++
    }

    private fun remove(txid: String) {
        entries.remove(txid)
        File(dir, "$txid.json").delete()
    }

    private fun write(e: Entry) {
        val tmp = File(dir, "${e.txid}.json.tmp")
        tmp.writeText(toJson(e).toString())
        check(tmp.renameTo(File(dir, "${e.txid}.json"))) { "could not save queue entry ${e.txid}" }
    }

    companion object {
        fun toJson(e: Entry, withRaw: Boolean = true): JSONObject = JSONObject()
            .put("txid", e.txid).put("hops", e.hops).put("from", e.from).put("first_seen", e.firstSeen)
            .put("state", e.state.name.lowercase()).put("for_us", e.forUs).put("mine", e.mine)
            .put("nullifiers", JSONArray(e.nullifiers))
            .put("mined_height", e.minedHeight ?: JSONObject.NULL).put("sent_at", e.sentAt ?: JSONObject.NULL)
            .put("settled_at", e.settledAt ?: JSONObject.NULL).put("attempts", e.attempts)
            .put("last_result", e.lastResult ?: JSONObject.NULL)
            .put("size", e.raw.size)
            .apply { if (withRaw) put("raw_hex", e.raw.toHex()) }

        private fun parse(o: JSONObject): Entry {
            fun long(k: String) = if (o.isNull(k)) null else o.getLong(k)
            val nfs = o.getJSONArray("nullifiers")
            return Entry(
                txid = o.getString("txid"),
                raw = o.getString("raw_hex").hexToBytes(),
                hops = o.getInt("hops"),
                from = o.getString("from"),
                firstSeen = o.getLong("first_seen"),
                state = State.valueOf(o.getString("state").uppercase()),
                forUs = o.getBoolean("for_us"),
                mine = o.getBoolean("mine"),
                nullifiers = (0 until nfs.length()).map { nfs.getString(it) },
                minedHeight = long("mined_height"),
                sentAt = long("sent_at"),
                settledAt = long("settled_at"),
                attempts = o.getInt("attempts"),
                lastResult = if (o.isNull("last_result")) null else o.getString("last_result"),
            )
        }
    }
}
