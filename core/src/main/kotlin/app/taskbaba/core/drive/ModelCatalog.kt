package app.taskbaba.core.drive

/**
 * Open-weight models the Colab notebook knows how to install into Google Drive. Values are pinned
 * to an exact Hugging Face revision and verified against the repository's LFS SHA-256.
 * The app never downloads these files to the Mac.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val notes: String,
) {
    val sourceUrl: String get() = "https://huggingface.co/$repo/blob/$revision/$fileName"
    val sizeLabel: String get() = "%.2f GB".format(sizeBytes / 1_000_000_000.0)
}

object ModelCatalog {
    val QWEN3_4B_Q4KM = ModelSpec(
        id = "qwen3-4b-q4km",
        displayName = "Qwen3 4B · Q4_K_M",
        repo = "Qwen/Qwen3-4B-GGUF",
        revision = "bc640142c66e1fdd12af0bd68f40445458f3869b",
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        sizeBytes = 2_497_280_256,
        sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/blob/main/LICENSE",
        notes = "Default. Fits a free Colab T4 GPU (16 GB) with room to spare; ~1–2 s per short line.",
    )

    val QWEN3_1_7B_Q8 = ModelSpec(
        id = "qwen3-1.7b-q8",
        displayName = "Qwen3 1.7B · Q8_0",
        repo = "Qwen/Qwen3-1.7B-GGUF",
        revision = "90862c4b9d2787eaed51d12237eafdfe7c5f6077",
        fileName = "Qwen3-1.7B-Q8_0.gguf",
        sizeBytes = 1_834_426_016,
        sha256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/blob/main/LICENSE",
        notes = "Lighter option: faster and usable on a CPU-only Colab runtime, slightly less witty.",
    )

    val all = listOf(QWEN3_4B_Q4KM, QWEN3_1_7B_Q8)
    val DEFAULT = QWEN3_4B_Q4KM

    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id } ?: DEFAULT
    fun byFileName(name: String): ModelSpec? = all.firstOrNull { it.fileName == name }
}
