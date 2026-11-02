package org.thoughtcrime.securesms.components;

import android.Manifest;
import android.content.Context;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.content.SharedPreferences;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.audio.AudioRecordingHandler;
import org.signal.core.ui.permissions.Permissions;
import org.signal.core.ui.util.ViewUtil;

public final class MicrophoneRecorderView extends FrameLayout implements View.OnTouchListener {

  enum State {
    NOT_RUNNING,
    RUNNING_HELD,
    RUNNING_LOCKED
  }

  /** What a press-and-hold on the record button records. A quick tap toggles between the two. */
  public enum RecordMode {
    AUDIO,
    VIDEO
  }

  public static final int ANIMATION_DURATION = 200;

  /** A press shorter than this is a tap (toggles the mode); a longer one starts recording. */
  private static final long HOLD_TO_RECORD_DELAY_MS = 150;

  private static final String PREFS_NAME          = "video_note_prefs";
  private static final String PREF_KEY_VIDEO_MODE = "record_mode_video";

  private           FloatingRecordButton   floatingRecordButton;
  private           LockDropTarget        lockDropTarget;
  private @Nullable AudioRecordingHandler handler;
  private @NonNull  State                 state = State.NOT_RUNNING;
  private @NonNull  RecordMode            mode  = RecordMode.AUDIO;
  private @NonNull  RecordMode            activeRecordingMode = RecordMode.AUDIO;
  private           ImageButton           recordButton;
  private           ImageView             recordButtonFab;
  private           boolean               pressPending;
  private           float                 pressDownX;
  private           float                 pressDownY;

  private final AudioManager audioManager;
  private final Runnable     startRecordingRunnable = this::onHoldDelayElapsed;

  public MicrophoneRecorderView(Context context) {
    super(context);
    this.audioManager = ContextCompat.getSystemService(context, AudioManager.class);
  }

  public MicrophoneRecorderView(Context context, AttributeSet attrs) {
    super(context, attrs);
    this.audioManager = ContextCompat.getSystemService(context, AudioManager.class);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    floatingRecordButton = new FloatingRecordButton(getContext(), findViewById(R.id.quick_audio_fab));
    lockDropTarget       = new LockDropTarget      (getContext(), findViewById(R.id.lock_drop_target));

    recordButton    = findViewById(R.id.quick_audio_toggle);
    recordButtonFab = findViewById(R.id.quick_audio_fab);
    recordButton.setOnTouchListener(this);

    mode = isInEditMode() ? RecordMode.AUDIO : loadPersistedMode(getContext());
    updateModeIcons();
  }

  public @NonNull RecordMode getRecordMode() {
    return mode;
  }

  /**
   * @return The mode of the recording in progress, or of the most recent one if none is in progress.
   */
  public @NonNull RecordMode getActiveRecordingMode() {
    return activeRecordingMode;
  }

  private void toggleMode() {
    mode = mode == RecordMode.AUDIO ? RecordMode.VIDEO : RecordMode.AUDIO;
    getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_VIDEO_MODE, mode == RecordMode.VIDEO)
                .apply();
    updateModeIcons();
    recordButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
  }

  private void updateModeIcons() {
    boolean video = mode == RecordMode.VIDEO;
    int     icon  = video ? R.drawable.symbol_video_24 : org.signal.core.ui.R.drawable.symbol_mic_24;
    recordButton.setImageResource(icon);
    recordButton.setContentDescription(getContext().getString(video ? R.string.conversation_activity__quick_attachment_drawer_record_and_send_video_note_description
                                                                    : R.string.conversation_activity__quick_attachment_drawer_record_and_send_audio_description));
    recordButtonFab.setImageResource(icon);
  }

  private static @NonNull RecordMode loadPersistedMode(@NonNull Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    return prefs.getBoolean(PREF_KEY_VIDEO_MODE, false) ? RecordMode.VIDEO : RecordMode.AUDIO;
  }

  public void cancelAction(boolean byUser) {
    if (state != State.NOT_RUNNING) {
      state = State.NOT_RUNNING;
      hideUi();

      if (handler != null) {
        handler.onRecordCanceled(byUser);
      }
    }
  }

  public void saveAction() {
    if (state != State.NOT_RUNNING) {
      state = State.NOT_RUNNING;
      hideUi();

      if (handler != null) {
        handler.onRecordSaved();
      }
    }
  }

  public boolean isRecordingLocked() {
    return state == State.RUNNING_LOCKED;
  }

  public boolean isRecording() {
    return state != State.NOT_RUNNING;
  }

  private void lockAction() {
    if (state == State.RUNNING_HELD) {
      state = State.RUNNING_LOCKED;
      hideUi();

      if (handler != null) handler.onRecordLocked();
    }
  }

  public void unlockAction() {
    if (state == State.RUNNING_LOCKED) {
      state = State.NOT_RUNNING;
      hideUi();

      if (handler != null) handler.onRecordReleased();
    }
  }

  /**
   * Finishes the current recording (held or locked) as if the user had released it, i.e. sends it.
   */
  public void finishAction() {
    if (state != State.NOT_RUNNING) {
      state = State.NOT_RUNNING;
      hideUi();

      if (handler != null) handler.onRecordReleased();
    }
  }

  private void hideUi() {
    floatingRecordButton.hide();
    lockDropTarget.hide();
  }

  @Override
  public boolean onTouch(View v, final MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        if (state == State.NOT_RUNNING) {
          pressPending = true;
          pressDownX   = event.getX();
          pressDownY   = event.getY();
          removeCallbacks(startRecordingRunnable);
          postDelayed(startRecordingRunnable, HOLD_TO_RECORD_DELAY_MS);
        }
        break;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        if (pressPending) {
          pressPending = false;
          removeCallbacks(startRecordingRunnable);
          if (event.getAction() == MotionEvent.ACTION_UP) {
            toggleMode();
          }
        } else if (this.state == State.RUNNING_HELD) {
          state = State.NOT_RUNNING;
          hideUi();
          if (handler != null) handler.onRecordReleased();
        }
        break;
      case MotionEvent.ACTION_MOVE:
        if (this.state == State.RUNNING_HELD) {
          this.floatingRecordButton.moveTo(event.getX(), event.getY());
          if (handler != null) handler.onRecordMoved(floatingRecordButton.lastOffsetX, event.getRawX());

          int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
          if (floatingRecordButton.lastOffsetY <= dimensionPixelSize) {
            lockAction();
          }
        }
        break;
    }

    return false;
  }

  private void onHoldDelayElapsed() {
    if (!pressPending) return;
    pressPending = false;

    boolean isMicPossiblyInUse = false;
    if (audioManager != null) {
      isMicPossiblyInUse = audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION || audioManager.getMode() == AudioManager.MODE_IN_CALL;
    }

    boolean hasPermissions = mode == RecordMode.VIDEO ? Permissions.hasAll(getContext(), Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                                      : Permissions.hasAll(getContext(), Manifest.permission.RECORD_AUDIO);

    if (!hasPermissions) {
      activeRecordingMode = mode;
      if (handler != null) handler.onRecordPermissionRequired();
    } else if (isMicPossiblyInUse) {
      activeRecordingMode = mode;
      if (handler != null) handler.onRecorderAlreadyInUse();
    } else if (state == State.NOT_RUNNING) {
      activeRecordingMode = mode;
      state               = State.RUNNING_HELD;
      floatingRecordButton.display(pressDownX, pressDownY);
      lockDropTarget.display();
      if (handler != null) handler.onRecordPressed();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    pressPending = false;
    removeCallbacks(startRecordingRunnable);
  }

  public void setHandler(@Nullable AudioRecordingHandler handler) {
    this.handler = handler;
  }

  private static class FloatingRecordButton {

    private final ImageView recordButtonFab;

    private float startPositionX;
    private float startPositionY;
    private float lastOffsetX;
    private float lastOffsetY;

    FloatingRecordButton(Context context, ImageView recordButtonFab) {
      this.recordButtonFab = recordButtonFab;
      this.recordButtonFab.getBackground().setColorFilter(context.getResources()
                                                                 .getColor(R.color.red_500),
                                                          PorterDuff.Mode.SRC_IN);
    }

    void display(float x, float y) {
      this.startPositionX = x;
      this.startPositionY = y;
      this.lastOffsetX    = 0;
      this.lastOffsetY    = 0;

      recordButtonFab.setVisibility(View.VISIBLE);

      AnimationSet animation = new AnimationSet(true);
      animation.addAnimation(new TranslateAnimation(Animation.ABSOLUTE, 0,
                                                    Animation.ABSOLUTE, 0,
                                                    Animation.ABSOLUTE, 0,
                                                    Animation.ABSOLUTE, 0));

      animation.addAnimation(new ScaleAnimation(.5f, 1f, .5f, 1f,
                                                Animation.RELATIVE_TO_SELF, .5f,
                                                Animation.RELATIVE_TO_SELF, .5f));

      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new OvershootInterpolator());

      recordButtonFab.startAnimation(animation);
    }

    void moveTo(float x, float y) {
      lastOffsetX   = getXOffset(x);
      lastOffsetY   = getYOffset(y);

      if (Math.abs(lastOffsetX) > Math.abs(lastOffsetY)) {
        lastOffsetY = 0;
      } else {
        lastOffsetX = 0;
      }

      recordButtonFab.setTranslationX(lastOffsetX);
      recordButtonFab.setTranslationY(lastOffsetY);
    }

    void hide() {
      recordButtonFab.setTranslationX(0);
      recordButtonFab.setTranslationY(0);
      if (recordButtonFab.getVisibility() != VISIBLE) return;

      AnimationSet animation = new AnimationSet(false);
      Animation scaleAnimation = new ScaleAnimation(1, 0.5f, 1, 0.5f,
                                                    Animation.RELATIVE_TO_SELF, 0.5f,
                                                    Animation.RELATIVE_TO_SELF, 0.5f);

      Animation translateAnimation = new TranslateAnimation(Animation.ABSOLUTE, lastOffsetX,
                                                            Animation.ABSOLUTE, 0,
                                                            Animation.ABSOLUTE, lastOffsetY,
                                                            Animation.ABSOLUTE, 0);

      scaleAnimation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));
      translateAnimation.setInterpolator(new DecelerateInterpolator());
      animation.addAnimation(scaleAnimation);
      animation.addAnimation(translateAnimation);
      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));

      recordButtonFab.setVisibility(View.GONE);
      recordButtonFab.clearAnimation();
      recordButtonFab.startAnimation(animation);
    }

    private float getXOffset(float x) {
      return ViewUtil.isLtr(recordButtonFab) ? -Math.max(0, this.startPositionX - x)
                                             : Math.max(0, x - this.startPositionX);
    }

    private float getYOffset(float y) {
      return Math.min(0, y - this.startPositionY);
    }
  }

  private static class LockDropTarget {

    private final View lockDropTarget;
    private final int  dropTargetPosition;

    LockDropTarget(Context context, View lockDropTarget) {
      this.lockDropTarget     = lockDropTarget;
      this.dropTargetPosition = context.getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
    }

    void display() {
      lockDropTarget.animate().cancel();
      lockDropTarget.setScaleX(1);
      lockDropTarget.setScaleY(1);
      lockDropTarget.setAlpha(0);
      lockDropTarget.setTranslationY(0);
      lockDropTarget.setVisibility(VISIBLE);
      lockDropTarget.animate()
                    .setStartDelay(ANIMATION_DURATION * 2)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(new DecelerateInterpolator())
                    .translationY(dropTargetPosition)
                    .alpha(1)
                    .start();
    }

    void hide() {
      if (lockDropTarget.getVisibility() != VISIBLE) return;

      lockDropTarget.animate().cancel();
      lockDropTarget.animate()
                    .setStartDelay(0)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(new LinearInterpolator())
                    .scaleX(0).scaleY(0)
                    .withEndAction(() -> lockDropTarget.setVisibility(GONE))
                    .start();
    }
  }
}
