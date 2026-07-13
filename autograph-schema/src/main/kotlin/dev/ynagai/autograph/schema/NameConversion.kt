package dev.ynagai.autograph.schema

private val WORD_BOUNDARY = Regex("[^A-Za-z0-9]+")

private fun String.words(): List<String> = split(WORD_BOUNDARY).filter { it.isNotEmpty() }

/** `"Recipe Saved"` -> `"RecipeSaved"`. */
internal fun String.toPascalCase(): String = words().joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

/** `"Recipe Saved"` -> `"recipeSaved"`. */
internal fun String.toCamelCase(): String = toPascalCase().replaceFirstChar(Char::lowercaseChar)

/** Escapes a string for embedding as a Kotlin string-literal body (between the quotes). */
internal fun String.escapeKotlinStringLiteral(): String = replace("\\", "\\\\").replace("\"", "\\\"")
