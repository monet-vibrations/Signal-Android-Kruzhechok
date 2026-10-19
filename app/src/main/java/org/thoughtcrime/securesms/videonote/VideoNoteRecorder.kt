/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.videonote

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.util.Rational
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import org.signal.core.util.ServiceUtil
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.VideoSlide
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Records a round video message ("video note"): shows a circular front-camera preview overlay inside [overlayParent],
 * records a square H.264/AAC mp4 with CameraX and hands back a ready-to-send [VideoSlide].
 *
 * The gesture handling (hold, slide to cancel, lock) lives in the input panel; this class is driven by [start] and [stop].
 * One instance can be reused for any number of consecutive recordings.
 */
class VideoNoteRecorder(
  private val fragment: Fragment,
  private val overlayParent: ViewGroup,
  private val callback: Callback
) {

  companion object {
    private val TAG = Log.tag(VideoNoteRecorder::class.java)

    const val MAX_DURATION_MS = 60_000L

    /** Just under StreamingTranscoder's pass-through threshold so the clip is sent as-is without re-encoding. */
    private const val TARGET_VIDEO_BITRATE = 1_000_000
  }

  interface Callback {
    /** Called before recording begins so the host can pause any media playback. */
    fun onVideoNoteRecordingWillBegin()

    /** A finished recording, ready to send. */
    fun onVideoNoteRecorded(slide: VideoSlide)

    /** The recorder stopped on its own at [MAX_DURATION_MS]. The host should finish the gesture and call [stop] with send = true. */
    fun onVideoNoteDurationLimitReached()
  }

  private var session: Session? = null

  val isActive: Boolean
    get() = session != null

  @MainThread
  fun start() {
    if (session != null) {
      Log.w(TAG, "Recording already in progress, ignoring start.")
      return
    }

    val context = fragment.requireContext()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Missing camera permission.")
      return
    }

    callback.onVideoNoteRecordingWillBegin()
    session = Session().also { it.begin() }
  }

  /**
   * Stops the current recording.
   *
   * @param send true to deliver the clip via [Callback.onVideoNoteRecorded], false to discard it.
   */
  @MainThread
  fun stop(send: Boolean) {
    session?.stop(send)
  }

  private enum class Decision { UNDECIDED, SEND, DISCARD }

  private inner class Session {
    private val context = fragment.requireContext().applicationContext
    private val outputFile = File(context.cacheDir, "video_note_${UUID.randomUUID()}.mp4")
    private val overlay = VideoNoteRecorderOverlay(fragment.requireContext())

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private var decision = Decision.UNDECIDED
    private var finalizedFile: File? = null
    private var finished = false

    fun begin() {
      val activity = fragment.requireActivity()
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
      vibrate(20)

      overlayParent.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      overlay.animateIn()
      overlay.flipButton.setOnClickListener { flipCamera() }

      val providerFuture = ProcessCameraProvider.getInstance(context)
      providerFuture.addListener({
        if (finished || decision != Decision.UNDECIDED) {
          Log.i(TAG, "Recording stopped before the camera became available.")
          finish()
          return@addListener
        }

        try {
          val provider = providerFuture.get()
          cameraProvider = provider

          if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            lensFacing = CameraSelector.LENS_FACING_BACK
            overlay.flipButton.visibility = android.view.View.GONE
          } else if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            overlay.flipButton.visibility = android.view.View.GONE
          }

          val newPreview = Preview.Builder().build().also { it.surfaceProvider = overlay.previewView.surfaceProvider }
          val newVideoCapture = buildVideoCapture()
          preview = newPreview
          videoCapture = newVideoCapture

          bindUseCases(provider, newPreview, newVideoCapture)
          startRecording(newVideoCapture)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to start the camera.", e)
          onFailure()
        }
      }, ContextCompat.getMainExecutor(context))
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
      val recorder = Recorder.Builder()
        .setAspectRatio(AspectRatio.RATIO_4_3)
        .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
        .setTargetVideoEncodingBitRate(TARGET_VIDEO_BITRATE)
        .build()

      return VideoCapture.Builder(recorder)
        .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        .build()
    }

    private fun bindUseCases(provider: ProcessCameraProvider, preview: Preview, videoCapture: VideoCapture<Recorder>) {
      val rotation = overlay.display?.rotation ?: fragment.view?.display?.rotation ?: Surface.ROTATION_0
      val viewPort = ViewPort.Builder(Rational(1, 1), rotation)
        .setScaleType(ViewPort.FILL_CENTER)
        .build()

      val useCaseGroup = UseCaseGroup.Builder()
        .setViewPort(viewPort)
        .addUseCase(preview)
        .addUseCase(videoCapture)
        .build()

      val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
      provider.bindToLifecycle(fragment.viewLifecycleOwner, selector, useCaseGroup)
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.OptIn(markerClass = [ExperimentalPersistentRecording::class])
    private fun startRecording(capture: VideoCapture<Recorder>) {
      val outputOptions = FileOutputOptions.Builder(outputFile)
        .setDurationLimitMillis(MAX_DURATION_MS)
        .build()

      val pending = capture.output
        .prepareRecording(context, outputOptions)
        // Persistent so flipping the camera (which rebinds the use cases) doesn't end the recording.
        .asPersistentRecording()

      val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
      val configured = if (hasAudio) pending.withAudioEnabled() else pending

      recording = configured.start(ContextCompat.getMainExecutor(context)) { event ->
        when (event) {
          is VideoRecordEvent.Start -> {
            Log.d(TAG, "Recording started.")
            overlay.progressRing.start(MAX_DURATION_MS)
          }
          is VideoRecordEvent.Finalize -> onFinalize(event)
          else -> Unit
        }
      }
    }

    private fun flipCamera() {
      val provider = cameraProvider ?: return
      val currentPreview = preview ?: return
      val currentCapture = videoCapture ?: return
      if (finished || decision != Decision.UNDECIDED) return

      lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

      try {
        provider.unbind(currentPreview, currentCapture)
        bindUseCases(provider, currentPreview, currentCapture)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to flip camera.", e)
      }
    }

    fun stop(send: Boolean) {
      if (finished) return

      if (decision == Decision.SEND && !send) {
        // Already sending, don't let a late cancel discard it.
        return
      }

      decision = if (send) Decision.SEND else Decision.DISCARD

      val finalized = finalizedFile
      val activeRecording = recording
      when {
        finalized != null -> deliver(finalized)
        activeRecording != null -> activeRecording.stop()
        cameraProvider == null -> Unit // Camera still starting; the provider listener will clean up.
        else -> finish()
      }
    }

    private fun onFinalize(event: VideoRecordEvent.Finalize) {
      recording = null

      if (finished) {
        outputFile.delete()
        return
      }

      val error = event.error
      val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
      Log.i(TAG, "Recording finalized. error: $error, duration: ${durationMs}ms, bytes: ${event.recordingStats.numBytesRecorded}")

      val usable = (error == VideoRecordEvent.Finalize.ERROR_NONE || error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) && outputFile.exists()

      if (!usable) {
        if (decision == Decision.SEND && error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
          Toast.makeText(context, R.string.VideoNoteRecorder__unable_to_record_video_message, Toast.LENGTH_SHORT).show()
        }
        finish()
        return
      }

      when (decision) {
        Decision.UNDECIDED -> {
          // Hit the duration limit while the user is still holding/locked. Keep the file until told what to do.
          finalizedFile = outputFile
          stopCamera()
          callback.onVideoNoteDurationLimitReached()
        }
        else -> deliver(outputFile)
      }
    }

    private fun deliver(file: File) {
      if (decision != Decision.SEND) {
        finish()
        return
      }

      // Detach from the session so a subsequent recording can start while this one is processed.
      finish(deleteFile = false)

      SignalExecutors.BOUNDED.execute {
        val slide = try {
          createSlide(file)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to prepare video note.", e)
          null
        } finally {
          file.delete()
        }

        ThreadUtil.runOnMain {
          if (slide == null) {
            Toast.makeText(context, R.string.VideoNoteRecorder__unable_to_record_video_message, Toast.LENGTH_SHORT).show()
          } else if (fragment.isAdded && fragment.view != null) {
            callback.onVideoNoteRecorded(slide)
          } else {
            Log.w(TAG, "Fragment gone before the video note was ready, dropping it.")
            AppDependencies.blobs.delete(context, slide.uri!!)
          }
        }
      }
    }

    @Throws(IOException::class)
    private fun createSlide(file: File): VideoSlide {
      var width = 0
      var height = 0

      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
          val tmp = width
          width = height
          height = tmp
        }
      } catch (e: RuntimeException) {
        Log.w(TAG, "Unable to read video dimensions.", e)
      } finally {
        retriever.release()
      }

      val size = file.length()
      val uri = FileInputStream(file).use { input ->
        AppDependencies.blobs
          .forData(input, size)
          .withMimeType(MediaUtil.VIDEO_MP4)
          .createForSingleSessionOnDisk(context)
      }

      Log.i(TAG, "Video note ready: ${width}x$height, $size bytes")
      return VideoSlide.forVideoNote(context, uri, size, width, height)
    }

    private fun onFailure() {
      Toast.makeText(context, R.string.VideoNoteRecorder__unable_to_record_video_message, Toast.LENGTH_SHORT).show()
      finish()
    }

    private fun stopCamera() {
      val provider = cameraProvider ?: return
      val currentPreview = preview
      val currentCapture = videoCapture
      try {
        if (currentPreview != null) provider.unbind(currentPreview)
        if (currentCapture != null) provider.unbind(currentCapture)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to unbind camera.", e)
      }
      preview = null
      videoCapture = null
    }

    private fun finish(deleteFile: Boolean = true) {
      if (finished) return
      finished = true

      recording?.let {
        recording = null
        it.stop()
      }

      stopCamera()

      if (deleteFile) {
        outputFile.delete()
      }

      val activity = fragment.activity
      if (activity != null) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
      vibrate(if (decision == Decision.SEND) 20 else 50)

      if (fragment.viewLifecycleOwnerLiveData.value?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
        overlay.animateOut { overlayParent.removeView(overlay) }
      } else {
        overlayParent.removeView(overlay)
      }

      if (session === this) {
        session = null
      }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
      ServiceUtil.getVibrator(context).vibrate(ms)
    }
  }
}
