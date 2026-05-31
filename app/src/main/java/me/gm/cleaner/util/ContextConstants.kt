package me.gm.cleaner.util

import android.content.Context
import android.content.res.ColorStateList
import androidx.annotation.ColorInt

val Context.colorPrimary: Int
    @ColorInt
    get() = getColorByAttr(android.R.attr.colorPrimary)!!

val Context.colorAccent: Int
    @ColorInt
    get() = getColorByAttr(android.R.attr.colorAccent)!!

val Context.colorSurface: Int
    @ColorInt
    get() = getColorByAttr(com.google.android.material.R.attr.colorSurface)!!

val Context.colorError: Int
    @ColorInt
    get() = getColorByAttr(com.google.android.material.R.attr.colorError)!!

val Context.colorControlHighlight: Int
    @ColorInt
    get() = getColorByAttr(android.R.attr.colorControlHighlight)!!

val Context.textColorPrimary: ColorStateList
    get() = getColorStateListByAttr(android.R.attr.textColorPrimary)!!

val Context.shortAnimTime: Long
    get() = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
