package org.schabi.newpipe

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/**
 * Y-SYNC personal access gate. The app unlocks once per install with an
 * access code (owner code or guest code). Only SHA-256 hashes of the codes
 * are kept here; the plain codes are never stored in the app or the repo.
 */
object AccessGate {
    private const val PREFS = "ysync_access"
    private const val KEY_UNLOCKED = "unlocked"

    private val ACCEPTED_HASHES = setOf(
        "a8e9dbe83f6377bdfa3242dd8b17861d408d2ab75d7a6ac179afef8a229ec93b",
        "8524f1fc065addadbf71933ac445e959324cafa990ddf0da274419e4adfc9393"
    )

    fun isUnlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UNLOCKED, false)

    fun tryUnlock(context: Context, code: String): Boolean {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) {
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (hex !in ACCEPTED_HASHES) {
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_UNLOCKED, true).apply()
        return true
    }
}
