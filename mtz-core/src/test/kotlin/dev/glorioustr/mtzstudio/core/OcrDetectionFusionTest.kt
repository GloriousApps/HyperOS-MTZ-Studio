package dev.glorioustr.mtzstudio.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrDetectionFusionTest {
    @Test
    fun `keeps complementary detections from different engines`() {
        val fused = OcrDetectionFusion.fuse(
            listOf(detection("添加", 0, 0, 60, 20, .91f)),
            listOf(detection("小组件", 70, 0, 145, 20, .82f)),
        )

        assertEquals(listOf("添加", "小组件"), fused.map { it.text })
    }

    @Test
    fun `deduplicates matching detections and rewards engine agreement`() {
        val fused = OcrDetectionFusion.fuse(
            listOf(detection("添加小组件", 10, 10, 120, 36, .76f)),
            listOf(detection("添加小组件", 12, 11, 121, 37, .74f)),
        )

        assertEquals(1, fused.size)
        assertEquals("添加小组件", fused.single().text)
        assertEquals(.81f, fused.single().confidence)
    }

    @Test
    fun `chooses stronger text when overlapping engines disagree`() {
        val fused = OcrDetectionFusion.fuse(
            listOf(detection("左侧组件", 10, 10, 110, 36, .61f)),
            listOf(detection("左侧组", 12, 10, 109, 36, .89f)),
        )

        assertEquals(listOf("左侧组"), fused.map { it.text })
    }

    private fun detection(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float,
    ) = OcrDetectionFusion.Detection(
        text = text,
        bounds = OcrDetectionFusion.Bounds(left, top, right, bottom),
        confidence = confidence,
    )
}
