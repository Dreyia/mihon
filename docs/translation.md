# Page translation

Translates manga pages while reading: speech bubbles are detected, the text is recognised,
translated, erased from the page and the translation is drawn back in. Everything is behind a
toggle in the reader's bottom bar and in **Settings › Translation**.

Two engines share one interface (`mihon.feature.translation.core.PageTranslator`):

| Engine | What runs where | Needs |
|---|---|---|
| **On this device** (default) | Detection, OCR, text removal and (optionally) translation run on the phone with [ONNX Runtime](https://onnxruntime.ai/). | ~100–360 MB of model packs, downloaded from Hugging Face on first use. |
| **manga-image-translator server** | The page is uploaded to a self-hosted [manga-image-translator](https://github.com/zyddnys/manga-image-translator) and the finished image is shown. | A PC running the server (Docker image, GPU recommended). |

With the on-device engine the *text translation* step can also be sent to any **OpenAI-compatible
server** (Ollama, LM Studio, llama.cpp, OpenRouter, …). This gives LLM-quality translation on any GPU
vendor while keeping detection/OCR/erasing on the phone.

## Models (on-device)

| Pack | Model | Size | Licence |
|---|---|---|---|
| Text & bubble detector | [ogkalu/comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) `detector-v4-s_int8.onnx` (RT-DETRv2) | 11 MB | Apache-2.0 |
| Japanese OCR | [ogkalu/manga-ocr-mobile](https://huggingface.co/ogkalu/manga-ocr-mobile) (mobile export of [manga-ocr](https://github.com/kha-white/manga-ocr)) | 65 MB | Apache-2.0 |
| Chinese OCR | [PaddlePaddle/PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx) | 21 MB | Apache-2.0 |
| Text eraser | [ogkalu/aot-inpainting](https://huggingface.co/ogkalu/aot-inpainting) `aot.onnx` | 23 MB | MIT |
| JA→EN translator | [onnx-community/opus-mt-ja-en](https://huggingface.co/onnx-community/opus-mt-ja-en) (int8 encoder + merged decoder) | 239 MB | CC-BY-4.0 / Apache-2.0 |
| ZH→EN translator | [onnx-community/opus-mt-zh-en](https://huggingface.co/onnx-community/opus-mt-zh-en) | 252 MB | CC-BY-4.0 / Apache-2.0 |

Model files are stored in the app's private `files/translation_models/<pack>/`; translated pages are
cached in `cache/translated_pages/` (512 MB cap, oldest evicted).

The on-device translator (opus-mt) is a small 2019 model; expect readable but rough English. For
better quality use the OpenAI-compatible option or the server engine.

## Code map

```
app/src/main/java/mihon/feature/translation/
  TranslationPreferences.kt            settings keys
  ReaderPageTranslation.kt             bridge used by the reader; error flow; model downloads
  core/
    TranslationConfig.kt               settings snapshot, cache key, required packs
    TranslationManager.kt              builds/caches the active engine, serialises page work
    ModelCatalog.kt / ModelStore.kt    pack definitions, resumable downloads
    pipeline/OnDevicePageTranslator.kt detect → OCR → translate → inpaint → render
    vision/                            RtDetrTextDetector, MangaOcr, PaddleOcr (+LineSegmenter, CtcDecoder), AotInpainter, FlatFillInpainter
    text/                              UnigramTokenizer, MarianOnnxTranslator, OpenAiCompatibleTranslator
    render/                            TextFitter, BubbleTextRenderer
    server/                            MangaImageTranslatorEngine
    cache/                             PageTranslationCache
```

Reader hook: `PagerPageHolder.process()` / `WebtoonPageHolder.process()` call
`ReaderPageTranslation.translate()` before the existing split/rotate steps when
`ViewerConfig.translatePages` is on. Failures fall back to the original page and are toasted.

## Setting up the server engine (optional)

On the PC (any OS with Docker; see the [project README](https://github.com/zyddnys/manga-image-translator#docker)):

```
docker run -d --name manga-translator -p 8000:8000 --ipc=host \
  zyddnys/manga-image-translator:main shared --host 0.0.0.0 --port 8000
```

Add `--gpus all` for NVIDIA. Then in the app: **Settings › Translation › Translation engine →
manga-image-translator server**, set the **Server URL** (e.g. `http://192.168.1.10:8000`) and the
**Server translator** (`sugoi` for JA→EN offline, or `custom_openai`, `deepl`, `gemini`, … with the
matching API keys set as environment variables on the server).

For the OpenAI-compatible text option with [Ollama](https://ollama.com): run `ollama serve` and
`ollama pull qwen2.5:7b`, then set **Base URL** `http://<pc-ip>:11434/v1` and **Model name**
`qwen2.5:7b`. Ollama needs `OLLAMA_HOST=0.0.0.0` to accept connections from the phone.

## Manual test plan

Prerequisites: a phone on the same network as the PC (for server/LLM tests), a Japanese manga and a
Chinese manhua in the library.

1. **Settings screen** – Open Settings › Translation. Expect: engine, language, on-device, server, LLM
   and storage sections; the model pack list shows sizes and "Not downloaded".
2. **Model download** – Tap Download on the four packs marked `*`. Expect: progress bars, then
   "Installed". Kill the app mid-download and reopen: the download resumes (file keeps `.part`).
3. **Missing models message** – With a pack missing, open a chapter, enable the Translate toggle
   (bottom bar). Expect: toast "Translation models are not downloaded…" and the original pages.
4. **Japanese page, on-device** – All packs installed, source language Japanese, text translator
   "On-device model". Open a chapter and toggle Translate. Expect: after a few seconds per page the
   bubbles show English text; original Japanese is erased; artwork outside bubbles untouched.
5. **Cache** – Swipe back to a translated page, or reopen the chapter. Expect: instant.
6. **Toggle off** – Tap Translate again. Expect: original pages return immediately.
7. **Split wide pages** – Enable "Split wide pages" and translate a double-page spread. Expect: both
   halves translated and consistent (translation happens before the split).
8. **Webtoon mode** – Switch reading mode to Webtoon on a long strip. Expect: text drawn correctly on
   each segment; no crash on tall images.
9. **Flat fill** – Set Text removal → Flat fill, clear translated pages, re-translate. Expect: text
   boxes filled with the bubble colour (no inpainting model needed).
10. **LLM translator** – Text translator → OpenAI-compatible server, set Ollama URL/model. Expect:
    better English; with a wrong URL, toast "Translation failed: Could not reach LLM endpoint…".
11. **Chinese** – Source language Chinese, Chinese OCR + ZH→EN packs installed, open a manhua.
    Expect: horizontal bubbles translated; vertical text is experimental (see assumptions).
12. **Server engine** – Translation engine → server, URL set. Expect: translated PNG pages from the
    server; with the server down, toast "Could not reach manga-image-translator…".
13. **Memory** – Translate 20+ pages in a row and check no OOM crash (watch logcat for
    `Page translation failed`).
14. **Clear cache** – Settings › Translation › Clear translated pages. Expect: size resets to 0 B.

## Assumptions to verify on a device

Marked `ASSUMPTION` in code comments as well:

- Tensor names/shapes of the mobile manga-ocr export (`decoder_init`/`decoder_step`, position ids
  starting at 2, 128 positions) follow the only public Android port that runs these files
  ([TachiyomiATVibe `MangaOcrEngine`](https://github.com/vetmohamed-arch/TachiyomiATVibe/blob/main/app/src/main/java/eu/kanade/translation/engines/vision/ocr/MangaOcrEngine.kt));
  they could not be executed in the development sandbox (no model downloads allowed).
- The detector export includes post-processing (`orig_target_sizes` input, `labels/boxes/scores`
  outputs). A raw-export fallback (logits + normalised boxes) is implemented but untested.
- PP-OCR input channel order RGB (the reference port uses RGB; PaddleOCR's own pipeline decodes BGR).
- `LineSegmenter` heuristics (vertical ratio 1.3, gap merging) are untested on real pages.
- `<unk>` id 1 is used for the opus-mt tokenizer even though its `tokenizer.json` says `unk_id: 2`.
