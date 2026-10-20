package dev.offlinezec.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.offlinezec.wallet.App
import dev.offlinezec.wallet.AppController
import dev.offlinezec.wallet.PayState
import dev.offlinezec.wallet.ReceiveState
import dev.offlinezec.wallet.qr.FrameEncoder
import dev.offlinezec.wallet.qr.QrAnalyzer
import dev.offlinezec.wallet.qr.qrBitmap
import dev.offlinezec.wallet.receivedLabel
import dev.offlinezec.wallet.wallet.PrepareStatus
import dev.offlinezec.wallet.wallet.SPEND_CONFIRMATIONS
import dev.offlinezec.wallet.wallet.WalletState
import dev.offlinezec.wallet.zec
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.util.concurrent.Executors

enum class Screen { Home, Prepare, Pay, Receive, Payments }

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = App.controller(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
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
}

@Composable
fun Centered(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("status_text"))
    }
}

@Composable
fun SetupScreen(c: AppController) {
    var restoring by remember { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf("") }
    val msg by c.message.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline ZEC wallet", style = MaterialTheme.typography.headlineMedium)
        Text("Testnet. Pays shielded Zcash with no internet; any phone broadcasts it later.")
        if (!restoring) {
            Button(onClick = { c.createWallet() }, Modifier.fillMaxWidth().testTag("create_wallet")) { Text("Create a new wallet") }
            OutlinedButton(onClick = { restoring = true }, Modifier.fillMaxWidth().testTag("restore_wallet")) { Text("Restore from seed phrase") }
        } else {
            OutlinedTextField(words, { words = it }, label = { Text("24-word seed phrase") }, modifier = Modifier.fillMaxWidth().testTag("restore_words"))
            OutlinedTextField(birthday, { birthday = it.filter(Char::isDigit) }, label = { Text("Birthday height") }, modifier = Modifier.fillMaxWidth().testTag("restore_birthday"))
            Button(
                onClick = { c.restoreWallet(words, birthday.toLongOrNull() ?: 0) },
                enabled = words.isNotBlank() && birthday.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("restore_go"),
            ) { Text("Restore") }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun WalletScreens(c: AppController) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> HomeScreen(c) { screen = it }
        Screen.Prepare -> PrepareScreen(c) { screen = Screen.Home }
        Screen.Pay -> PayScreen(c) { screen = Screen.Home }
        Screen.Receive -> ReceiveScreen(c) { screen = Screen.Home }
        Screen.Payments -> PaymentsScreen(c) { screen = Screen.Home }
    }
}

@Composable
fun HomeScreen(c: AppController, go: (Screen) -> Unit) {
    val w = c.wallet.wallet ?: return
    val sync by c.wallet.sync.collectAsState()
    val prep by c.prepare.collectAsState()
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    val msg by c.message.collectAsState()
    val offlineAvailable = (prep as? PrepareStatus.Idle)?.notes?.sumOf { it.value }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline ZEC · testnet", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${zec(sync.shieldedTotal)} ZEC", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("balance_total"))
                Text("Ironwood spendable ${zec(sync.ironwoodAvailable)}", modifier = Modifier.testTag("balance_ironwood"))
                offlineAvailable?.let { Text("Ready for offline: ${zec(it)}", modifier = Modifier.testTag("balance_offline")) }
                val pct = (sync.progress * 100).toInt()
                Text(
                    "${sync.status.lowercase()} · $pct% · block ${sync.scannedHeight ?: "?"} of ${sync.networkHeight ?: "?"}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync_status"),
                )
                if (!sync.atTip) LinearProgressIndicator(progress = { sync.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        Button(onClick = { go(Screen.Prepare) }, Modifier.fillMaxWidth().testTag("nav_prepare")) { Text("Prepare for offline") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { c.clearPay(); go(Screen.Pay) }, Modifier.weight(1f).testTag("nav_pay")) { Text("Pay") }
            Button(onClick = { c.resetReceive(); go(Screen.Receive) }, Modifier.weight(1f).testTag("nav_receive")) { Text("Receive") }
        }
        OutlinedButton(onClick = { go(Screen.Payments) }, Modifier.fillMaxWidth().testTag("nav_payments")) {
            Text("Payments (${pending.size} to send, ${received.count { !it.settled }} unsettled)")
        }
        OutlinedButton(onClick = { c.broadcastNow() }, Modifier.fillMaxWidth().testTag("broadcast_now")) { Text("Broadcast now") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        Text("Your address", style = MaterialTheme.typography.titleSmall)
        QrImage(w.address, 260)
        Text(w.address, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.testTag("address"))
    }
}

@Composable
fun QrImage(text: String, sizeDp: Int, tag: String = "qr") {
    val bmp = remember(text) { qrBitmap(text, 720) }
    Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(sizeDp.dp).testTag(tag))
}

@Composable
fun PrepareScreen(c: AppController, back: () -> Unit) {
    val prep by c.prepare.collectAsState()
    val busy by c.prepareBusy.collectAsState()
    val sync by c.wallet.sync.collectAsState()
    val msg by c.message.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Prepare for offline", style = MaterialTheme.typography.titleLarge)
        Text("Splits your balance into cash-like notes (two each of 0.01 ZEC upward, plus small fee notes) so you can pay exact amounts with no internet. Needs a connection; takes a few minutes to confirm.")
        val waiting = prep is PrepareStatus.Waiting
        // The split spends everything, so every note must be spendable by the SDK's rules first.
        val allSpendable = sync.ironwoodAvailable > 0 && sync.ironwoodAvailable == sync.ironwoodTotal
        Button(
            onClick = { c.prepareForOffline() },
            enabled = !busy && !waiting && sync.atTip && allSpendable,
            modifier = Modifier.fillMaxWidth().testTag("prepare_button"),
        ) { Text(if (busy) "Sending…" else "Prepare for offline") }
        when {
            !sync.atTip -> Text("Wait for sync to finish first.", style = MaterialTheme.typography.bodySmall)
            !waiting && !allSpendable -> Text(
                "Waiting for recent funds to confirm (${zec(sync.ironwoodAvailable)} of ${zec(sync.ironwoodTotal)} spendable).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("prepare_hint"),
            )
        }
        when (val p = prep) {
            is PrepareStatus.Waiting -> {
                Text("Waiting for the split to be mined…", modifier = Modifier.testTag("prepare_state"))
                Text(p.txid, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is PrepareStatus.Idle -> {
                Text(if (p.ready) "Ready for offline" else if (p.notes.isEmpty()) "No spendable notes yet" else "Confirming notes…",
                    modifier = Modifier.testTag("prepare_state"), fontWeight = FontWeight.Bold)
                // One row per note size: "0.01 ZEC × 2  ✓" (✓ = spendable offline).
                p.notes.groupBy { it.value }.toSortedMap(compareByDescending { it }).forEach { (value, notes) ->
                    val conf = notes.minOf { n ->
                        if (n.minedHeight != null && p.tip != null) (p.tip - n.minedHeight + 1).coerceAtLeast(0) else 0
                    }
                    Text("${zec(value)} ZEC × ${notes.size}   ${if (conf >= SPEND_CONFIRMATIONS) "✓" else "$conf/$SPEND_CONFIRMATIONS"}",
                        fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("denomination"))
                }
            }
            null -> Text("…")
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = back, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** ZEC decimal text to zatoshis, or null if invalid. */
fun parseZec(s: String): Long? = try {
    BigDecimal(s.trim()).movePointRight(8).longValueExact().takeIf { it > 0 }
} catch (_: Exception) {
    null
}

@Composable
fun PayScreen(c: AppController, back: () -> Unit) {
    val pay by c.pay.collectAsState()
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    when (val p = pay) {
        is PayState.Ready -> PaymentQr(c, p, back)
        else -> Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pay offline", style = MaterialTheme.typography.titleLarge)
            if (scanning) {
                CameraScanner { text ->
                    val addr = text.removePrefix("zcash:").substringBefore('?')
                    if (addr.startsWith("utest") || addr.startsWith("u1")) {
                        to = addr
                        scanning = false
                    }
                }
                OutlinedButton(onClick = { scanning = false }, Modifier.fillMaxWidth()) { Text("Cancel scan") }
            } else {
                OutlinedTextField(to, { to = it.trim() }, label = { Text("Receiver's address") }, modifier = Modifier.fillMaxWidth().testTag("pay_to"))
                OutlinedButton(onClick = { scanning = true }, Modifier.fillMaxWidth().testTag("pay_scan_address")) { Text("Scan receiver's address") }
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount (ZEC)") }, modifier = Modifier.fillMaxWidth().testTag("pay_amount"))
            OutlinedTextField(memo, { memo = it }, label = { Text("Memo (optional)") }, modifier = Modifier.fillMaxWidth().testTag("pay_memo"))
            val zats = parseZec(amount)
            Button(
                onClick = { c.payOffline(to, zats!!, memo.ifBlank { null }) },
                enabled = pay !is PayState.Building && to.isNotBlank() && zats != null,
                modifier = Modifier.fillMaxWidth().testTag("pay_go"),
            ) { Text(if (pay is PayState.Building) "Signing… (no internet needed)" else "Pay offline") }
            if (pay is PayState.Building) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (pay is PayState.Failed) Text((pay as PayState.Failed).error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pay_error"))
            OutlinedButton(onClick = back, Modifier.fillMaxWidth()) { Text("Back") }
        }
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
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Show this to the receiver", style = MaterialTheme.typography.titleLarge)
        Text("${zec(p.payment.amount)} ZEC · fee ${zec(p.payment.fee)}", modifier = Modifier.testTag("pay_summary"))
        QrImage(frame, 330, tag = "payment_qr")
        Text("frame ${shown + 1} · ${encoder.seqLen} parts", style = MaterialTheme.typography.bodySmall)
        Text("Signed and saved. Your notes are spent; this phone or the receiver's will broadcast it once online.",
            style = MaterialTheme.typography.bodySmall)
        Text(p.payment.txid, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.testTag("pay_txid"))
        Button(onClick = { c.clearPay(); back() }, Modifier.fillMaxWidth().testTag("pay_done")) { Text("Done") }
    }
}

private const val FRAME_MS = 200L

@Composable
fun ReceiveScreen(c: AppController, back: () -> Unit) {
    val w = c.wallet.wallet ?: return
    val st by c.receive.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Receive", style = MaterialTheme.typography.titleLarge)
        when (val s = st) {
            is ReceiveState.Scanning -> {
                Text("1. Let the payer scan your address", style = MaterialTheme.typography.titleSmall)
                QrImage(w.address, 200)
                Text("2. Scan the payer's moving QR code", style = MaterialTheme.typography.titleSmall)
                CameraScanner { c.onFrame(it) }
                LinearProgressIndicator(progress = { s.progress.toFloat() }, modifier = Modifier.fillMaxWidth().testTag("receive_progress"))
            }
            ReceiveState.Verifying -> Text("Checking the payment…", modifier = Modifier.testTag("receive_state"))
            is ReceiveState.Accepted -> {
                Text("${zec(s.payment.amount)} ZEC", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("received_amount"))
                Text(receivedLabel(s.payment), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("received_status"))
                s.payment.memo?.let { Text("“$it”") }
                Text("Proofs and signatures verified offline. It settles once either phone broadcasts it.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { c.resetReceive() }, Modifier.fillMaxWidth()) { Text("Receive another") }
            }
            is ReceiveState.Rejected -> {
                Text("Not accepted", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("receive_state"))
                Text(s.reason, modifier = Modifier.testTag("receive_reason"))
                Button(onClick = { c.resetReceive() }, Modifier.fillMaxWidth()) { Text("Scan again") }
            }
        }
        OutlinedButton(onClick = back, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
fun PaymentsScreen(c: AppController, back: () -> Unit) {
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Payments", style = MaterialTheme.typography.titleLarge)
        Text("Sent, waiting to reach the network", style = MaterialTheme.typography.titleSmall)
        if (pending.isEmpty()) Text("none")
        pending.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        Spacer(Modifier.height(8.dp))
        Text("Received", style = MaterialTheme.typography.titleSmall)
        if (received.isEmpty()) Text("none")
        received.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${zec(r.amount)} ZEC · ${receivedLabel(r)}", fontWeight = FontWeight.Bold)
                    r.memo?.let { Text("“$it”") }
                    Text(r.txid, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        OutlinedButton(onClick = { c.broadcastNow() }, Modifier.fillMaxWidth()) { Text("Broadcast now") }
        OutlinedButton(onClick = back, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** Camera preview that reports every decoded QR text. Asks for the camera permission. */
@Composable
fun CameraScanner(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Text("Camera permission is needed to scan.")
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(280.dp).testTag("camera"),
        factory = { context ->
            val view = PreviewView(context)
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
