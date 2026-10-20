package dev.offlinezec.wallet.wallet

import android.app.Application
import android.util.Log
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import dev.offlinezec.wallet.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Testnet until Phase 6. */
object Net {
    val network: ZcashNetwork = ZcashNetwork.Testnet
    val endpoint = LightWalletEndpoint("testnet.zec.rocks", 443, true)
    const val ALIAS = "offline"
    const val CORE_NETWORK = "test"
}

/** What the SDK reports about sync and balance. */
data class SyncInfo(
    val status: String = "INITIALIZING",
    val progress: Float = 0f,
    val scannedHeight: Long? = null,
    val networkHeight: Long? = null,
    val ironwoodAvailable: Long = 0,
    val ironwoodTotal: Long = 0,
    val orchardTotal: Long = 0,
    val saplingTotal: Long = 0,
) {
    val shieldedTotal get() = ironwoodTotal + orchardTotal + saplingTotal
    val atTip get() = status == "SYNCED"
}

/** An open wallet: the SDK synchronizer plus what offline-core needs to reach the same DB. */
class OpenWallet(
    val synchronizer: CloseableSynchronizer,
    val account: Account,
    /** Account UUID as offline-core prints it (8-4-4-4-12 hex). */
    val accountUuid: String,
    val address: String,
    /** The SDK's wallet database, which offline-core opens from the `:core` process. */
    val dbPath: String,
)

sealed interface WalletState {
    data object Loading : WalletState
    data object NoWallet : WalletState
    data class Opening(val what: String) : WalletState
    data class Ready(val wallet: OpenWallet) : WalletState
    data class Failed(val error: String) : WalletState
}

/**
 * Owns the wallet: creates or restores it from a BIP 39 seed, opens the SDK synchronizer (sync,
 * balance, broadcast) and exposes the DB path offline-core uses for offline work.
 */
class WalletManager(private val app: Application, private val scope: CoroutineScope) {
    val core = Core(app, Net.CORE_NETWORK)
    private val seeds = SeedStore(app)
    private val lock = Mutex()
    private var flowsJob: Job? = null

    private val _state = MutableStateFlow<WalletState>(WalletState.Loading)
    val state: StateFlow<WalletState> = _state
    private val _sync = MutableStateFlow(SyncInfo())
    val sync: StateFlow<SyncInfo> = _sync

    val wallet: OpenWallet? get() = (_state.value as? WalletState.Ready)?.wallet

    /** Opens the stored wallet, if there is one. */
    suspend fun start() = lock.withLock {
        if (_state.value is WalletState.Ready) return@withLock
        if (seeds.mnemonic == null) {
            _state.value = WalletState.NoWallet
        } else {
            open(WalletInitMode.ExistingWallet, seeds.birthday)
        }
    }

    /** New 24-word seed; the wallet starts at the current chain tip. */
    suspend fun create() = lock.withLock {
        check(seeds.mnemonic == null) { "a wallet already exists" }
        val code = Mnemonics.MnemonicCode(Mnemonics.WordCount.COUNT_24)
        seeds.save(code.words.joinToString(" ") { String(it) }, null)
        open(WalletInitMode.NewWallet, null)
    }

    /** Restores from a mnemonic, scanning from [birthday]. */
    suspend fun restore(mnemonic: String, birthday: Long) = lock.withLock {
        check(seeds.mnemonic == null) { "a wallet already exists" }
        val normalized = mnemonic.trim().split(Regex("\\s+")).joinToString(" ")
        Mnemonics.MnemonicCode(normalized).validate()
        seeds.save(normalized, birthday)
        open(WalletInitMode.RestoreWallet, birthday)
    }

    private suspend fun open(mode: WalletInitMode, birthday: Long?) {
        _state.value = WalletState.Opening(if (mode == WalletInitMode.RestoreWallet) "Restoring wallet" else "Opening wallet")
        try {
            val seed = seed()
            val sync = Synchronizer.new(
                alias = Net.ALIAS,
                birthday = birthday?.let { BlockHeight.new(it) },
                context = app,
                lightWalletEndpoint = Net.endpoint,
                setup = AccountCreateSetup(accountName = "Main", keySource = null, seed = FirstClassByteArray(seed)),
                walletInitMode = mode,
                zcashNetwork = Net.network,
                isTorEnabled = false,
                isExchangeRateEnabled = false,
            )
            val account = sync.getAccounts().first()
            val wallet = OpenWallet(
                synchronizer = sync,
                account = account,
                accountUuid = account.accountUuid.value.toUuidString(),
                address = sync.getUnifiedAddress(account),
                dbPath = sync.getExistingDataDbFilePath(app, Net.network, Net.ALIAS),
            )
            // The account's birthday as stored in the wallet DB (for a new wallet the SDK picks it
            // from the tree state near the tip; `latestBirthdayHeight` would say Sapling activation).
            runCatching {
                val accounts = core.statusJson(wallet.dbPath).getJSONArray("accounts")
                (0 until accounts.length()).map { accounts.getJSONObject(it) }
                    .first { it.getString("account_uuid") == wallet.accountUuid }
                    .getLong("birthday_height")
            }.onSuccess { seeds.saveBirthday(it) }
                .onFailure { Log.w(TAG, "could not read the account birthday: ${it.message}") }
            watch(wallet)
            _state.value = WalletState.Ready(wallet)
        } catch (e: Exception) {
            Log.e(TAG, "opening wallet failed", e)
            _state.value = WalletState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun watch(w: OpenWallet) {
        flowsJob?.cancel()
        val s = w.synchronizer
        flowsJob = scope.launch {
            combine(s.status, s.progress, s.networkHeight, s.fullyScannedHeight, s.walletBalances) { st, pr, net, scanned, bal ->
                val b = bal?.get(w.account.accountUuid)
                SyncInfo(
                    status = st.name,
                    progress = pr.decimal,
                    scannedHeight = scanned?.value,
                    networkHeight = net?.value,
                    ironwoodAvailable = b?.ironwood?.available?.value ?: 0,
                    ironwoodTotal = b?.ironwood?.total?.value ?: 0,
                    orchardTotal = b?.orchard?.total?.value ?: 0,
                    saplingTotal = b?.sapling?.total?.value ?: 0,
                )
            }.collect { info -> _sync.update { info } }
        }
    }

    /** The BIP 39 seed bytes (sensitive: do not keep them around). */
    fun seed(): ByteArray = Mnemonics.MnemonicCode(seeds.mnemonic ?: error("no wallet")).toSeed()

    /** Debug builds only: the mnemonic, so a test can restore it on a second phone. */
    fun mnemonicForTests(): String? = seeds.mnemonic

    fun birthday(): Long? = seeds.birthday

    suspend fun spendingKey(): UnifiedSpendingKey =
        DerivationTool.getInstance().deriveUnifiedSpendingKey(seed(), Net.network, Zip32AccountIndex.new(0))

    private companion object {
        const val TAG = "WalletManager"
    }
}

fun ByteArray.toUuidString(): String {
    val h = joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
