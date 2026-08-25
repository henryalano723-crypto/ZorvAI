package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuroAsrModelsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun touch(dir: File, name: String) {
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }

    @Test
    fun detectsSenseVoiceOnnx() {
        val dir = temp.newFolder("sherpa-onnx-sense-voice-int8")
        touch(dir, "model.int8.onnx")
        touch(dir, "tokens.txt")
        assertEquals(AsrModelLayout.ONNX_SENSE_VOICE, detectAsrLayout(dir))
    }

    @Test
    fun detectsStreamingParaformerOnnxInNestedDirectory() {
        val root = temp.newFolder("archive")
        val dir = File(root, "sherpa-onnx-streaming-paraformer-bilingual-zh-en").apply { mkdirs() }
        touch(dir, "encoder.int8.onnx")
        touch(dir, "decoder.int8.onnx")
        touch(dir, "tokens.txt")
        assertEquals(AsrModelLayout.ONNX_STREAMING_PARAFORMER, detectAsrLayout(root))
    }

    @Test
    fun detectsLegacyNcnnTransducerBeforeLegacySenseVoice() {
        val dir = temp.newFolder("zipformer")
        listOf("encoder", "decoder", "joiner").forEach { role ->
            touch(dir, "$role.ncnn.param")
            touch(dir, "$role.ncnn.bin")
        }
        touch(dir, "tokens.txt")
        assertEquals(AsrModelLayout.TRANSDUCER, detectAsrLayout(dir))
    }

    @Test
    fun rejectsUnknownOnnxLayoutAsLegacy() {
        val dir = temp.newFolder("unknown")
        touch(dir, "mystery.onnx")
        assertEquals(AsrModelLayout.ONNX_LEGACY, detectAsrLayout(dir))
    }
}
