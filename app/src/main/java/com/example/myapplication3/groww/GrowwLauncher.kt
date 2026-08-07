package com.example.myapplication3.groww

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Which order type to choose inside Groww. Shown big and unmissable on every
 * card (requirement B0.2a) so a zero-knowledge user never picks the wrong one.
 */
enum class OrderType(val label: String) {
    INTRADAY("INTRADAY — sell today"),
    DELIVERY("DELIVERY — hold a few days")
}

/**
 * Opens the Groww broker app on a stock so the user can place the order themselves
 * (requirement B0.2). The app never places or touches the order — advice only (B0.4).
 *
 * Best-effort chain, most-specific first:
 *   1. Groww app, on a groww.in search URL scoped to Groww's package (lands the user
 *      on the stock's search result — one tap to the stock page).
 *   2. Groww app's launcher (if it did not handle the link) + a toast to search.
 *   3. Groww Play Store page (app not installed).
 *   4. The groww.in URL in a browser (last resort).
 *
 * Note: Groww exposes no public, stable deep link to a specific stock PAGE by NSE
 * symbol, so we honestly use its search — exactly the fallback the requirement allows.
 */
object GrowwLauncher {

    private const val TAG = "GrowwLauncher"
    const val GROWW_PACKAGE = "com.nextbillion.groww"

    fun openStock(context: Context, symbol: String) {
        val clean = symbol.trim().removeSuffix(".NS").uppercase()
        val searchUrl = "https://groww.in/search?q=" + Uri.encode(clean)

        // 1) Groww app, on the search URL (app-link scoped to Groww's package)
        val inGroww = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
            setPackage(GROWW_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(context, inGroww)) return

        // 2) Groww is installed but didn't claim the link — open its launcher + hint
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(GROWW_PACKAGE) }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, launch)) {
                Toast.makeText(context, "Search '$clean' in Groww", Toast.LENGTH_LONG).show()
                return
            }
        }

        // 3) Groww not installed — take the user to its Play Store page
        val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GROWW_PACKAGE"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (start(context, play)) {
            Toast.makeText(context, "Install Groww first", Toast.LENGTH_LONG).show()
            return
        }
        val playWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GROWW_PACKAGE"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (start(context, playWeb)) return

        // 4) Last resort — the groww.in search page in a browser
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (!start(context, web)) {
            Toast.makeText(context, "Could not open Groww", Toast.LENGTH_LONG).show()
        }
    }

    /** True if Groww is installed — lets the UI tailor its wording. */
    fun isGrowwInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(GROWW_PACKAGE) != null }.getOrDefault(false)

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "start failed: ${e.message}")
        false
    }
}
