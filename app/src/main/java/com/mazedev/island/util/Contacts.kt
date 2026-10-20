package com.mazedev.island.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class Contact(val id: Long, val name: String, val starred: Boolean, val photo: Uri?)

object Contacts {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canCallDirectly(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Contacts with a phone number: favorites first, then A–Z. Call from a background thread. */
    fun load(context: Context): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Contact>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    val name = c.getString(1) ?: continue
                    out += Contact(
                        id = c.getLong(0),
                        name = name,
                        starred = c.getInt(2) == 1,
                        photo = c.getString(3)?.let(Uri::parse),
                    )
                }
            }
        }
        return out
    }

    /** The contact's main number (or the first one). */
    fun number(context: Context, contactId: Long): String? = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun photo(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    fun callIntent(context: Context, number: String): Intent =
        Intent(if (canCallDirectly(context)) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun textIntent(number: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
