package me.gm.cleaner.util

import android.content.Intent
import android.os.Parcelable
import androidx.core.content.IntentCompat

inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String?)
        : T? = IntentCompat.getParcelableExtra(this, name, T::class.java)
