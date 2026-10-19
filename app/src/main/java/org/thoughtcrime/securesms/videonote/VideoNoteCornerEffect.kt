/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.videonote

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Bakes a "round video" look into the pixels of a video note so that clients which don't crop video notes to a circle
 * still see one: the inscribed circle keeps the original frame untouched, while the corners show a zoomed-in, heavily
 * blurred and dimmed copy of the same frame, with an anti-aliased edge and a faint darker ring around the circle.
 *
 * Media3 converts the decoder's external OES texture into an internal 2D texture in the first stage of the video frame
 * processing pipeline, so custom [GlEffect]s like this one always sample a `sampler2D`.
 */
@OptIn(markerClass = [UnstableApi::class])
class VideoNoteCornerEffect : GlEffect {

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return ShaderProgram(useHdr)
  }

  private class ShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram = try {
      GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

    private var width = 0
    private var height = 0

    init {
      val identity = GlUtil.create4x4IdentityMatrix()
      glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
      glProgram.setFloatsUniform("uTransformationMatrix", identity)
      glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
      width = inputWidth
      height = inputHeight
      return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
      try {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
      } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e, presentationTimeUs)
      }
    }

    override fun release() {
      super.release()
      try {
        glProgram.delete()
      } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
      }
    }
  }

  private companion object {
    private const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

    /**
     * Inside the circle: the original sample. Outside: a 64-tap golden-angle spiral blur (radius ~5% of the frame) of the
     * frame zoomed 1.35x about its center, multiplied by 0.55. The two are mixed with a 1.5px smoothstep just outside the
     * radius, and a ~2px ring outside the edge is darkened a little further for definition.
     */
    private const val FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uSize;
varying vec2 vTexSamplingCoord;

const float ZOOM = 1.35;
const float DIM = 0.55;
const float BLUR_RADIUS = 0.05;
const float EDGE_WIDTH = 1.5;
const float RING_STRENGTH = 0.18;
const int TAPS = 64;
const float GOLDEN_ANGLE = 2.39996323;

vec3 blurredCorner(vec2 uv) {
  vec2 zoomed = (uv - 0.5) / ZOOM + 0.5;
  float minSide = min(uSize.x, uSize.y);
  vec2 radius = vec2(BLUR_RADIUS * minSide) / uSize;
  vec3 sum = vec3(0.0);
  float weightSum = 0.0;
  for (int i = 0; i < TAPS; i++) {
    float fi = float(i) + 0.5;
    float r = sqrt(fi / float(TAPS));
    float a = fi * GOLDEN_ANGLE;
    vec2 offset = vec2(cos(a), sin(a)) * r * radius;
    // Soft falloff so the blur reads as gaussian-ish rather than a hard disk.
    float w = 1.0 - 0.6 * r * r;
    sum += texture2D(uTexSampler, clamp(zoomed + offset, 0.0, 1.0)).rgb * w;
    weightSum += w;
  }
  return sum / weightSum;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 original = texture2D(uTexSampler, uv);
  vec2 p = (uv - 0.5) * uSize;
  float d = length(p);
  float circleRadius = 0.5 * min(uSize.x, uSize.y);

  if (d <= circleRadius) {
    gl_FragColor = original;
    return;
  }

  float outside = smoothstep(circleRadius, circleRadius + EDGE_WIDTH, d);
  float ring = 1.0 - smoothstep(circleRadius + EDGE_WIDTH, circleRadius + EDGE_WIDTH + 2.0, d);
  vec3 corner = blurredCorner(uv) * DIM * (1.0 - RING_STRENGTH * ring);
  gl_FragColor = vec4(mix(original.rgb, corner, outside), 1.0);
}
"""
  }
}
