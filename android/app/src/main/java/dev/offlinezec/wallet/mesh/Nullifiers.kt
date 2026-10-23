package dev.offlinezec.wallet.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Double-spend detection: which transaction spends which nullifier, as far as this phone knows.
 * Claims come from the transactions in our relay queue ([local]: proofs verified here) and from
 * peers' gossip ([heard]: taken on their word). Two different txids claiming one nullifier is a
 * double spend: a [Conflict], kept and saved to [file] (the newest [maxConflicts]).
 *
 * Bounded: a settled transaction's nullifiers are dropped from the claims ([settle]) and kept
 * only as a capped list of tombstones, so stale gossip cannot bring them back; gossip-only
 * claims beyond [maxClaims] are evicted oldest first. Claims from our own queue are never
 * evicted (the queue is capped itself). Thread-safe.
 */
class Nullifiers(
    private val file: File? = null,
    private val maxClaims: Int = 4096,
    private val maxSettled: Int = 4096,
    private val maxConflicts: Int = 100,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called (outside the lock) when a conflict is found or gains a txid. */
    private val onConflict: (Conflict) -> Unit = {},
) {
    data class Claim(val nullifier: String, val txid: String)

    data class Conflict(
        val nullifier: String,
        /** Every txid seen spending [nullifier], sorted. Always at least two. */
        val txids: List<String>,
        val detectedAt: Long,
        /** True when this phone holds and verified every one of [txids]; else some came by gossip. */
        val verified: Boolean,
        /** The txid that was mined, once one was. */
        val settled: String? = null,
    )

    private class Info(val local: Boolean)

    private val lock = Any()
    private val claims = LinkedHashMap<String, LinkedHashMap<String, Info>>()
    private var claimCount = 0
    private val settled = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxSettled
    }
    private val conflicts = LinkedHashMap<String, Conflict>()

    /** Bumped when a conflict is found or changes, so the mesh spreads it to every peer. */
    @Volatile var version = 0L
        private set

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) parse(a.getJSONObject(i)).let { conflicts[it.nullifier] = it }
            }
        }
    }

    /** A transaction in our queue (verified here) spends [nullifiers]. */
    fun local(txid: String, nullifiers: Collection<String>) = add(nullifiers.map { Claim(it, txid) }, local = true)

    /** A peer says these claims hold. */
    fun heard(claims: Collection<Claim>) = add(claims, local = false)

    /** [txid] was mined: its nullifiers are spent for good and leave the gossip set. */
    fun settle(txid: String, nullifiers: Collection<String>) {
        val found = ArrayList<Conflict>()
        synchronized(lock) {
            for (nf in nullifiers) {
                settled[nf] = txid
                val others = claims.remove(nf)?.also { claimCount -= it.size }?.keys.orEmpty() - txid
                if (others.isNotEmpty() || nf in conflicts) {
                    record(nf, others + txid, verified = false, settledTxid = txid)?.let { found += it }
                }
            }
            if (found.isNotEmpty()) changed()
        }
        found.forEach(onConflict)
    }

    /** What we tell a peer: our own claims first, then the newest gossip, at most [limit]. */
    fun share(limit: Int = Proto.MAX_CLAIMS): List<Claim> = synchronized(lock) {
        val all = claims.flatMap { (nf, m) -> m.map { (txid, i) -> Triple(nf, txid, i.local) } }
        (all.filter { it.third } + all.filter { !it.third }.asReversed()).take(limit).map { Claim(it.first, it.second) }
    }

    /** Nullifier → txid claims held (the gossip set). */
    fun size(): Int = synchronized(lock) { claimCount }

    fun settledCount(): Int = synchronized(lock) { settled.size }

    fun conflicts(): List<Conflict> = synchronized(lock) { conflicts.values.toList() }

    /** Debug: forget everything (claims, tombstones, conflicts). */
    fun clear() = synchronized(lock) {
        claims.clear()
        claimCount = 0
        settled.clear()
        conflicts.clear()
        changed()
    }

    private fun add(new: Collection<Claim>, local: Boolean) {
        val found = ArrayList<Conflict>()
        synchronized(lock) {
            for ((nf, txid) in new) {
                val done = settled[nf]
                if (done != null) {
                    // Spent on chain already: any other txid is a (losing) double spend.
                    if (done != txid) record(nf, listOf(done, txid), local, settledTxid = done)?.let { found += it }
                    continue
                }
                val m = claims.getOrPut(nf) { LinkedHashMap() }
                val old = m[txid]
                if (old == null) claimCount++
                if (old == null || (local && !old.local)) m[txid] = Info(local || old?.local == true)
                if (m.size >= 2) record(nf, m.keys.toList(), m.values.all { it.local })?.let { found += it }
            }
            evict()
            if (found.isNotEmpty()) changed()
        }
        found.forEach(onConflict)
    }

    /** Under the lock. Returns the conflict if it is new or changed. */
    private fun record(nf: String, txids: Collection<String>, verified: Boolean, settledTxid: String? = null): Conflict? {
        val old = conflicts[nf]
        val all = (txids + old?.txids.orEmpty()).toSortedSet().toList()
        val c = Conflict(nf, all, old?.detectedAt ?: now(), verified || old?.verified == true, settledTxid ?: old?.settled)
        if (c == old) return null
        conflicts.remove(nf)
        conflicts[nf] = c
        while (conflicts.size > maxConflicts) conflicts.remove(conflicts.keys.first())
        return c
    }

    /** Under the lock: drop the oldest gossip-only claims past the cap. */
    private fun evict() {
        if (claimCount <= maxClaims) return
        val it = claims.entries.iterator()
        while (claimCount > maxClaims && it.hasNext()) {
            val (_, m) = it.next()
            val gossip = m.entries.iterator()
            while (claimCount > maxClaims && gossip.hasNext()) {
                if (!gossip.next().value.local) {
                    gossip.remove()
                    claimCount--
                }
            }
            if (m.isEmpty()) it.remove()
        }
    }

    private fun changed() {
        version++
        file?.let { f ->
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONArray(conflicts.values.map(::toJson)).toString())
            tmp.renameTo(f)
        }
    }

    companion object {
        fun toJson(c: Conflict): JSONObject = JSONObject().put("nullifier", c.nullifier).put("txids", JSONArray(c.txids))
            .put("detected_at", c.detectedAt).put("verified", c.verified).put("settled", c.settled ?: JSONObject.NULL)

        private fun parse(o: JSONObject): Conflict {
            val t = o.getJSONArray("txids")
            return Conflict(
                o.getString("nullifier"), (0 until t.length()).map { t.getString(it) }, o.getLong("detected_at"),
                o.getBoolean("verified"), if (o.isNull("settled")) null else o.getString("settled"),
            )
        }
    }
}
