package com.example.myapplication3.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication3.ui.theme.CautionAmber
import com.example.myapplication3.ui.theme.CautionContainer
import com.example.myapplication3.ui.theme.DarkSurfaceElevated
import com.example.myapplication3.ui.theme.GreenContainer
import com.example.myapplication3.ui.theme.GreenLight
import com.example.myapplication3.ui.theme.RedContainer
import com.example.myapplication3.ui.theme.RedLight
import com.example.myapplication3.ui.theme.TextPrimary
import com.example.myapplication3.ui.theme.TextSecondary
import kotlin.math.abs

/**
 * Today's running profit/loss, always at the top while there is trade activity or
 * a set budget (B7b). Hidden when there's truly nothing to show, so a brand-new
 * user sees a clean screen (rule B10) instead of a pointless "Today: ₹0".
 *
 * Practice results are ALWAYS labeled PRACTICE (B0.3b) — fake money must never
 * read like real money. [onEditBudget] shows a small "My budget: ₹X ✎" chip so a
 * mis-tapped daily budget can be corrected any time (B7b).
 */
@Composable
fun TodayPnlBar(
    realizedPnl: Double,
    openTradeCount: Int,
    practiceMode: Boolean = false,
    budget: Int = 0,
    onEditBudget: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val showBudgetChip = budget > 0 && onEditBudget != null
    if (realizedPnl == 0.0 && openTradeCount == 0 && !showBudgetChip) return

    val positive = realizedPnl >= 0.0
    // Practice = FAKE money → CAUTION amber card with a dark, readable label and an
    // amber frame, NEVER the brand green (B0.3b: fake money must not read as a real
    // gain). Real P/L uses the soft up-green / down-red card with its readable ink.
    val (surfaceColor, textColor) = when {
        practiceMode -> CautionContainer to TextPrimary
        positive     -> GreenContainer to GreenLight
        else         -> RedContainer to RedLight
    }
    val accentBorder = if (practiceMode) BorderStroke(1.dp, CautionAmber) else null
    val sign = if (positive) "+" else "−"
    // I9: TRUE Indian lakh/crore grouping — String.format with Locale("en","IN")
    // groups in threes (100,000), not the 1,00,000 users read everywhere else.
    val amount = formatIndianRupees(abs(realizedPnl), 0)
    // Panel fix (unlabeled money): every ₹ number says WHAT it is in words —
    // "profit"/"loss", never a bare "Today: +₹0" the user must decode. Practice
    // keeps its PRACTICE prefix (B0.3b) so fake money never reads like real money.
    val kind  = if (positive) "profit" else "loss"
    val label = if (practiceMode) "PRACTICE $kind today: $sign₹$amount"
                else "Today's $kind: $sign₹$amount"

    // Slim single-line header band: full-bleed colour so it reads as a header, but
    // its inner content is inset 16dp so the label lines up with the cards below.
    // Min 48dp height keeps it slim and gives the budget chip a full-height 48dp
    // tap target (B7b), while letting the worded label wrap to a second line on a
    // narrow phone WITHOUT clipping (U9.4) — nothing is ever cut off.
    Surface(
        color = surfaceColor,
        border = accentBorder,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = textColor,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleSmall,
                // Weight so the longer worded label wraps inside the band on a
                // narrow phone instead of pushing the budget chip off-screen.
                modifier = Modifier.weight(1f, fill = false)
            )
            Row(
                // NOT fillMaxHeight(): inside a heightIn(min=…) parent the max height
                // is unbounded, so fillMaxHeight would balloon the bar to full screen.
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (openTradeCount > 0) {
                    Text(
                        if (openTradeCount == 1) "1 trade open" else "$openTradeCount trades open",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showBudgetChip) {
                    // Full-height clickable column = 48dp tap target so a mis-set budget
                    // is easy to correct (B7b), while the visible chip stays slim and
                    // vertically centred. Material pencil icon (clean line-icon).
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable { onEditBudget?.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceElevated
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    // Panel fix (unlabeled money): say what this ₹ IS.
                                    // I9: shared Indian-grouping formatter.
                                    "My budget: ₹${formatIndianRupees(budget.toLong())}",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Change daily budget",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
