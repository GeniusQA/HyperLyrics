package com.genius.hyperlyrics.root.utils

/**
 * 诊断日志文本处理：折叠空白并截断长度，避免超长标题/包名把日志冲爆。
 *
 * DisplayDiagnosticLogger 与 MediaCardDiagnosticLogger 此前各有一份实现，现统一在此。
 */
internal object DiagnosticText {
    internal const val MAX_TEXT_CHARS = 80
    private val WHITESPACE = Regex("\\s+")

    /**
     * @param replaceComma 是否把 `,` 替换成 `;`。以逗号拼接字段的日志需要开启，
     *                     否则文本里的逗号会破坏字段边界。
     */
    fun sanitize(value: Any?, replaceComma: Boolean = false): String {
        val text = value?.toString()
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            .orEmpty()
        return if (replaceComma) text.replace(',', ';') else text
    }
}
