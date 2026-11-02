package org.thoughtcrime.securesms.video.exo

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

fun ExoPlayer.configureForGifPlayback() {
  repeatMode = Player.REPEAT_MODE_ALL
  volume = 0f
  trackSelectionParameters = trackSelectionParameters.buildUpon()
    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
    .build()
}

/**
 * Inline playback with sound, e.g. a round video message the user tapped. Still repeats, so a loop policy decides when
 * a single pass is over. Undo with [configureForGifPlayback] followed by [releaseAudioFocusHandling].
 */
fun ExoPlayer.configureForInlinePlaybackWithSound() {
  repeatMode = Player.REPEAT_MODE_ALL
  volume = 1f
  trackSelectionParameters = trackSelectionParameters.buildUpon()
    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    .build()
  setAudioAttributes(
    AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
      .build(),
    true
  )
}

fun ExoPlayer.releaseAudioFocusHandling() {
  setAudioAttributes(AudioAttributes.DEFAULT, false)
}
