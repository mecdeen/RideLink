package com.aiyu.ridelink.core.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup

/**
 * Turns a caller's number into the name saved in Contacts, so the display shows "Amma" instead of digits.
 * Used where only a number is known: the phone-state backup, a dialer notification titled with the number,
 * and the missed-call pop-up when the call log has no cached name. Needs READ_CONTACTS (the optional phone card).
 */
object ContactNames {
    private val NUMBER = Regex("""^\+?[\d\s\-().]{5,}$""")

    /** True when [text] is a phone number rather than a name. */
    fun isNumber(text: String) = NUMBER.matches(text.trim())

    /** The contact name for [number]; null without READ_CONTACTS or a saved contact. */
    fun lookup(context: Context, number: String): String? {
        if (number.isBlank() || context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim()))
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** [text] itself when it is already a name, else the contact name for that number, else the number. */
    fun nameOf(context: Context, text: String): String = if (isNumber(text)) lookup(context, text) ?: text else text
}
