/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BitmapFactory;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.R;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.id3.ApicFrame;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.android.exoplayer2.util.Assertions;
import java.util.List;

/**
 * Displays a video stream.
 */
@TargetApi(16)
public final class SimpleExoPlayerView extends FrameLayout {

  private static final int SURFACE_TYPE_NONE = 0;
  private static final int SURFACE_TYPE_SURFACE_VIEW = 1;
  private static final int SURFACE_TYPE_TEXTURE_VIEW = 2;

  private final ViewGroup videoFrame;
  private final AspectRatioFrameLayout aspectRatioVideoFrame;
  private final View shutterView;
  private final View surfaceView;
  private final ImageView artworkView;
  private final SubtitleView subtitleView;
  private final PlaybackControlView controller;
  private final ComponentListener componentListener;

  private SimpleExoPlayer player;
  private boolean useController;
  private boolean useArtwork;
  private int controllerShowTimeoutMs;

  public SimpleExoPlayerView(Context context) {
    this(context, null);
  }

  public SimpleExoPlayerView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SimpleExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    int playerLayoutId = R.layout.exo_simple_player_view;
    boolean useArtwork = true;
    boolean useController = true;
    int surfaceType = SURFACE_TYPE_SURFACE_VIEW;
    int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    int rewindMs = PlaybackControlView.DEFAULT_REWIND_MS;
    int fastForwardMs = PlaybackControlView.DEFAULT_FAST_FORWARD_MS;
    int controllerShowTimeoutMs = PlaybackControlView.DEFAULT_SHOW_TIMEOUT_MS;
    if (attrs != null) {
      TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
          R.styleable.SimpleExoPlayerView, 0, 0);
      try {
        playerLayoutId = a.getResourceId(R.styleable.SimpleExoPlayerView_player_layout_id,
            playerLayoutId);
        useArtwork = a.getBoolean(R.styleable.SimpleExoPlayerView_use_artwork, useArtwork);
        useController = a.getBoolean(R.styleable.SimpleExoPlayerView_use_controller, useController);
        surfaceType = a.getInt(R.styleable.SimpleExoPlayerView_surface_type, surfaceType);
        resizeMode = a.getInt(R.styleable.SimpleExoPlayerView_resize_mode, resizeMode);
        rewindMs = a.getInt(R.styleable.SimpleExoPlayerView_rewind_increment, rewindMs);
        fastForwardMs = a.getInt(R.styleable.SimpleExoPlayerView_fastforward_increment,
            fastForwardMs);
        controllerShowTimeoutMs = a.getInt(R.styleable.SimpleExoPlayerView_show_timeout,
            controllerShowTimeoutMs);
      } finally {
        a.recycle();
      }
    }

    LayoutInflater.from(context).inflate(playerLayoutId, this);
    componentListener = new ComponentListener();

    videoFrame = (ViewGroup) findViewById(R.id.exo_video_frame);
    if (videoFrame != null) {
      if (videoFrame instanceof AspectRatioFrameLayout) {
        aspectRatioVideoFrame = (AspectRatioFrameLayout) videoFrame;
        setResizeModeRaw(aspectRatioVideoFrame, resizeMode);
      } else {
        aspectRatioVideoFrame = null;
      }
      shutterView = Assertions.checkNotNull(videoFrame.findViewById(R.id.exo_shutter));
      if (surfaceType != SURFACE_TYPE_NONE) {
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceView = surfaceType == SURFACE_TYPE_TEXTURE_VIEW ? new TextureView(context)
            : new SurfaceView(context);
        surfaceView.setLayoutParams(params);
        videoFrame.addView(surfaceView, 0);
      } else {
        surfaceView = null;
      }
    } else {
      aspectRatioVideoFrame = null;
      shutterView = null;
      surfaceView = null;
    }

    artworkView = (ImageView) findViewById(R.id.exo_artwork);
    this.useArtwork = useArtwork && artworkView != null;

    subtitleView = (SubtitleView) findViewById(R.id.exo_subtitles);
    if (subtitleView != null) {
      subtitleView.setUserDefaultStyle();
      subtitleView.setUserDefaultTextSize();
    }

    PlaybackControlView controller = (PlaybackControlView) findViewById(R.id.exo_controller);
    if (controller != null) {
      controller.setRewindIncrementMs(rewindMs);
      controller.setFastForwardIncrementMs(fastForwardMs);
    } else {
      View controllerPlaceholder = findViewById(R.id.exo_controller_placeholder);
      if (controllerPlaceholder != null) {
        // Note: rewindMs and fastForwardMs are passed via attrs, so we don't need to make explicit
        // calls to set them.
        controller = new PlaybackControlView(context, attrs);
        controller.setLayoutParams(controllerPlaceholder.getLayoutParams());
        ViewGroup parent = ((ViewGroup) controllerPlaceholder.getParent());
        int controllerIndex = parent.indexOfChild(controllerPlaceholder);
        parent.removeView(controllerPlaceholder);
        parent.addView(controller, controllerIndex);
      }
    }
    this.controller = controller;
    this.controllerShowTimeoutMs = controller != null ? controllerShowTimeoutMs : 0;
    this.useController = useController && controller != null;
    hideController();
  }

  /**
   * Returns the player currently set on this view, or null if no player is set.
   */
  public SimpleExoPlayer getPlayer() {
    return player;
  }

  /**
   * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#setTextOutput} and
   * {@link SimpleExoPlayer#setVideoListener} method of the player will be called and previous
   * assignments are overridden.
   *
   * @param player The {@link SimpleExoPlayer} to use.
   */
  public void setPlayer(SimpleExoPlayer player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.setTextOutput(null);
      this.player.setVideoListener(null);
      this.player.removeListener(componentListener);
      this.player.setVideoSurface(null);
    }
    this.player = player;
    if (useController) {
      controller.setPlayer(player);
    }
    if (shutterView != null) {
      shutterView.setVisibility(VISIBLE);
    }
    if (player != null) {
      if (surfaceView instanceof TextureView) {
        player.setVideoTextureView((TextureView) surfaceView);
      } else if (surfaceView instanceof SurfaceView) {
        player.setVideoSurfaceView((SurfaceView) surfaceView);
      }
      player.setVideoListener(componentListener);
      player.addListener(componentListener);
      player.setTextOutput(componentListener);
      maybeShowController(false);
      updateForCurrentTrackSelections();
    } else {
      hideController();
      hideArtwork();
    }
  }

  /**
   * Sets the resize mode.
   *
   * @param resizeMode The resize mode.
   */
  public void setResizeMode(@ResizeMode int resizeMode) {
    Assertions.checkState(aspectRatioVideoFrame != null);
    aspectRatioVideoFrame.setResizeMode(resizeMode);
  }

  /**
   * Returns whether artwork is displayed if present in the media.
   */
  public boolean getUseArtwork() {
    return useArtwork;
  }

  /**
   * Sets whether artwork is displayed if present in the media.
   *
   * @param useArtwork Whether artwork is displayed.
   */
  public void setUseArtwork(boolean useArtwork) {
    Assertions.checkState(!useArtwork || artworkView != null);
    if (this.useArtwork != useArtwork) {
      this.useArtwork = useArtwork;
      updateForCurrentTrackSelections();
    }
  }

  /**
   * Returns whether the playback controls are enabled.
   */
  public boolean getUseController() {
    return useController;
  }

  /**
   * Sets whether playback controls are enabled. If set to {@code false} the playback controls are
   * never visible and are disconnected from the player.
   *
   * @param useController Whether playback controls should be enabled.
   */
  public void setUseController(boolean useController) {
    Assertions.checkState(!useController || controller != null);
    if (this.useController == useController) {
      return;
    }
    this.useController = useController;
    if (useController) {
      controller.setPlayer(player);
    } else if (controller != null) {
      controller.hide();
      controller.setPlayer(null);
    }
  }

  /**
   * Returns the playback controls timeout. The playback controls are automatically hidden after
   * this duration of time has elapsed without user input and with playback or buffering in
   * progress.
   *
   * @return The timeout in milliseconds. A non-positive value will cause the controller to remain
   *     visible indefinitely.
   */
  public int getControllerShowTimeoutMs() {
    return controllerShowTimeoutMs;
  }

  /**
   * Sets the playback controls timeout. The playback controls are automatically hidden after this
   * duration of time has elapsed without user input and with playback or buffering in progress.
   *
   * @param controllerShowTimeoutMs The timeout in milliseconds. A non-positive value will cause
   *     the controller to remain visible indefinitely.
   */
  public void setControllerShowTimeoutMs(int controllerShowTimeoutMs) {
    Assertions.checkState(controller != null);
    this.controllerShowTimeoutMs = controllerShowTimeoutMs;
  }

  /**
   * Set the {@link PlaybackControlView.VisibilityListener}.
   *
   * @param listener The listener to be notified about visibility changes.
   */
  public void setControllerVisibilityListener(PlaybackControlView.VisibilityListener listener) {
    Assertions.checkState(controller != null);
    controller.setVisibilityListener(listener);
  }

  /**
   * Sets the rewind increment in milliseconds.
   *
   * @param rewindMs The rewind increment in milliseconds.
   */
  public void setRewindIncrementMs(int rewindMs) {
    Assertions.checkState(controller != null);
    controller.setRewindIncrementMs(rewindMs);
  }

  /**
   * Sets the fast forward increment in milliseconds.
   *
   * @param fastForwardMs The fast forward increment in milliseconds.
   */
  public void setFastForwardIncrementMs(int fastForwardMs) {
    Assertions.checkState(controller != null);
    controller.setFastForwardIncrementMs(fastForwardMs);
  }

  /**
   * Get the view onto which video is rendered. This is either a {@link SurfaceView} (default)
   * or a {@link TextureView} if the {@code use_texture_view} view attribute has been set to true.
   *
   * @return either a {@link SurfaceView} or a {@link TextureView}.
   */
  public View getVideoSurfaceView() {
    return surfaceView;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!useController || player == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
      return false;
    }
    if (controller.isVisible()) {
      controller.hide();
    } else {
      maybeShowController(true);
    }
    return true;
  }

  @Override
  public boolean onTrackballEvent(MotionEvent ev) {
    if (!useController || player == null) {
      return false;
    }
    maybeShowController(true);
    return true;
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    return useController ? controller.dispatchKeyEvent(event) : super.dispatchKeyEvent(event);
  }

  private void maybeShowController(boolean isForced) {
    if (!useController || player == null) {
      return;
    }
    int playbackState = player.getPlaybackState();
    boolean showIndefinitely = playbackState == ExoPlayer.STATE_IDLE
        || playbackState == ExoPlayer.STATE_ENDED || !player.getPlayWhenReady();
    boolean wasShowingIndefinitely = controller.isVisible() && controller.getShowTimeoutMs() <= 0;
    controller.setShowTimeoutMs(showIndefinitely ? 0 : controllerShowTimeoutMs);
    if (isForced || showIndefinitely || wasShowingIndefinitely) {
      controller.show();
    }
  }

  private void updateForCurrentTrackSelections() {
    if (player == null) {
      return;
    }
    TrackSelectionArray selections = player.getCurrentTrackSelections();
    for (int i = 0; i < selections.length; i++) {
      if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
        // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
        // onRenderedFirstFrame().
        hideArtwork();
        return;
      }
    }
    // Video disabled so the shutter must be closed.
    if (shutterView != null) {
      shutterView.setVisibility(VISIBLE);
    }
    // Display artwork if enabled and available, else hide it.
    if (useArtwork) {
      for (int i = 0; i < selections.length; i++) {
        TrackSelection selection = selections.get(i);
        if (selection != null) {
          for (int j = 0; j < selection.length(); j++) {
            Metadata metadata = selection.getFormat(j).metadata;
            if (metadata != null) {
              for (int k = 0; k < metadata.length(); k++) {
                Metadata.Entry metadataEntry = metadata.get(k);
                if (metadataEntry instanceof ApicFrame) {
                  byte[] data = ((ApicFrame) metadataEntry).pictureData;;
                  artworkView.setImageBitmap(BitmapFactory.decodeByteArray(data, 0, data.length));
                  artworkView.setVisibility(VISIBLE);
                  return;
                }
              }
            }
          }
        }
      }
    }
    // Artwork disabled or unavailable.
    hideArtwork();
  }

  private void hideArtwork() {
    if (artworkView != null) {
      artworkView.setImageResource(android.R.color.transparent); // Clears any bitmap reference.
      artworkView.setVisibility(INVISIBLE);
    }
  }

  private void hideController() {
    if (controller != null) {
      controller.hide();
    }
  }

  @SuppressWarnings("ResourceType")
  private static void setResizeModeRaw(AspectRatioFrameLayout aspectRatioFrame, int resizeMode) {
    aspectRatioFrame.setResizeMode(resizeMode);
  }

  private final class ComponentListener implements SimpleExoPlayer.VideoListener,
      TextRenderer.Output, ExoPlayer.EventListener {

    // TextRenderer.Output implementation

    @Override
    public void onCues(List<Cue> cues) {
      if (subtitleView != null) {
        subtitleView.onCues(cues);
      }
    }

    // SimpleExoPlayer.VideoListener implementation

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      if (aspectRatioVideoFrame != null) {
        float aspectRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;
        aspectRatioVideoFrame.setAspectRatio(aspectRatio);
      }
    }

    @Override
    public void onRenderedFirstFrame() {
      if (shutterView != null) {
        shutterView.setVisibility(INVISIBLE);
      }
    }

    @Override
    public void onTracksChanged(TrackGroupArray tracks, TrackSelectionArray selections) {
      updateForCurrentTrackSelections();
    }

    // ExoPlayer.EventListener implementation

    @Override
    public void onLoadingChanged(boolean isLoading) {
      // Do nothing.
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      maybeShowController(false);
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
      // Do nothing.
    }

    @Override
    public void onPositionDiscontinuity() {
      // Do nothing.
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
      // Do nothing.
    }

  }

}
