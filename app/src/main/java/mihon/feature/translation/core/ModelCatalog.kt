package mihon.feature.translation.core

/**
 * One downloadable file of a [ModelPack].
 *
 * @param expectedSize size in bytes as listed on Hugging Face; used to verify the download.
 */
data class ModelFile(
    val fileName: String,
    val url: String,
    val expectedSize: Long,
)

private fun hf(repo: String, path: String) = "https://huggingface.co/$repo/resolve/main/$path"

/**
 * Group of files that is downloaded, listed and deleted as one unit.
 *
 * Sizes and licences were read from the Hugging Face repositories on 2026-10-26.
 */
enum class ModelPack(
    val id: String,
    val displayName: String,
    val license: String,
    val sourceUrl: String,
    val files: List<ModelFile>,
) {
    DETECTOR(
        id = "detector",
        displayName = "Text & bubble detector (RT-DETRv2)",
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector",
        files = listOf(
            ModelFile(
                "detector.onnx",
                hf("ogkalu/comic-text-and-bubble-detector", "detector-v4-s_int8.onnx"),
                11_120_765,
            ),
        ),
    ),
    OCR_JAPANESE(
        id = "ocr_ja",
        displayName = "Japanese OCR (manga-ocr mobile)",
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/ogkalu/manga-ocr-mobile",
        files = listOf(
            ModelFile("encoder.onnx", hf("ogkalu/manga-ocr-mobile", "encoder.onnx"), 17_070_003),
            ModelFile("decoder_init.onnx", hf("ogkalu/manga-ocr-mobile", "decoder_init.onnx"), 24_875_052),
            ModelFile("decoder_step.onnx", hf("ogkalu/manga-ocr-mobile", "decoder_step.onnx"), 22_776_797),
            ModelFile("vocab.txt", hf("ogkalu/manga-ocr-mobile", "vocab.txt"), 37_193),
        ),
    ),
    OCR_CHINESE(
        id = "ocr_zh",
        displayName = "Chinese OCR (PP-OCRv6 small)",
        license = "Apache-2.0",
        sourceUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx",
        files = listOf(
            ModelFile("rec.onnx", hf("PaddlePaddle/PP-OCRv6_small_rec_onnx", "inference.onnx"), 21_159_378),
            ModelFile("rec.yml", hf("PaddlePaddle/PP-OCRv6_small_rec_onnx", "inference.yml"), 150_579),
        ),
    ),
    INPAINTING(
        id = "inpaint",
        displayName = "Text eraser (AOT inpainting)",
        license = "MIT",
        sourceUrl = "https://huggingface.co/ogkalu/aot-inpainting",
        files = listOf(
            ModelFile("aot.onnx", hf("ogkalu/aot-inpainting", "aot.onnx"), 23_068_213),
        ),
    ),
    TRANSLATE_JA_EN(
        id = "mt_ja_en",
        displayName = "Japanese → English translator (opus-mt)",
        license = "CC-BY-4.0 (weights), Apache-2.0 (original model)",
        sourceUrl = "https://huggingface.co/onnx-community/opus-mt-ja-en",
        files = listOf(
            ModelFile(
                "encoder.onnx",
                hf("onnx-community/opus-mt-ja-en", "onnx/encoder_model_int8.onnx"),
                50_681_158,
            ),
            ModelFile(
                "decoder.onnx",
                hf("onnx-community/opus-mt-ja-en", "onnx/decoder_model_merged_int8.onnx"),
                182_303_484,
            ),
            ModelFile("tokenizer.json", hf("onnx-community/opus-mt-ja-en", "tokenizer.json"), 5_991_485),
        ),
    ),
    TRANSLATE_ZH_EN(
        id = "mt_zh_en",
        displayName = "Chinese → English translator (opus-mt)",
        license = "CC-BY-4.0 (weights), Apache-2.0 (original model)",
        sourceUrl = "https://huggingface.co/onnx-community/opus-mt-zh-en",
        files = listOf(
            ModelFile(
                "encoder.onnx",
                hf("onnx-community/opus-mt-zh-en", "onnx/encoder_model_int8.onnx"),
                52_875_078,
            ),
            ModelFile(
                "decoder.onnx",
                hf("onnx-community/opus-mt-zh-en", "onnx/decoder_model_merged_int8.onnx"),
                193_290_224,
            ),
            ModelFile("tokenizer.json", hf("onnx-community/opus-mt-zh-en", "tokenizer.json"), 6_381_339),
        ),
    ),
    ;

    val totalSize: Long get() = files.sumOf { it.expectedSize }

    companion object {
        fun byId(id: String): ModelPack? = entries.firstOrNull { it.id == id }
    }
}
