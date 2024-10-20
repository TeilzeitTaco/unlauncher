package com.sduduzog.slimlauncher.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.util.DisplayMetrics
import android.view.WindowInsets
import androidx.annotation.StringRes
import com.jkuester.unlauncher.datastore.AlignmentFormat
import com.sduduzog.slimlauncher.R
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.random.Random

private fun isAppDefaultLauncher(context: Context?): Boolean {
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_HOME)
    val res = context?.packageManager?.resolveActivity(intent, 0)
    if (res?.activityInfo == null) {
        // should not happen. A home is always installed, isn't it?
        return false
    }
    return context.packageName == res.activityInfo?.packageName
}

private fun intentContainsDefaultLauncher(intent: Intent?): Boolean =
    intent?.action == Intent.ACTION_MAIN &&
        intent.categories?.contains(Intent.CATEGORY_HOME) == true

fun isActivityDefaultLauncher(activity: Activity?): Boolean =
    isAppDefaultLauncher(activity) || intentContainsDefaultLauncher(activity?.intent)

fun getScreenWidth(activity: Activity): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics = activity.windowManager.currentWindowMetrics
        val bounds: Rect = windowMetrics.bounds
        val insets: Insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars()
        )
        if (activity.resources.configuration.orientation
            == Configuration.ORIENTATION_LANDSCAPE &&
            activity.resources.configuration.smallestScreenWidthDp < 600
        ) { // landscape and phone
            val navigationBarSize: Int = insets.right + insets.left
            bounds.width() - navigationBarSize
        } else { // portrait or tablet
            bounds.width()
        }
    } else {
        val outMetrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(outMetrics)
        outMetrics.widthPixels
    }
}

fun getScreenHeight(activity: Activity): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics = activity.windowManager.currentWindowMetrics
        val bounds: Rect = windowMetrics.bounds
        val insets: Insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars()
        )
        if (activity.resources.configuration.orientation
            == Configuration.ORIENTATION_LANDSCAPE &&
            activity.resources.configuration.smallestScreenWidthDp < 600
        ) { // landscape and phone
            bounds.height()
        } else { // portrait or tablet
            val navigationBarSize: Int = insets.bottom
            bounds.height() - navigationBarSize
        }
    } else {
        val outMetrics = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(outMetrics)
        outMetrics.heightPixels
    }
}

fun createTitleAndSubtitleText(
    context: Context,
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int
): CharSequence = createTitleAndSubtitleText(
    context,
    context.getString(titleRes),
    context.getString(subtitleRes)
)

fun createTitleAndSubtitleText(
    context: Context,
    title: CharSequence,
    subtitle: CharSequence
): CharSequence {
    val spanBuilder = SpannableStringBuilder("$title\n$subtitle")
    spanBuilder.setSpan(
        TextAppearanceSpan(context, R.style.TextAppearance_AppCompat_Large),
        0,
        title.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    spanBuilder.setSpan(
        TextAppearanceSpan(context, R.style.TextAppearance_AppCompat_Small),
        title.length + 1,
        title.length + 1 + subtitle.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    return spanBuilder
}

fun String.firstUppercase() = this.first().uppercase()

fun ApplicationInfo.isSystemApp(): Boolean = (this.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
    (this.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)

fun AlignmentFormat.gravity(): Int = when (this.number) {
    2 -> 5 // RIGHT
    1 -> 1 // CENTER
    else -> 3 // LEFT
}

fun isPinnedApp(packageName: String): Boolean {
    return packageName == "com.spotify.music" || packageName == "at.rsg.pfp" || packageName == "com.google.android.apps.photos"
}


class Glitcher(private val baseBitmap: Bitmap, quality: Int, iterations: Int) {
    private lateinit var unmodifiedJpegBytes: ByteArray
    private val modifiedIndices = ArrayList<Int>()
    private val modifiedJpegBytes: ByteArray
    private val maxIndex: Int

    private val jpegHeaderSize: Int by lazy {
        for (i in unmodifiedJpegBytes.indices)
            if (unmodifiedJpegBytes[i].toInt() == 255 && unmodifiedJpegBytes[i + 1].toInt() == 218)
                return@lazy i + 2

        return@lazy 417
    }

    private fun undoLastChange() {
        if (modifiedIndices.isNotEmpty()) {
            val i = modifiedIndices.removeLast()
            modifiedJpegBytes[i] = unmodifiedJpegBytes[i]
        }
    }

    private fun makeAMess(iterations: Int) {
        val perIteration = maxIndex / iterations
        for (i in 0..iterations) {
            val delta = perIteration * Random.nextFloat()
            val offset = perIteration * i + delta.toInt()
            val pxIndex = jpegHeaderSize + min(offset, maxIndex)
            modifiedIndices.add(pxIndex)  // so we can undo this later
            modifiedJpegBytes[pxIndex] = Random.nextInt().toByte()
        }
    }

    fun getNext(): Bitmap {
        undoLastChange()
        return BitmapFactory.decodeByteArray(modifiedJpegBytes, 0, modifiedJpegBytes.size) ?: baseBitmap
    }

    fun done() = modifiedIndices.isEmpty()

    init {
        ByteArrayOutputStream().use {
            baseBitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            unmodifiedJpegBytes = it.toByteArray()
            modifiedJpegBytes = unmodifiedJpegBytes.clone()
            maxIndex = modifiedJpegBytes.size - jpegHeaderSize - 4
        }

        modifiedIndices.shuffle()
        makeAMess(iterations + 1)
    }
}
