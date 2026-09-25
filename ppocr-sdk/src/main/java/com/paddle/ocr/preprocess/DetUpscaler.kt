// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.preprocess

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil

object DetUpscaler {
    // Theme UI artwork carries glyphs far smaller than a document scan. The DB
    // detector needs roughly 32px of text height to fire reliably, so the target
    // is set well above that and the cap allows the smallest assets to reach it.
    fun upscaleIfSmall(src: Mat, minTextHeightPx: Int = 48, maxScale: Int = 4): Mat {
        val scale = scaleFor(src, minTextHeightPx, maxScale)
        if (scale == 1) return src
        val dst = Mat()
        Imgproc.resize(src, dst, Size(src.cols() * scale.toDouble(), src.rows() * scale.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        return dst
    }

    fun scaleFor(src: Mat, minTextHeightPx: Int = 48, maxScale: Int = 4): Int {
        val minDim = minOf(src.rows(), src.cols())
        val target = minTextHeightPx * 4
        if (minDim >= target) return 1
        val scale = ceil(target.toDouble() / minDim).toInt().coerceIn(1, maxScale)
        return scale
    }
}
