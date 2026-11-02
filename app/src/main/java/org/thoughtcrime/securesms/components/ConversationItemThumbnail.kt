/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.Px
import androidx.annotation.UiThread
import androidx.core.os.bundleOf
import com.bumptech.glide.RequestManager
import org.signal.core.ui.view.Stub
import org.signal.core.util.dp
import org.signal.core.util.getParcelableCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideClickListener
import org.thoughtcrime.securesms.mms.SlidesClickedListener
import org.thoughtcrime.securesms.util.Projection.Corners
import org.thoughtcrime.securesms.video.inline.InlineVideoHost

class ConversationItemThumbnail @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  private var state: ConversationItemThumbnailState
  private var thumbnail: Stub<ThumbnailView>
  private var album: Stub<AlbumThumbnailView>
  private var shade: ImageView
  var footer: Stub<ConversationItemFooter>
    private set

  val surfaceHost: InlineVideoHost
  private var cornerMask: CornerMask
  private var borderless = false
  private var normalBounds: IntArray
  private var gifBounds: IntArray
  private var minimumThumbnailWidth = 0
  private var maximumThumbnailHeight = 0

  /** Round video message mode: a square thumbnail clipped to a circle. */
  var isVideoNote = false
    private set
  private var videoNoteProgressVisible = false
  private val defaultOutlineProvider: ViewOutlineProvider? = outlineProvider
  private val defaultElevation: Float
  private val progressRect = RectF()
  private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeWidth = 3.dp.toFloat()
    color = Color.WHITE
  }
  private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3.dp.toFloat()
    color = Color.WHITE
    alpha = 0x4D
  }

  init {
    inflate(context, R.layout.conversation_item_thumbnail, this)

    thumbnail = Stub(findViewById(R.id.thumbnail_view_stub))
    album = Stub(findViewById(R.id.album_view_stub))
    shade = findViewById(R.id.conversation_thumbnail_shade)
    footer = Stub(findViewById(R.id.footer_view_stub))
    surfaceHost = findViewById(R.id.conversation_thumbnail_surface_host)
    cornerMask = CornerMask(this)

    var gifWidth = 260.dp

    if (attrs != null) {
      val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.ConversationItemThumbnail, 0, 0)
      normalBounds = intArrayOf(
        typedArray.getDimensionPixelSize(R.styleable.ConversationItemThumbnail_conversationThumbnail_minWidth, 0),
        typedArray.getDimensionPixelSize(R.styleable.ConversationItemThumbnail_conversationThumbnail_maxWidth, 0),
        typedArray.getDimensionPixelSize(R.styleable.ConversationItemThumbnail_conversationThumbnail_minHeight, 0),
        typedArray.getDimensionPixelSize(R.styleable.ConversationItemThumbnail_conversationThumbnail_maxHeight, 0)
      )

      gifWidth = typedArray.getDimensionPixelSize(R.styleable.ConversationItemThumbnail_conversationThumbnail_gifWidth, gifWidth)

      typedArray.recycle()
    } else {
      normalBounds = intArrayOf(0, 0, 0, 0)
    }

    gifBounds = intArrayOf(
      gifWidth,
      gifWidth,
      1,
      Int.MAX_VALUE
    )

    minimumThumbnailWidth = -1
    maximumThumbnailHeight = -1

    state = ConversationItemThumbnailState()
    defaultElevation = elevation
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)

    // FrameLayout won't stretch a lone match_parent child to its final size, so the video would otherwise measure 0x0.
    surfaceHost.measure(
      MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom, MeasureSpec.EXACTLY)
    )
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    if (!borderless) {
      cornerMask.mask(canvas)
    }

    if (isVideoNote && videoNoteProgressVisible) {
      drawVideoNoteProgress(canvas)
    }
  }

  private fun drawVideoNoteProgress(canvas: Canvas) {
    val slot = surfaceHost.slot
    if (slot == null || !slot.isSoundEnabled || !slot.hasRenderedFirstFrame) {
      return
    }

    val inset = progressPaint.strokeWidth / 2f + 1.5f.dp
    progressRect.set(inset, inset, width - inset, height - inset)
    canvas.drawArc(progressRect, 0f, 360f, false, progressTrackPaint)
    canvas.drawArc(progressRect, -90f, 360f * slot.playbackProgress, false, progressPaint)

    postInvalidateOnAnimation()
  }

  /**
   * Renders a single square video as a circle of [videoNoteSize]: no corner mask, no elevation, and both the still and
   * the inline video surface clipped to an oval. Call before [setImageResource].
   */
  fun setVideoNote(videoNote: Boolean) {
    if (videoNote == isVideoNote) {
      return
    }

    isVideoNote = videoNote

    if (videoNote) {
      outlineProvider = OvalOutlineProvider
      clipToOutline = true
      elevation = 0f
      val radius = videoNoteSize / 2
      setCorners(radius, radius, radius, radius)
      surfaceHost.setCornerRadius(radius.toFloat())
    } else {
      outlineProvider = defaultOutlineProvider
      clipToOutline = false
      elevation = defaultElevation
      videoNoteProgressVisible = false
      surfaceHost.setCornerRadius(0f)
    }

    invalidateOutline()
    requestLayout()
  }

  /**
   * Shows a progress ring around the circle while the hosted video plays with sound.
   */
  fun setVideoNoteProgressVisible(visible: Boolean) {
    if (videoNoteProgressVisible != visible) {
      videoNoteProgressVisible = visible
      invalidate()
    }
  }

  /** Diameter of a round video message: 0.6 × the shorter screen side, capped. */
  val videoNoteSize: Int
    get() {
      val metrics = resources.displayMetrics
      return (minOf(metrics.widthPixels, metrics.heightPixels) * 0.6f).toInt().coerceAtMost(MAX_VIDEO_NOTE_SIZE_DP.dp)
    }

  override fun onSaveInstanceState(): Parcelable {
    val root = super.onSaveInstanceState()
    return bundleOf(
      STATE_ROOT to root,
      STATE_STATE to state
    )
  }

  override fun onRestoreInstanceState(state: Parcelable) {
    if (state is Bundle && state.containsKey(STATE_STATE)) {
      val root: Parcelable? = state.getParcelableCompat(STATE_ROOT, Parcelable::class.java)
      this.state = state.getParcelableCompat(STATE_STATE, ConversationItemThumbnailState::class.java)!!
      super.onRestoreInstanceState(root)
    } else {
      super.onRestoreInstanceState(state)
    }
  }

  override fun setFocusable(focusable: Boolean) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(focusable = focusable),
      albumViewState = state.albumViewState.copy(focusable = focusable)
    )

    state.applyState(thumbnail, album)
  }

  override fun setClickable(clickable: Boolean) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(clickable = clickable),
      albumViewState = state.albumViewState.copy(clickable = clickable)
    )

    state.applyState(thumbnail, album)
  }

  override fun setOnLongClickListener(l: OnLongClickListener?) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(longClickListener = l),
      albumViewState = state.albumViewState.copy(longClickListener = l)
    )

    state.applyState(thumbnail, album)
  }

  fun hideThumbnailView() {
    state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(alpha = 0f))
    state.thumbnailViewState.applyState(thumbnail)
  }

  fun showThumbnailView() {
    state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(alpha = 1f))
    state.thumbnailViewState.applyState(thumbnail)
  }

  fun setPlayOverlayForced(forced: Boolean) {
    if (thumbnail.resolved()) {
      thumbnail.get().setPlayOverlayForced(forced)
    }
  }

  val corners: Corners
    get() = Corners(cornerMask.radii)

  fun showShade(show: Boolean) {
    shade.visibility = if (show) VISIBLE else GONE
    forceLayout()
  }

  fun setCorners(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int) {
    cornerMask.setRadii(topLeft, topRight, bottomRight, bottomLeft)
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(
        cornerTopLeft = topLeft,
        cornerTopRight = topRight,
        cornerBottomRight = bottomRight,
        cornerBottomLeft = bottomLeft
      ),
      albumViewState = state.albumViewState.copy(
        cornerTopLeft = topLeft,
        cornerTopRight = topRight,
        cornerBottomRight = bottomRight,
        cornerBottomLeft = bottomLeft
      )
    )

    state.applyState(thumbnail, album)
  }

  fun setMinimumThumbnailWidth(@Px width: Int) {
    minimumThumbnailWidth = width
    state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(minWidth = width))
    state.thumbnailViewState.applyState(thumbnail)
  }

  fun setMaximumThumbnailHeight(@Px height: Int) {
    maximumThumbnailHeight = height
    state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(maxHeight = height))
    state.thumbnailViewState.applyState(thumbnail)
  }

  fun setBorderless(borderless: Boolean) {
    this.borderless = borderless
  }

  @UiThread
  fun setImageResource(
    requestManager: RequestManager,
    slides: List<Slide>,
    showControls: Boolean,
    isPreview: Boolean,
    @ColorInt missingThumbnailBackgroundColor: Int
  ) {
    if (slides.size == 1 && isVideoNote) {
      val size = videoNoteSize
      setThumbnailBounds(intArrayOf(size, size, size, size))

      state = state.copy(
        thumbnailViewState = state.thumbnailViewState.copy(visibility = VISIBLE),
        albumViewState = state.albumViewState.copy(visibility = GONE)
      )

      state.applyState(thumbnail, album)

      thumbnail.get().setImageResource(requestManager, slides[0], showControls, isPreview, size, size, missingThumbnailBackgroundColor)
      touchDelegate = thumbnail.get().touchDelegate
    } else if (slides.size == 1) {
      val slide = slides[0]

      if (slide.isVideoGif) {
        setThumbnailBounds(gifBounds)
      } else {
        setThumbnailBounds(normalBounds)

        if (minimumThumbnailWidth != -1) {
          state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(minWidth = minimumThumbnailWidth))
        }

        if (maximumThumbnailHeight != -1) {
          state = state.copy(thumbnailViewState = state.thumbnailViewState.copy(maxHeight = maximumThumbnailHeight))
        }
      }

      state = state.copy(
        thumbnailViewState = state.thumbnailViewState.copy(visibility = VISIBLE),
        albumViewState = state.albumViewState.copy(visibility = GONE)
      )

      state.applyState(thumbnail, album)

      val attachment = slides[0].asAttachment()

      thumbnail.get().setImageResource(requestManager, slides[0], showControls, isPreview, attachment.width, attachment.height, missingThumbnailBackgroundColor)
      touchDelegate = thumbnail.get().touchDelegate
    } else {
      state = state.copy(
        thumbnailViewState = state.thumbnailViewState.copy(visibility = GONE),
        albumViewState = state.albumViewState.copy(visibility = VISIBLE)
      )

      state.applyState(thumbnail, album)
      album.get().setSlides(requestManager, slides, showControls)
      touchDelegate = album.get().touchDelegate
    }
  }

  fun setConversationColor(@ColorInt color: Int) {
    state = state.copy(albumViewState = state.albumViewState.copy(cellBackgroundColor = color))
    state.albumViewState.applyState(album)
  }

  fun setThumbnailClickListener(listener: SlideClickListener?) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(clickListener = listener),
      albumViewState = state.albumViewState.copy(clickListener = listener)
    )

    state.applyState(thumbnail, album)
  }

  fun setStartTransferClickListener(listener: SlidesClickedListener?) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(startTransferClickListener = listener),
      albumViewState = state.albumViewState.copy(startTransferClickListener = listener)
    )

    state.applyState(thumbnail, album)
  }

  fun setPlayVideoClickListener(listener: SlideClickListener?) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(playVideoClickListener = listener),
      albumViewState = state.albumViewState.copy(playVideoClickListener = listener)
    )

    state.applyState(thumbnail, album)
  }

  fun setCancelTransferClickListener(listener: SlidesClickedListener?) {
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(cancelTransferClickListener = listener),
      albumViewState = state.albumViewState.copy(cancelTransferClickListener = listener)
    )

    state.applyState(thumbnail, album)
  }

  private fun setThumbnailBounds(bounds: IntArray) {
    val (minWidth, maxWidth, minHeight, maxHeight) = bounds
    state = state.copy(
      thumbnailViewState = state.thumbnailViewState.copy(
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight
      )
    )
    state.thumbnailViewState.applyState(thumbnail)
  }

  private object OvalOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
      outline.setOval(0, 0, view.width, view.height)
    }
  }

  companion object {
    private const val MAX_VIDEO_NOTE_SIZE_DP = 260
    private const val STATE_ROOT = "state.root"
    private const val STATE_STATE = "state.state"
  }
}
