package com.example.myapplication3.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication3.groww.GrowwLauncher
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.intraday.PendingOrder
import com.example.myapplication3.ui.theme.*

/** One target's exit instruction: sell [shares] at target [index] (price [price]). */
data class TargetExit(val index: Int, val price: Double, val shares: Int)

/**
 * Split [qty] shares across the available targets (B0.2c — exact counts, never "half").
 * Front-loads the remainder to earlier targets so profit is locked sooner. Only
 * targets with a real price (> 0) get a bucket; a 1-share trade sells fully at T1.
 */
fun computeTargetSplit(qty: Int, t1: Double, t2: Double, t3: Double): List<TargetExit> {
    if (qty <= 0) return emptyList()
    val prices = buildList {
        if (t1 > 0.0) add(1 to t1)
        if (t2 > 0.0) add(2 to t2)
        if (t3 > 0.0) add(3 to t3)
    }.ifEmpty { listOf(1 to t1) }

    val buckets = minOf(prices.size, qty)          // never more buckets than shares
    val base = qty / buckets
    val rem = qty % buckets
    return (0 until buckets).map { i ->
        val (idx, price) = prices[i]
        TargetExit(idx, price, base + if (i < rem) 1 else 0)
    }
}

/**
 * The action every BUY card ends with (B0.2): the big order-type label so the user
 * picks the right type in Groww, the one button that opens Groww on the stock, and
 * — the moment the user returns from Groww — the "Order placed?" confirm + fill-price
 * capture (B0.2b) that starts tracking. Honest: the app only advises (B0.4).
 *
 * Blocking (I7): when [enabled] is false the button is REALLY disabled (greyed)
 * and [disabledReason] explains why in one line — a blocked action must never be
 * a live button under an advisory banner.
 *
 * Persistence (locked decision #3): callers that survive process death via a
 * DataStore pending-order record pass [externalConfirm] = true and persist in
 * [onLaunch]; the in-composition confirm flow is then skipped entirely and the
 * screen-level [PendingOrderConfirmDialog] takes over. Legacy callers that pass
 * nothing keep the old in-composition confirm behavior unchanged.
 */
@Composable
fun GrowwActionRow(
    symbol: String,
    orderType: OrderType,
    suggestedPrice: Double,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String = "",
    externalConfirm: Boolean = false,
    onLaunch: () -> Unit = {},
    onConfirmed: (fillPrice: Double) -> Unit = {}
) {
    val context = LocalContext.current
    // Intraday must be squared off the SAME day → the riskier choice for a first-time
    // user, so it wears the CAUTION amber; plain delivery gets the calm brand green.
    // Both use a readable dark/green ink (not bright-on-tint) to stay >=4.5:1 on white.
    val isIntraday = orderType == OrderType.INTRADAY
    val typeAccent = if (isIntraday) CautionAmber else GreenPrimary
    val typeTint   = if (isIntraday) CautionContainer else GreenContainer
    val typeInk    = if (isIntraday) TextPrimary else GreenLight

    var awaitingReturn by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showFillPrice by remember { mutableStateOf(false) }

    // "The moment the user comes back from Groww, the app asks" (B0.2b) — detect the
    // return via the resume that follows launching Groww; tracking never depends on
    // the user remembering to tell the app. Skipped when the caller confirms
    // externally via the persisted pending-order flow.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingReturn) {
                awaitingReturn = false
                if (!externalConfirm) showConfirm = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Big, unmissable order-type label (B0.2a) — greyed while blocked
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (enabled) typeTint else TextMuted.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, (if (enabled) typeAccent else TextMuted).copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("In Groww, choose:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(
                    orderType.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (enabled) typeInk else TextMuted
                )
            }
        }

        Button(
            onClick = {
                onLaunch()
                GrowwLauncher.openStock(context, symbol)
                awaitingReturn = true
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldAccent,
                contentColor = TextOnGold,
                disabledContainerColor = TextMuted.copy(alpha = 0.25f),
                disabledContentColor = TextMuted
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (enabled) "Place order in Groww" else "Buying is off right now",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        if (!enabled && disabledReason.isNotEmpty()) {
            // One-line reason + what IS possible (B9/I7)
            Text(
                disabledReason,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = RedLight
            )
        }
        // Honest, per locked decision 2: the app can only guide, not sell for you.
        Text(
            "This app only guides you — you place and sell in Groww.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }

    // Step 1 on return: did the order actually go through? (B0.2b)
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Order placed?") },
            text = { Text("Did you place the $symbol order in Groww?") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; showFillPrice = true }) {
                    Text("Yes", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("No") }
            }
        )
    }

    // Step 2: capture the REAL fill price (pre-filled with the live/suggested price).
    if (showFillPrice) {
        PriceInputDialog(
            title = "Your buy price",
            prompt = "Enter the price you actually paid in Groww. Not sure? Keep the suggested price.",
            suggested = suggestedPrice,
            onConfirm = { price -> showFillPrice = false; onConfirmed(price) },
            onDismiss = { showFillPrice = false }
        )
    }
}

/**
 * Screen-level "Order placed?" flow for a DataStore-persisted [PendingOrder]
 * (B0.2b + locked decision #3). Because the pending record survives process
 * death, this dialog re-appears even if Android killed the app while the user
 * was inside Groww. Step 2 pre-fills the LIVE price and the recommended
 * quantity and lets the user adjust BOTH before tracking starts.
 */
@Composable
fun PendingOrderConfirmDialog(
    pending: PendingOrder,
    livePrice: Double? = null,
    onConfirm: (fillPrice: Double, quantity: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember(pending.symbol, pending.timestampMs) { mutableStateOf(1) }
    val suggestedPrice = livePrice?.takeIf { it > 0.0 } ?: pending.price
    val suggestedQty = pending.qty.coerceAtLeast(1)

    if (step == 1) {
        AlertDialog(
            onDismissRequest = { /* must answer — the order may be real money */ },
            title = { Text("Order placed?") },
            text = { Text("Did you place the ${pending.symbol} order in Groww?") },
            confirmButton = {
                TextButton(onClick = { step = 2 }) { Text("Yes", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("No") }
            }
        )
    } else {
        var priceText by remember { mutableStateOf(String.format("%.2f", suggestedPrice)) }
        var qtyText by remember { mutableStateOf(suggestedQty.toString()) }
        val price = priceText.toDoubleOrNull() ?: 0.0
        val qty = qtyText.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { /* keep — tracking must not be lost (B0.2b) */ },
            title = { Text("Your ${pending.symbol} order") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Check the price and how many shares you bought. Not sure? Keep the shown numbers.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { new -> priceText = new.filter { it.isDigit() || it == '.' } },
                        singleLine = true,
                        label = { Text("Buy price") },
                        leadingIcon = { Text("₹", fontWeight = FontWeight.Bold, color = TextPrimary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { new -> qtyText = new.filter { it.isDigit() } },
                        singleLine = true,
                        label = { Text("How many shares") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(price, qty) },
                    enabled = price > 0.0 && qty > 0
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("I didn't buy") }
            }
        )
    }
}

/**
 * Asks the user their actual buy/sell price. Pre-filled with the live/suggested
 * price so a low-literacy user can accept it in one tap, or adjust it. Reused for
 * both the buy fill-price (locked decision 3) and the sell-close price.
 */
@Composable
fun PriceInputDialog(title: String, prompt: String, suggested: Double, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (suggested > 0) String.format("%.2f", suggested) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(prompt, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' } },
                    singleLine = true,
                    leadingIcon = { Text("₹", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text.toDoubleOrNull()?.let { if (it > 0) onConfirm(it) } },
                enabled = (text.toDoubleOrNull() ?: 0.0) > 0.0
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
