package dev.ynagai.autograph.schema

private val WORD_BOUNDARY = Regex("[^A-Za-z0-9]+")

private fun String.words(): List<String> = split(WORD_BOUNDARY).filter { it.isNotEmpty() }

/** `"Recipe Saved"` -> `"RecipeSaved"`. */
internal fun String.toPascalCase(): String = words().joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

/** `"Recipe Saved"` -> `"recipeSaved"`. */
internal fun String.toCamelCase(): String = toPascalCase().replaceFirstChar(Char::lowercaseChar)

/**
 * Escapes a string for embedding as a Kotlin string-literal body (between the quotes) —
 * including `$`, which would otherwise be parsed as string-template interpolation.
 */
internal fun String.escapeKotlinStringLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

// Kotlin hard keywords (kotlinlang.org/docs/keyword-reference.html#hard-keywords) — these, unlike
// soft/modifier keywords, are never valid as a plain identifier and MUST be backtick-escaped.
private val KOTLIN_HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
)

/**
 * Converts [this] property name into a valid Kotlin parameter identifier: `toCamelCase()`,
 * backtick-escaped if the result collides with a Kotlin hard keyword or starts with a digit
 * (neither is a legal plain identifier). Blank input (no alphanumeric characters at all) has no
 * valid identifier to produce and is the caller's responsibility to reject before calling this.
 */
internal fun String.toKotlinParameterName(): String {
    val camel = toCamelCase()
    require(camel.isNotEmpty()) { "\"$this\" contains no alphanumeric characters to derive a parameter name from" }
    return if (camel[0].isDigit() || camel in KOTLIN_HARD_KEYWORDS) "`$camel`" else camel
}
