package com.danielealbano.androidremotecontrolmcp.services.controlbar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Human, present-progressive caption for one wire `op`/`params` pair - `read_screen` is
 *  deliberately never passed here (plan 71's "captions update on action ops only"). [appLabel]
 *  resolves a package name to its launcher label, falling back to the package name itself. */
fun stepCaption(
    op: String,
    params: JsonObject,
    appLabel: (String) -> String,
): String =
    when (op) {
        "tap" -> tapCaption(params)
        "type_text" -> typeTextCaption(params)
        "scroll_find" -> "Looking for \"${selector(params)?.let(::selectorLabel) ?: "the target"}\"…"
        "key" -> keyCaption(params)
        "launch_app" -> launchAppCaption(params, appLabel)
        "wait_until" -> "Waiting for \"${selector(params)?.let(::selectorLabel) ?: "the target"}\"…"
        else -> "Working…"
    }

private fun selector(params: JsonObject): JsonObject? = params["selector"]?.jsonObject

private fun tapCaption(params: JsonObject): String {
    val selector = selector(params) ?: return "Tapping the screen…"
    return "Tapping \"${selectorLabel(selector)}\"…"
}

private fun typeTextCaption(params: JsonObject): String {
    if (params["clear"]?.jsonPrimitive?.booleanOrNull == true) return "Clearing the field…"
    val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val truncated = text.length > MAX_CAPTION_TEXT_LEN
    return "Typing \"${text.take(MAX_CAPTION_TEXT_LEN)}${if (truncated) "…" else ""}\"…"
}

private fun keyCaption(params: JsonObject): String =
    when (params["key"]?.jsonPrimitive?.contentOrNull) {
        "back" -> "Pressing back…"
        "home" -> "Going home…"
        "recents" -> "Viewing recent apps…"
        "dismiss_keyboard" -> "Dismissing the keyboard…"
        else -> "Pressing a key…"
    }

private fun launchAppCaption(
    params: JsonObject,
    appLabel: (String) -> String,
): String {
    val pkg = params["package"]?.jsonPrimitive?.contentOrNull
    return "Opening ${pkg?.let(appLabel) ?: "an app"}…"
}

/** Prefers the most human-readable selector field present: text, then content_desc, then
 *  resource_id, then class_name - matches how a person would describe the element. */
private fun selectorLabel(selector: JsonObject): String =
    selector["text"]?.jsonPrimitive?.contentOrNull
        ?: selector["content_desc"]?.jsonPrimitive?.contentOrNull
        ?: selector["resource_id"]?.jsonPrimitive?.contentOrNull
        ?: selector["class_name"]?.jsonPrimitive?.contentOrNull
        ?: "the element"

private const val MAX_CAPTION_TEXT_LEN = 24
