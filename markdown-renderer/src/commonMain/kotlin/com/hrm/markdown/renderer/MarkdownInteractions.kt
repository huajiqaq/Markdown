package com.hrm.markdown.renderer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/** Typed host actions. Replacing handlers does not reparse or remeasure Markdown. */
@Immutable
data class MarkdownInteractions(
    val onLinkClick: ((String) -> Unit)? = null,
    val onCitationClick: ((MarkdownCitationClickEvent) -> Unit)? = null,
    val onImageClick: ((MarkdownImageClickEvent) -> Unit)? = null,
) {
    companion object {
        val Default = MarkdownInteractions()
    }
}

/** Identifies an occurrence within the current rendered document, not a persistent database ID. */
@Immutable
data class MarkdownCitationClickEvent(
    val key: String,
    val occurrenceId: Long,
    val documentRevision: Long,
    /** Window coordinates in pixels; null if geometry is unavailable (e.g. accessibility). */
    val anchorBoundsInWindow: Rect?,
)

@Immutable
data class MarkdownImageOccurrence(val occurrenceId: Long, val image: MarkdownImageData)

data class MarkdownImageGallery(
    val images: List<MarkdownImageOccurrence>,
    val initialIndex: Int,
)

/** Image loading and preview UI remain owned by the host. The gallery is built only on demand. */
class MarkdownImageClickEvent internal constructor(
    val image: MarkdownImageData,
    val occurrenceId: Long,
    val documentRevision: Long,
    val anchorBoundsInWindow: Rect?,
    gallery: () -> MarkdownImageGallery,
) {
    /** A document-order snapshot, including offscreen images and repeated URLs. */
    val gallery: MarkdownImageGallery by lazy(gallery)
}
