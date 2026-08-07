package com.example.myapplication3.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.myapplication3.ui.theme.CautionAmber
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.viewmodel.SmartApiViewModel

/**
 * "Live data (Angel One)" settings card (SmartAPI Stage 1). The user pastes the
 * four values from their own SmartAPI setup; they are stored only on this phone.
 * "Test" does a real login so they know the keys work before relying on live data.
 */
@Composable
fun SmartApiSettingsCard(viewModel: SmartApiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Removing keys stops live prices — always confirm before doing it.
    var showRemoveConfirm by remember { mutableStateOf(false) }
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove your Angel One keys?") },
            text = { Text("Live prices will stop. The app goes back to free (slightly delayed) data. You can add the keys again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    viewModel.clear()
                }) { Text("Yes, remove", color = RedPrimary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) { /* no browser installed — ignore */ }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Live data (Angel One)", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Real-time prices via your own Angel One SmartAPI. Stored only on this phone. Leave blank to keep using free (slightly delayed) data.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // B0.4: the one honest line that settles what this login can do —
            // the app has read-only use for it, full stop.
            Text(
                "This login only READS live prices - the app can never place, change, or cancel orders.",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── First-time helper: open the exact pages so the user need not type URLs.
            // Only the user can log in (2FA protects the account) — we just take them there.
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Text("First time? Get your 4 values (about 10 minutes, one time):",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text("• Client code = your Angel One login ID.  • MPIN = the PIN you open Angel One with.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedButton(
                onClick = { openUrl("https://smartapi.angelone.in") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("1. Get API Key — open website") }
            Text("Log in → Create an App → pick \"Trading APIs\" → copy the API Key.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedButton(
                onClick = { openUrl("https://smartapi.angelbroking.com/enable-totp") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("2. Get TOTP secret — open website") }
            Text("Enter your client code → scan the QR → also copy the secret text.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Only you can log in — never share these with anyone (not even support).",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            // Every secret is hidden the same way, with an eye to check what you
            // typed before testing — no field is left readable to shoulder-surfers.
            SecretField(
                value = state.apiKey, onValueChange = viewModel::setApiKey,
                label = "API Key"
            )
            OutlinedTextField(
                value = state.clientCode, onValueChange = viewModel::setClientCode,
                label = { Text("Client code (Angel login ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            SecretField(
                value = state.mpin, onValueChange = viewModel::setMpin,
                label = "MPIN", numeric = true
            )
            SecretField(
                value = state.totpSecret, onValueChange = viewModel::setTotpSecret,
                label = "TOTP secret"
            )

            // Save + Test share one row; Remove gets its own full-width row below —
            // three buttons in one row squeezed "Remove" into a vertical letter stack.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.save() }, enabled = !state.testing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("Save", maxLines = 1) }
                Button(
                    onClick = { viewModel.test() }, enabled = !state.testing,
                    modifier = Modifier.weight(1.5f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("Test connection", maxLines = 1) }
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (state.configured) {
                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, RedPrimary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedPrimary)
                ) { Text("Remove keys", maxLines = 1) }
            }
            Text("Test only checks the keys — nothing is saved until you tap Save.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            state.testResult?.let { msg ->
                val ok = msg.startsWith("✓") || msg.startsWith("Saved")
                Text(msg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (ok) GreenPrimary else RedPrimary)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = CautionAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "These keys can also place trades. Keep them private.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Masked text field with a show/hide eye — used for every trade-capable secret. */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    numeric: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                          else KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
