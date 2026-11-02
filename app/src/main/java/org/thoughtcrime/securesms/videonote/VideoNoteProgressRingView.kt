/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.videonote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * Draws the white progress ring around the round camera preview while a video note is recording.
 * The ring starts at the top and fills clockwise as the recording approaches its maximum duration.
 */
class VideoNoteProgressRingView(context: Context) : View(context) {

  private val strokeWidth = ViewUtil.dpToPx(3).toFloat()

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.WHITE
    strokeCap = Paint.Cap.ROUND
    strokeWidth = this@VideoNoteProgressRingView.strokeWidth
  }

  private val rect = RectF()

  private var startTimeMs: Long = -1
  private var maxDurationMs: Long = 1

  /** Starts animating the ring from 0 to full over [maxDurationMs]. */
  fun start(maxDurationMs: Long) {
    this.maxDurationMs = maxDurationMs.coerceAtLeast(1)
    this.startTimeMs = SystemClock.uptimeMillis()
    postInvalidateOnAnimation()
  }

  fun stop() {
    startTimeMs = -1
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    val start = startTimeMs
    if (start < 0) {
      return
    }

    val progress = ((SystemClock.uptimeMillis() - start).toFloat() / maxDurationMs).coerceIn(0f, 1f)
    val inset = strokeWidth / 2f
    rect.set(inset, inset, width - inset, height - inset)
    canvas.drawArc(rect, -90f, 360f * progress, false, paint)

    if (progress < 1f) {
      postInvalidateOnAnimation()
    }
  }
}
