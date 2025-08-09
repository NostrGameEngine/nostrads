/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostrads.protocol.types;

import jakarta.annotation.Nullable;

/**
 * Standard aspect ratios for Ad content
 */
public enum AdAspectRatio {
    RATIO_8_1("8:1", 8f / 1f),
    RATIO_1_2("1:2", 1 / 2f),
    RATIO_1_4("1:4", 1 / 4f),
    RATIO_1_1("1:1", 1f / 1f),
    RATIO_2_1("2:1", 2f / 1f),
    RATIO_4_1("4:1", 4f / 1f);

    private final String stringValue;
    private final float floatValue;

    AdAspectRatio(String stringValue, float floatValue) {
        this.stringValue = stringValue;
        this.floatValue = floatValue;
    }

    /**
     * Get aspect ratio in W:H format
     */
    public String getStringValue() {
        return stringValue;
    }

    /**
     * Get the calculated aspect ratio as a float
     */
    public float getFloatValue() {
        return floatValue;
    }

    /**
     * Find aspect ratio from string (e.g. "16:9")
     */
    @Nullable
    public static AdAspectRatio fromString(String aspectRatio) {
        for (AdAspectRatio ratio : values()) {
            if (ratio.stringValue.equalsIgnoreCase(aspectRatio)) {
                return ratio;
            }
        }
        return null;
    }

    /**
     * Find the closest aspect ratio
     */
    public static AdAspectRatio findClosest(float ratio) {
        AdAspectRatio closest = RATIO_1_1;
        float minDiff = Math.abs(closest.floatValue - ratio);

        for (AdAspectRatio aspectRatio : values()) {
            float diff = Math.abs(aspectRatio.floatValue - ratio);
            if (diff < minDiff) {
                minDiff = diff;
                closest = aspectRatio;
            }
        }
        return closest;
    }

    /**
     * Calculate aspect ratio from width and height
     */
    public static AdAspectRatio fromDimensions(int width, int height) {
        if (height == 0) {
            throw new IllegalArgumentException("Height cannot be zero");
        }
        return findClosest((float) width / (float) height);
    }

    @Override
    public String toString() {
        return stringValue;
    }
}
