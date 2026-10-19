/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.videonote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.camera.view.PreviewView
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.ViewUtil
import kotlin.math.min

/**
 * Full-size overlay shown above the conversation while a video note is recording: a dimmed background,
 * a circular live camera preview with a progress ring around it and a flip-camera button below it.
 */
@SuppressLint("ViewConstructor")
class VideoNoteRecorderOverlay(context: Context) : FrameLayout(context) {

  companion object {
    private const val SHOW_ANIMATION_MS = 180L
    private const val HIDE_ANIMATION_MS = 150L
    private const val CIRCLE_SCREEN_FRACTION = 0.6f
  }

  private val ringGap = ViewUtil.dpToPx(8)
  private val ringStroke = ViewUtil.dpToPx(3)

  val circleSize: Int = run {
    val metrics = context.resources.displayMetrics
    (min(metrics.widthPixels, metrics.heightPixels) * CIRCLE_SCREEN_FRACTION).toInt()
  }

  private val dim = View(context).apply {
    setBackgroundColor(Color.argb(0x66, 0, 0, 0))
    alpha = 0f
    // Swallow touches so the conversation underneath can't be scrolled/clicked while recording.
    isClickable = true
  }

  private val circleContainer = FrameLayout(context).apply {
    clipChildren = false
  }

  val previewView: PreviewView = PreviewView(context).apply {
    // TextureView-based so the oval outline clip actually applies to the camera frames.
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FILL_CENTER
    setBackgroundColor(Color.BLACK)
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
      }
    }
    clipToOutline = true
  }

  val progressRing = VideoNoteProgressRingView(context)

  val flipButton: ImageButton = ImageButton(context).apply {
    setImageResource(R.drawable.symbol_switch_24)
    setColorFilter(Color.WHITE)
    contentDescription = context.getString(R.string.SwitchCameraButton__switch_camera_direction)
    background = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(Color.argb(0x80, 0, 0, 0))
    }
    val padding = ViewUtil.dpToPx(12)
    setPadding(padding, padding, padding, padding)
  }

  init {
    clipChildren = false
    clipToPadding = false

    addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    val containerSize = circleSize + 2 * (ringGap + ringStroke)
    addView(circleContainer, LayoutParams(containerSize, containerSize, Gravity.CENTER))

    circleContainer.addView(previewView, LayoutParams(circleSize, circleSize, Gravity.CENTER))
    circleContainer.addView(progressRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    val flipSize = ViewUtil.dpToPx(48)
    addView(
      flipButton,
      LayoutParams(flipSize, flipSize, Gravity.CENTER).apply {
        topMargin = containerSize / 2 + flipSize / 2 + ViewUtil.dpToPx(16)
      }
    )

    circleContainer.scaleX = 0.1f
    circleContainer.scaleY = 0.1f
    circleContainer.alpha = 0f
    flipButton.alpha = 0f
  }

  fun animateIn() {
    dim.animate().alpha(1f).setDuration(SHOW_ANIMATION_MS).start()
    flipButton.animate().alpha(1f).setDuration(SHOW_ANIMATION_MS).start()
    circleContainer.animate()
      .scaleX(1f)
      .scaleY(1f)
      .alpha(1f)
      .setInterpolator(DecelerateInterpolator())
      .setDuration(SHOW_ANIMATION_MS)
      .start()
  }

  fun animateOut(onEnd: () -> Unit) {
    progressRing.stop()
    flipButton.isEnabled = false
    dim.animate().alpha(0f).setDuration(HIDE_ANIMATION_MS).start()
    flipButton.animate().alpha(0f).setDuration(HIDE_ANIMATION_MS).start()
    circleContainer.animate()
      .scaleX(0.1f)
      .scaleY(0.1f)
      .alpha(0f)
      .setDuration(HIDE_ANIMATION_MS)
      .withEndAction(onEnd)
      .start()
  }
}
