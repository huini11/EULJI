package com.hanscreen.translator.core

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

object Screen {

    fun size(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = context.resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }
    }
}
