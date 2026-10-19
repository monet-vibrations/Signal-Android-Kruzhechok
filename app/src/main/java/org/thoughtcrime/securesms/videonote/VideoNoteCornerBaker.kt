/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.videonote

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.annotation.WorkerThread
import android.media.MediaCodecInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.signal.core.util.logging.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Re-encodes a recorded video note with [VideoNoteCornerEffect] so the "round video" look survives on clients that show
 * it as a plain square video. Output stays H.264 at roughly the recording bitrate so the attachment pipeline passes it
 * through without transcoding again.
 */
@OptIn(markerClass = [UnstableApi::class])
object VideoNoteCornerBaker {

  private val TAG = Log.tag(VideoNoteCornerBaker::class.java)

  private const val TIMEOUT_MS = 90_000L
  private const val OUTPUT_SIZE = 768

  /**
   * Blocks until the export finishes.
   *
   * @return the processed file (owned by the caller), or null if processing failed, in which case nothing is left behind.
   */
  @WorkerThread
  fun bake(context: Context, input: File, videoBitrate: Int): File? {
    val output = File(context.cacheDir, "video_note_baked_${UUID.randomUUID()}.mp4")
    val startTime = System.currentTimeMillis()

    val thread = HandlerThread("VideoNoteBaker").apply { start() }
    val handler = Handler(thread.looper)
    val latch = CountDownLatch(1)
    val result = AtomicReference<ExportResult?>()
    val error = AtomicReference<Exception?>()
    var transformer: Transformer? = null // Only touched on the handler thread.

    handler.post {
      try {
        val encoderFactory = DefaultEncoderFactory.Builder(context)
          .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder()
              // Some encoders (notably software ones) overshoot in VBR; CBR keeps us under StreamingTranscoder's
              // re-transcode threshold so the file isn't encoded a second time by AttachmentCompressionJob.
              .setBitrate((videoBitrate * 0.8).toInt())
              .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
              .build()
          )
          .build()

        val newTransformer = Transformer.Builder(context)
          .setLooper(thread.looper)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .setEncoderFactory(encoderFactory)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              result.set(exportResult)
              latch.countDown()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
              error.set(exportException)
              latch.countDown()
            }
          })
          .build()
        transformer = newTransformer

        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
          .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(OUTPUT_SIZE, OUTPUT_SIZE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP), VideoNoteCornerEffect())))
          .build()

        newTransformer.start(item, output.absolutePath)
      } catch (e: Exception) {
        error.set(e)
        latch.countDown()
      }
    }

    val finished = try {
      latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      false
    }

    if (!finished) {
      handler.post { transformer?.cancel() }
    }
    thread.quitSafely()

    val exportResult = result.get()
    val failure: String? = when {
      !finished -> "Timed out after $TIMEOUT_MS ms"
      error.get() != null -> "Export failed"
      exportResult == null -> "No export result"
      exportResult.videoMimeType != null && exportResult.videoMimeType != MimeTypes.VIDEO_H264 -> "Unexpected video codec ${exportResult.videoMimeType}"
      !output.exists() || output.length() == 0L -> "Empty output"
      else -> null
    }

    if (failure != null) {
      Log.w(TAG, "Unable to bake video note corners, sending the original. $failure", error.get())
      output.delete()
      return null
    }

    Log.i(
      TAG,
      "Baked video note corners in ${System.currentTimeMillis() - startTime} ms: ${exportResult!!.width}x${exportResult.height}, " +
        "video ${exportResult.videoEncoderName} @ ${exportResult.averageVideoBitrate} bps, audio ${exportResult.audioMimeType}, ${output.length()} bytes"
    )
    return output
  }
}
