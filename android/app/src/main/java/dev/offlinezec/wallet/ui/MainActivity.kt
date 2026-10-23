package dev.offlinezec.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.offlinezec.wallet.App
import dev.offlinezec.wallet.AppController
import dev.offlinezec.wallet.BuildConfig
import dev.offlinezec.wallet.PayState
import dev.offlinezec.wallet.ReceiveState
import dev.offlinezec.wallet.incomingUnsettled
import dev.offlinezec.wallet.mesh.Mesh
import dev.offlinezec.wallet.qr.FrameEncoder
import dev.offlinezec.wallet.qr.QrAnalyzer
import dev.offlinezec.wallet.receivedLabel
import dev.offlinezec.wallet.wallet.Caps
import dev.offlinezec.wallet.wallet.Limits
import dev.offlinezec.wallet.wallet.PrepareStatus
import dev.offlinezec.wallet.wallet.SPEND_CONFIRMATIONS
import dev.offlinezec.wallet.wallet.SeedWarning
import dev.offlinezec.wallet.wallet.SyncInfo
import dev.offlinezec.wallet.wallet.WalletState
import dev.offlinezec.wallet.zec
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.util.concurrent.Executors

enum class Screen { Home, Prepare, Pay, Receive, Payments, Limits }

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val c = App.controller(this)
        // Bluetooth relaying needs these; the mesh starts as soon as they are granted.
        val missing = Mesh.PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.mesh.permissionsChanged() }
                .launch(missing.toTypedArray())
        } else {
            c.mesh.permissionsChanged()
        }
        setContent {
            WalletTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = Ink.page) {
                    Root(c)
                }
            }
        }
    }
}

@Composable
fun Root(c: AppController) {
    val state by c.wallet.state.collectAsState()
    when (val s = state) {
        WalletState.Loading -> Centered("Loading…")
        WalletState.NoWallet -> SetupScreen(c)
        is WalletState.Opening -> Centered("${s.what}…")
        is WalletState.Failed -> Centered("Wallet failed to open:\n${s.error}")
        is WalletState.Ready -> WalletScreens(c)
    }
    SeedWarningDialog(c)
}

@Composable
fun Centered(text: String) {
    Column(
        Modifier.fillMaxSize().background(Ink.page).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZecLogo(64.dp)
        Spacer(Modifier.height(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("status_text"))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.text, unfocusedBorderColor = Ink.line, disabledBorderColor = Ink.card,
    focusedContainerColor = Ink.card, unfocusedContainerColor = Ink.card, disabledContainerColor = Ink.card,
    focusedLabelColor = Ink.text, unfocusedLabelColor = Ink.dim, cursorColor = Ink.text,
)

@Composable
fun SetupScreen(c: AppController) {
    var restoring by remember { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf("") }
    val msg by c.message.collectAsState()
    Page {
        Spacer(Modifier.height(48.dp))
        ZecLogo(56.dp)
        Text("Offline ZEC", style = MaterialTheme.typography.displayMedium)
        Text("Pay shielded Zcash with no internet. Any phone that gets online later broadcasts it.",
            style = MaterialTheme.typography.bodyLarge, color = Ink.dim)
        Chip("testnet")
        Spacer(Modifier.height(24.dp))
        if (!restoring) {
            PrimaryButton("Create a new wallet", { c.createWallet() }, Modifier.testTag("create_wallet"))
            SecondaryButton("Restore from seed phrase", { restoring = true }, Modifier.testTag("restore_wallet"))
        } else {
            OutlinedTextField(words, { words = it }, label = { Text("24-word seed phrase") }, shape = RoundedCornerShape(20.dp),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("restore_words"))
            OutlinedTextField(birthday, { birthday = it.filter(Char::isDigit) }, label = { Text("Birthday height") }, shape = RoundedCornerShape(20.dp),
                colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("restore_birthday"))
            PrimaryButton(
                if (words.isBlank()) "Enter the seed phrase" else if (birthday.isBlank()) "Enter the birthday height" else "Restore",
                { c.restoreWallet(words, birthday.toLongOrNull() ?: 0) },
                Modifier.testTag("restore_go"), enabled = words.isNotBlank() && birthday.isNotBlank(),
            )
            SecondaryButton("Back", { restoring = false })
        }
        msg?.let { Text(it, color = Ink.red) }
    }
}

/** P5.G3: blocks the whole app until acknowledged; Back and taps outside do nothing. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeedWarningDialog(c: AppController) {
    val w by c.seedWarning.collectAsState()
    val warning = w ?: return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        DarkCard(Modifier.semantics { testTagsAsResourceId = true }.testTag("seed_warning"), color = Ink.raised, padding = 24.dp) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.redCard), contentAlignment = Alignment.Center) {
                Text("!", color = Ink.red, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Text(
                if (warning == SeedWarning.RESTORED) "Is this seed on another phone?" else "You're offline: one phone per seed",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("seed_warning_title"),
            )
            Text(
                if (warning == SeedWarning.RESTORED)
                    "You restored this wallet from a seed phrase. If the same seed is still on another phone, never pay offline from both. " +
                        "Two phones paying offline from one seed spend the same notes: only one payment can settle, and the other receiver goes unpaid."
                else
                    "Offline payments are only checked once a phone gets online. This wallet was restored from a seed: " +
                        "if that seed is on another phone too, pay offline from only one of them.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.dim,
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton("I understand", { c.acknowledgeSeedWarning(warning) }, Modifier.testTag("seed_ack"))
        }
    }
}

@Composable
fun WalletScreens(c: AppController) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> HomeScreen(c) { screen = it }
        Screen.Prepare -> PrepareScreen(c) { screen = Screen.Home }
        Screen.Pay -> PayScreen(c, prepare = { screen = Screen.Prepare }) { screen = Screen.Home }
        Screen.Receive -> ReceiveScreen(c) { screen = Screen.Home }
        Screen.Payments -> PaymentsScreen(c, showQr = { screen = Screen.Pay }) { screen = Screen.Home }
        Screen.Limits -> LimitsScreen(c) { screen = Screen.Home }
    }
}

/** "Offline · last synced at block 4,530,694", or how far sync has got. */
fun syncText(s: SyncInfo): String {
    val at = s.scannedHeight?.let { "%,d".format(it) } ?: "?"
    return when {
        s.status == "SYNCING" && s.networkHeight != null ->
            "Syncing · ${(s.progress * 100).toInt()}% · block $at of ${"%,d".format(s.networkHeight)}"
        s.networkHeight == null || s.status == "DISCONNECTED" -> "Offline · last synced at block $at"
        s.atTip -> "Online · synced to block $at"
        else -> "${s.status.lowercase().replaceFirstChar { it.uppercase() }} · block $at"
    }
}

fun zecText(zats: Long, big: Boolean = false): AnnotatedString = buildAnnotatedString {
    append(zec(zats))
    withStyle(SpanStyle(color = Ink.dim, fontSize = if (big) 24.sp else 14.sp, fontWeight = FontWeight.SemiBold)) { append(" ZEC") }
}

@Composable
fun HomeScreen(c: AppController, go: (Screen) -> Unit) {
    c.wallet.wallet ?: return
    val sync by c.wallet.sync.collectAsState()
    val prep by c.prepare.collectAsState()
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    val notes by c.notes.collectAsState()
    val m by c.meshInfo.collectAsState()
    val amounts by c.sentAmounts.collectAsState()
    val msg by c.message.collectAsState()
    val caps by c.capsState.collectAsState()
    val split = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight)
    Page {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZecLogo(32.dp)
            Text("Offline ZEC", style = MaterialTheme.typography.titleMedium)
            Chip("testnet")
            Spacer(Modifier.weight(1f))
            RoundIcon("⚙", { go(Screen.Limits) }, tag = "nav_limits")
        }
        ConflictBanner(c)
        BalanceCard(c, sync, split, received)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("↑  Pay", { c.clearPay(); go(Screen.Pay) }, Modifier.weight(1f).testTag("nav_pay"))
            SecondaryButton("↓  Receive", { c.resetReceive(); go(Screen.Receive) }, Modifier.weight(1f).height(60.dp).testTag("nav_receive"))
        }
        // Prepare for offline: a secondary card with where it stands.
        val (prepChip, prepLine) = when (val p = prep) {
            is PrepareStatus.Waiting -> "confirming" to "Split sent, waiting to be mined"
            is PrepareStatus.Idle -> when {
                p.ready -> "ready" to "${p.notes.size} notes · pay exact amounts with no internet"
                p.notes.isEmpty() -> "not ready" to "Needs a connection: splits your balance into notes"
                else -> "confirming" to "${p.notes.size} notes · waiting for $SPEND_CONFIRMATIONS confirmations"
            }
            null -> "…" to ""
        }
        DarkCard(Modifier.testTag("nav_prepare"), onClick = { go(Screen.Prepare) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Prepare for offline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Chip(prepChip, color = if (prepChip == "ready") Ink.green else Ink.amber)
                Text("›", fontSize = 24.sp, color = Ink.dim)
            }
            Text(prepLine, style = MaterialTheme.typography.bodySmall)
        }
        val rows = paymentRows(pending, received, m.queue, amounts, c.now()).filter { it.direction != Direction.RELAY }
        DarkCard(Modifier.testTag("nav_payments"), onClick = { go(Screen.Payments) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Payments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val waiting = rows.count { it.status == PayStatus.PENDING || it.status == PayStatus.RELAYED }
                val lost = rows.count { it.status == PayStatus.CONFLICTED || it.status == PayStatus.REJECTED }
                if (waiting > 0) Chip("$waiting unsettled", color = Ink.amber)
                if (lost > 0) Chip("$lost conflicted", color = Ink.red)
                Text("›", fontSize = 24.sp, color = Ink.dim)
            }
            if (rows.isEmpty()) Text("Nothing yet. Payments you send or receive show up here.", style = MaterialTheme.typography.bodySmall)
            rows.take(2).forEach { PaymentLine(it, c.now()) }
        }
        MeshCard(c)
        if (caps.enabled) Text("Offline limits on: ${zec(caps.perPayment)} ZEC per payment", style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun BalanceCard(c: AppController, sync: SyncInfo, split: BalanceSplit, received: List<dev.offlinezec.wallet.wallet.Received>) {
    DarkCard(padding = 22.dp) {
        Text("Balance", style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
        Text(zecText(split.total, big = true), style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("balance_total"))
        val offline = sync.networkHeight == null || sync.status == "DISCONNECTED"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (offline) Ink.amber else Ink.green))
            Text(syncText(sync), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync_status"))
        }
        // Only a real sync shows progress; an offline phone shows a plain line, never a stuck bar.
        if (sync.status == "SYNCING") LinearProgressIndicator(
            progress = { sync.progress }, modifier = Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised,
        )
        Spacer(Modifier.height(4.dp))
        ValueRow("Ready to pay offline", zec(split.available), Modifier.testTag("balance_offline"), color = Ink.green)
        if (split.locked > 0) ValueRow("Confirming or not prepared", zec(split.locked), Modifier.testTag("balance_ironwood"))
        if (split.pending > 0) ValueRow("Change on its way", zec(split.pending), Modifier.testTag("balance_pending"))
        HorizontalDivider(color = Ink.line)
        ValueRow("Total", "${zec(split.total)} ZEC", Modifier.testTag("balance_split_total"), labelColor = Ink.text)
        if (split.inPayments > 0) ValueRow("Paid offline, settling", "−${zec(split.inPayments)}", Modifier.testTag("balance_in_payments"), color = Ink.dim)
        // P4.G4: received offline but unmined is shown apart from the balance; conflicted never counts.
        val incoming = incomingUnsettled(received)
        if (incoming > 0) Text("Received offline, not yet settled: ${zec(incoming)}", style = MaterialTheme.typography.bodyMedium,
            color = Ink.amber, modifier = Modifier.testTag("balance_incoming"))
        val lost = received.filter { it.conflicted }
        if (lost.isNotEmpty()) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.redCard).padding(12.dp)) {
            Text(
                "Conflicted, not counted: ${zec(lost.sumOf { it.amount })} (${lost.size} double spend${if (lost.size == 1) "" else "s"})",
                color = Ink.red, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("balance_conflicted"),
            )
        }
    }
}

/** One payment, compact: arrow disc, title and short txid, amount and status chip. */
@Composable
fun PaymentLine(r: PaymentRow, now: Long, modifier: Modifier = Modifier, labelTag: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (glyph, title) = when (r.direction) {
            Direction.SENT -> "↑" to "Sent"
            Direction.RECEIVED -> "↓" to "Received"
            Direction.RELAY -> "⇄" to "Relaying"
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(r.status.color().copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = r.status.color(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title + if (r.direction == Direction.RELAY) " · ${r.hops} hop${if (r.hops == 1) "" else "s"}" else "", style = MaterialTheme.typography.titleMedium)
            Text(shortTxid(r.txid), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
            Text(ago(r.since, now), style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag("age_${r.txid.take(16)}"))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                r.amount?.let { (if (r.direction == Direction.SENT) "−" else "+") + zec(it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = if (r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED) Ink.red else Ink.text,
            )
            StatusChip(r.status, Modifier.testTag("status_${r.txid.take(16)}"))
        }
    }
}

@Composable
fun MeshCard(c: AppController) {
    val m by c.meshInfo.collectAsState()
    val unsettled = m.queue.count { it.unsettled }
    val on = m.permitted && m.bluetooth && m.running
    val status = when {
        !m.permitted -> "Bluetooth permission needed to relay payments"
        !m.bluetooth -> "Bluetooth is off: not relaying"
        !m.running -> "Bluetooth relay starting…"
        else -> "Bluetooth relay on · ${m.peers} phone${if (m.peers == 1) "" else "s"} nearby"
    }
    DarkCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bluetooth relay", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Chip(if (on) "on" else "off", color = if (on) Ink.green else Ink.dim)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("mesh_status"))
        Text(
            "Carrying ${m.queue.size} payment${if (m.queue.size == 1) "" else "s"} ($unsettled not yet settled) · " +
                if (m.online) "online: broadcasting" else "offline",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mesh_queue"),
        )
    }
}

/** P4.G2: two payments spend the same note. Stays up; it is not something to tap away. */
@Composable
fun ConflictBanner(c: AppController) {
    val m by c.meshInfo.collectAsState()
    if (m.conflicts.isEmpty()) return
    val ours = m.queue.filter { it.mine || it.forUs }.associateBy { it.txid }
    DarkCard(Modifier.testTag("conflict_alert"), color = Ink.redCard) {
        Text(
            "⚠ Double spend detected" + if (m.conflicts.size > 1) " (${m.conflicts.size})" else "",
            style = MaterialTheme.typography.titleMedium, color = Ink.red,
        )
        m.conflicts.sortedByDescending { it.detectedAt }.take(3).forEach { k ->
            val involved = k.txids.mapNotNull { ours[it] }
            val text = buildString {
                append(k.txids.joinToString(" and ") { it.take(10) + "…" })
                append(" spend the same note; at most one can ever settle.")
                when {
                    involved.any { it.forUs } -> append(" One is a payment TO YOU: don't hand over goods until it settles.")
                    involved.any { it.mine } -> append(" One is your own payment: your seed may be in use on another phone.")
                }
                k.settled?.let { append(" Mined: ${it.take(10)}….") }
                if (!k.verified) append(" (Partly reported by nearby phones.)")
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun PrepareScreen(c: AppController, back: () -> Unit) {
    val prep by c.prepare.collectAsState()
    val busy by c.prepareBusy.collectAsState()
    val sync by c.wallet.sync.collectAsState()
    val msg by c.message.collectAsState()
    val waiting = prep is PrepareStatus.Waiting
    // The split spends everything, so every note must be spendable by the SDK's rules first.
    val allSpendable = sync.ironwoodAvailable > 0 && sync.ironwoodAvailable == sync.ironwoodTotal
    Page {
        SheetHeader("Prepare for offline", back)
        Text("Splits your balance into cash-like notes (two each of 0.01 ZEC upward, plus small fee notes) so you can pay exact amounts with no internet. Needs a connection; takes a few minutes to confirm.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
        DarkCard {
            when (val p = prep) {
                is PrepareStatus.Waiting -> {
                    Text("Waiting for the split to be mined…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("prepare_state"))
                    Text(shortTxid(p.txid), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
                }
                is PrepareStatus.Idle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (p.ready) "Ready for offline" else if (p.notes.isEmpty()) "No spendable notes yet" else "Confirming notes…",
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("prepare_state"))
                        if (p.ready) Chip("ready", color = Ink.green)
                    }
                    if (p.notes.isNotEmpty()) {
                        Text("${zec(p.notes.sumOf { it.value })} ZEC in ${p.notes.size} notes", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(color = Ink.line)
                    }
                    // One row per note size: "0.01 ZEC × 2  ✓" (✓ = spendable offline).
                    p.notes.groupBy { it.value }.toSortedMap(compareByDescending { it }).forEach { (value, notes) ->
                        val conf = notes.minOf { n ->
                            if (n.minedHeight != null && p.tip != null) (p.tip - n.minedHeight + 1).coerceAtLeast(0) else 0
                        }
                        val ok = conf >= SPEND_CONFIRMATIONS
                        Text("${zec(value)} ZEC × ${notes.size}   ${if (ok) "✓" else "$conf/$SPEND_CONFIRMATIONS"}",
                            fontFamily = FontFamily.Monospace, color = if (ok) Ink.text else Ink.amber,
                            modifier = Modifier.padding(vertical = 2.dp).testTag("denomination"))
                    }
                }
                null -> Text("…")
            }
        }
        val hint = when {
            !sync.atTip -> "Needs a connection: wait for sync to finish first."
            !waiting && !allSpendable -> "Waiting for recent funds to confirm (${zec(sync.ironwoodAvailable)} of ${zec(sync.ironwoodTotal)} spendable)."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("prepare_hint")) }
        PrimaryButton(
            if (busy) "Sending…" else if (waiting) "Waiting for the split" else "Prepare for offline",
            { c.prepareForOffline() }, Modifier.testTag("prepare_button"),
            enabled = !busy && !waiting && sync.atTip && allSpendable,
        )
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** ZEC decimal text to zatoshis, or null if invalid. */
fun parseZec(s: String): Long? = try {
    BigDecimal(s.trim()).movePointRight(8).longValueExact().takeIf { it > 0 }
} catch (_: Exception) {
    null
}

@Composable
fun PayScreen(c: AppController, prepare: () -> Unit, back: () -> Unit) {
    val pay by c.pay.collectAsState()
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var withMemo by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val notes by c.notes.collectAsState()
    val sync by c.wallet.sync.collectAsState()
    val caps by c.capsState.collectAsState()
    val pending by c.pendingSent.collectAsState()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    if (pay is PayState.Ready) {
        PaymentQr(c, pay as PayState.Ready, back)
        return
    }
    val ready = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight).available
    Page(gap = 10.dp) {
        SheetHeader("Pay", back, compact = true)
        // Amount first: big, typed on the keypad below.
        DarkCard(padding = 14.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.clip(Pill).background(Ink.raised).padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { ZecLogo(20.dp); Text("ZEC", style = MaterialTheme.typography.labelMedium) }
                Text(
                    amount.ifEmpty { "0" }, style = MaterialTheme.typography.displayLarge, maxLines = 1,
                    color = if (amount.isEmpty()) Ink.faint else Ink.text, modifier = Modifier.testTag("pay_amount"),
                )
                Text("${zec(ready)} ZEC ready to pay offline", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (scanning) {
            DarkCard(padding = 12.dp) {
                CameraScanner(260) { text ->
                    val addr = text.removePrefix("zcash:").substringBefore('?')
                    if (addr.startsWith("utest") || addr.startsWith("u1")) {
                        to = addr
                        scanning = false
                    }
                }
                SecondaryButton("Cancel scan", { scanning = false })
            }
        } else {
            // Recipient: one pill row; type, paste or scan.
            Row(
                Modifier.fillMaxWidth().clip(Pill).background(Ink.card).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (to.isEmpty()) Text("Receiver's address", color = Ink.dim, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        to, { to = it.trim() }, singleLine = true, cursorBrush = SolidColor(Ink.text),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.text, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().testTag("pay_to"),
                    )
                }
                if (to.isEmpty()) Chip("Paste", onClick = { clipboard.getText()?.text?.trim()?.let { to = it } })
                else Chip("Clear", onClick = { to = "" })
                Chip("Scan", Modifier.testTag("pay_scan_address"), onClick = { scanning = true })
            }
            if (withMemo) {
                OutlinedTextField(memo, { memo = it }, label = { Text("Memo (optional)") }, shape = RoundedCornerShape(20.dp),
                    colors = fieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pay_memo"))
            } else {
                Chip("+ Add a memo (optional)", Modifier.testTag("pay_add_memo"), onClick = { withMemo = true })
            }
            Keypad({ k -> amount = keypadInput(amount, k) })
        }
        val zats = parseZec(amount)
        if (caps.enabled) {
            Text(
                "Offline limits on: up to ${zec(caps.perPayment)} ZEC per payment; " +
                    "${zec(Limits.unsettled(pending))} of ${zec(caps.perSession)} ZEC used until your payments settle.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pay_limits"),
            )
        }
        val building = pay is PayState.Building
        // The button says why it can't pay yet.
        val why = when {
            building -> "Signing… (no internet needed)"
            zats == null -> "Enter an amount"
            to.isBlank() -> "Add the receiver's address"
            ready == 0L -> "Nothing ready offline: prepare first"
            zats > ready -> "Only ${zec(ready)} ZEC is ready offline"
            else -> null
        }
        PrimaryButton(why ?: "Pay ${zec(zats!!)} ZEC offline", { c.payOffline(to, zats!!, memo.ifBlank { null }) },
            Modifier.testTag("pay_go"), enabled = why == null)
        if (!building && ready == 0L) SecondaryButton("Prepare for offline", prepare)
        if (building) LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
        if (pay is PayState.Failed) Text((pay as PayState.Failed).error, color = Ink.red, modifier = Modifier.testTag("pay_error"))
        Spacer(Modifier.height(8.dp))
    }
}

/** The signed, stored payment as an animated QR. Shown only after write-before-send. */
@Composable
fun PaymentQr(c: AppController, p: PayState.Ready, back: () -> Unit) {
    val encoder = remember(p.payment.txid) { FrameEncoder(p.payment.raw) }
    var frame by remember(p.payment.txid) { mutableStateOf(encoder.next()) }
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.payment.txid) {
        c.onPaymentQrShown(p.payment)
        while (true) {
            delay(FRAME_MS)
            frame = encoder.next()
            shown++
        }
    }
    Page {
        SheetHeader("Show the receiver", { c.clearPay(); back() })
        DarkCard(padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(zecText(p.payment.amount, big = true), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("pay_summary"))
                Text("fee ${zec(p.payment.fee)} ZEC", style = MaterialTheme.typography.bodySmall)
                QrTile(frame, 320.dp, tag = "payment_qr")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(PayStatus.PENDING)
                    Chip("frame ${shown + 1} · ${encoder.seqLen} parts")
                }
            }
        }
        Text("Signed and saved; your notes are spent. It also goes out over Bluetooth to nearby phones, and whichever gets online first broadcasts it.",
            style = MaterialTheme.typography.bodySmall)
        Text(shortTxid(p.payment.txid), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pay_txid"))
        PrimaryButton("Done", { c.clearPay(); back() }, Modifier.testTag("pay_done"))
        Spacer(Modifier.height(8.dp))
    }
}

private const val FRAME_MS = 200L

@Composable
fun ReceiveScreen(c: AppController, back: () -> Unit) {
    val w = c.wallet.wallet ?: return
    val st by c.receive.collectAsState()
    var scan by rememberSaveable { mutableStateOf(false) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Page {
        SheetHeader("Receive", back)
        when (val s = st) {
            is ReceiveState.Scanning -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("1  Your address", Modifier.testTag("recv_tab_address"), selected = !scan, onClick = { scan = false })
                    Chip("2  Scan the payment", Modifier.testTag("recv_tab_scan"), selected = scan, onClick = { scan = true })
                }
                if (!scan) {
                    DarkCard(padding = 20.dp) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(shortAddress(w.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.clip(Pill).background(Ink.raised).padding(horizontal = 18.dp, vertical = 10.dp).testTag("address"))
                            QrTile(w.address, 280.dp, logo = true)
                            Text("Let the payer scan your address", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PrimaryButton(if (copied) "Copied" else "Copy address", {
                        clipboard.setText(AnnotatedString(w.address)); copied = true
                    }, Modifier.testTag("copy_address"))
                    SecondaryButton("Next: scan the payer's QR", { scan = true })
                } else {
                    DarkCard(padding = 12.dp) {
                        CameraScanner(300) { c.onFrame(it) }
                        LinearProgressIndicator(progress = { s.progress.toFloat() }, color = Ink.text, trackColor = Ink.raised,
                            modifier = Modifier.fillMaxWidth().clip(Pill).testTag("receive_progress"))
                        Text(if (s.progress > 0) "Reading… ${(s.progress * 100).toInt()}%" else "Point the camera at the payer's moving QR code. Missed frames fill in by themselves.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ReceiveState.Verifying -> DarkCard {
                Text("Checking the payment…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("receive_state"))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
            }
            is ReceiveState.Accepted -> {
                DarkCard(padding = 24.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("You received", style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
                        Text("${zec(s.payment.amount)} ZEC", style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("received_amount"))
                        StatusChip(PayStatus.PENDING)
                        Text(receivedLabel(s.payment), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("received_status"))
                        s.payment.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                s.warning?.let {
                    DarkCard(Modifier.testTag("receive_warning"), color = Ink.redCard, padding = 16.dp) {
                        Text("⚠ $it", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Text("Proofs and signatures verified offline. It settles once either phone broadcasts it.", style = MaterialTheme.typography.bodySmall)
                PrimaryButton("Receive another", { c.resetReceive() })
            }
            is ReceiveState.Rejected -> {
                DarkCard(color = Ink.redCard) {
                    Text("Not accepted", style = MaterialTheme.typography.titleLarge, color = Ink.red, modifier = Modifier.testTag("receive_state"))
                    Text(s.reason, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("receive_reason"))
                }
                PrimaryButton("Scan again", { c.resetReceive() })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** P4.G3: optional caps on paying offline, and a warning on large incoming payments. Off by default. */
@Composable
fun LimitsScreen(c: AppController, back: () -> Unit) {
    val caps by c.capsState.collectAsState()
    var on by rememberSaveable { mutableStateOf(caps.enabled) }
    var perPayment by rememberSaveable { mutableStateOf(zec(caps.perPayment)) }
    var perSession by rememberSaveable { mutableStateOf(zec(caps.perSession)) }
    var saved by remember { mutableStateOf(false) }
    Page {
        SheetHeader("Offline limits", back)
        Text("An offline payment is final only once it settles. Limits cap how much rides on unsettled payments: " +
            "this phone won't sign above them, and warns when it receives more than the per-payment limit.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
        DarkCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use limits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(on, { on = it; saved = false }, Modifier.testTag("limits_on"),
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.text, checkedThumbColor = Color.Black,
                        uncheckedTrackColor = Ink.raised, uncheckedThumbColor = Ink.dim, uncheckedBorderColor = Ink.line))
            }
            OutlinedTextField(perPayment, { perPayment = it; saved = false }, label = { Text("Per payment (ZEC)") }, shape = RoundedCornerShape(18.dp),
                colors = fieldColors(), enabled = on, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("limits_per_payment"))
            OutlinedTextField(perSession, { perSession = it; saved = false }, label = { Text("Unsettled total (ZEC)") }, shape = RoundedCornerShape(18.dp),
                supportingText = { Text("Paid offline and not yet mined. Frees up as your payments settle.") },
                colors = fieldColors(), enabled = on, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("limits_per_session"))
        }
        val p = parseZec(perPayment)
        val s = parseZec(perSession)
        PrimaryButton(
            if (saved) "Saved" else if (on && (p == null || s == null)) "Enter both limits" else "Save",
            { c.setCaps(Caps(on, p ?: caps.perPayment, s ?: caps.perSession)); saved = true },
            Modifier.testTag("limits_save"), enabled = !on || (p != null && s != null),
        )
    }
}

enum class PaymentFilter(val title: String) { ALL("All"), SENT("Sent"), RECEIVED("Received"), RELAY("Relaying") }

@Composable
fun PaymentsScreen(c: AppController, showQr: () -> Unit, back: () -> Unit) {
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    val m by c.meshInfo.collectAsState()
    val amounts by c.sentAmounts.collectAsState()
    var filter by rememberSaveable { mutableStateOf(PaymentFilter.ALL) }
    val now = c.now()
    val rows = paymentRows(pending, received, m.queue, amounts, now)
    Page {
        SheetHeader("Payments", back)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentFilter.entries.forEach { f ->
                Chip(f.title, Modifier.testTag("filter_${f.name.lowercase()}"), selected = filter == f, onClick = { filter = f })
            }
        }
        val shown = rows.filter {
            when (filter) {
                PaymentFilter.ALL -> true
                PaymentFilter.SENT -> it.direction == Direction.SENT
                PaymentFilter.RECEIVED -> it.direction == Direction.RECEIVED
                PaymentFilter.RELAY -> it.direction == Direction.RELAY
            }
        }
        if (shown.isEmpty()) DarkCard { Text("No payments here yet.", color = Ink.dim) }
        shown.forEach { r ->
            val key = r.txid.take(16)
            val tag = when (r.direction) { Direction.SENT -> "sent_$key"; Direction.RECEIVED -> "received_$key"; Direction.RELAY -> "relay_$key" }
            val bad = r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED
            DarkCard(Modifier.testTag(tag), color = if (bad) Ink.redCard else Ink.card, padding = 16.dp) {
                PaymentLine(r, now)
                Text(
                    r.label, style = MaterialTheme.typography.bodySmall, color = if (bad) Ink.red else Ink.dim,
                    modifier = if (r.direction == Direction.RECEIVED) Modifier.testTag("received_label_$key") else Modifier,
                )
                r.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
                if (r.canShowQr) {
                    SecondaryButton("Show QR to the receiver again", { c.showPending(r.txid); showQr() }, Modifier.height(48.dp).testTag("pending_show_$key"))
                }
            }
        }
        // Debug builds: push everything to the network now instead of waiting for the next try.
        if (BuildConfig.DEBUG) SecondaryButton("Broadcast now (debug)", { c.broadcastNow() }, Modifier.testTag("broadcast_now"))
        val msg by c.message.collectAsState()
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        Spacer(Modifier.height(8.dp))
    }
}

/** Camera preview that reports every decoded QR text. Asks for the camera permission. */
@Composable
fun CameraScanner(heightDp: Int, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Text("Camera permission is needed to scan.", color = Ink.dim)
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        // COMPATIBLE = a TextureView: it clips to the rounded card and stays under the header,
        // where the default SurfaceView drew over the Back control.
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).clip(RoundedCornerShape(20.dp)).testTag("camera"),
        factory = { context ->
            val view = PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(executor, QrAnalyzer(onText = onText)) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
            view
        },
    )
}
