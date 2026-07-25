package com.apricotiris.IrisShot.core.export

import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedSide

internal data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun offset(dx: Int, dy: Int): PixelRect = PixelRect(
        left = Math.addExact(left, dx),
        top = Math.addExact(top, dy),
        right = Math.addExact(right, dx),
        bottom = Math.addExact(bottom, dy),
    )
}

internal data class PlannedCompositionSpan(
    val sourceId: String,
    val source: PixelRect,
    val destination: PixelRect,
)

internal data class PlannedComposition(
    val width: Int,
    val height: Int,
    val spans: List<PlannedCompositionSpan>,
)

internal data class FixedBandSource(
    val sourceId: String,
    val side: FixedSide,
    val width: Int,
    val height: Int,
    val anchorCount: Int,
    val repeatRegion: PixelRect?,
    val anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    /** Insets removed before an old full-length parallel band is repeated. */
    val repeatSourceLeadingInset: Int = 0,
    val repeatSourceTrailingInset: Int = 0,
    /** Corner lengths owned by an end cap when the adjacent bands are included. */
    val leadingCornerExtent: Int = 0,
    val trailingCornerExtent: Int = 0,
)

internal fun capturedFixedBandSource(
    layer: CapturedFixedLayer,
    actualWidth: Int,
    actualHeight: Int,
    repeatSourceLeadingInset: Int = 0,
    repeatSourceTrailingInset: Int = 0,
    leadingCornerExtent: Int = 0,
    trailingCornerExtent: Int = 0,
): FixedBandSource = FixedBandSource(
    sourceId = layer.bitmapPath,
    side = layer.side,
    width = actualWidth,
    height = actualHeight,
    anchorCount = layer.anchorCount.coerceIn(1, 2),
    anchorPosition = layer.anchorPosition,
    repeatRegion = layer.repeatRegion?.let { repeat ->
        PixelRect(repeat.left, repeat.top, repeat.right, repeat.bottom)
    },
    repeatSourceLeadingInset = repeatSourceLeadingInset,
    repeatSourceTrailingInset = repeatSourceTrailingInset,
    leadingCornerExtent = leadingCornerExtent,
    trailingCornerExtent = trailingCornerExtent,
)

/** Builds a 1:1 composition for scroll content surrounded by physical fixed bands. */
internal object FixedBandCompositionPlanner {
    fun build(
        horizontalScroll: Boolean,
        scrollWidth: Int,
        scrollHeight: Int,
        scrollSpans: List<PlannedCompositionSpan>,
        fixedBands: List<FixedBandSource>,
    ): PlannedComposition {
        require(scrollWidth > 0 && scrollHeight > 0)
        require(fixedBands.map(FixedBandSource::side).distinct().size == fixedBands.size) {
            "Only one fixed band is allowed on each physical side"
        }
        fixedBands.forEach { band ->
            require(band.width > 0 && band.height > 0)
            require(
                band.repeatSourceLeadingInset >= 0 && band.repeatSourceTrailingInset >= 0 &&
                    band.leadingCornerExtent >= 0 && band.trailingCornerExtent >= 0,
            ) { "Fixed band insets must not be negative" }
            val axisLength = band.axisLength()
            require(
                band.repeatSourceLeadingInset + band.repeatSourceTrailingInset < axisLength,
            ) { "The repeat source insets consume the complete fixed band" }
            require(band.leadingCornerExtent + band.trailingCornerExtent < axisLength) {
                "The fixed corner extents consume the complete end cap"
            }
        }

        val bySide = fixedBands.associateBy(FixedBandSource::side)
        val leftWidth = bySide[FixedSide.LEFT]?.width ?: 0
        val rightWidth = bySide[FixedSide.RIGHT]?.width ?: 0
        val topHeight = bySide[FixedSide.TOP]?.height ?: 0
        val bottomHeight = bySide[FixedSide.BOTTOM]?.height ?: 0
        val outputWidth = addDimensions(leftWidth, scrollWidth, rightWidth)
        val outputHeight = addDimensions(topHeight, scrollHeight, bottomHeight)
        val spans = ArrayList<PlannedCompositionSpan>(scrollSpans.size + fixedBands.size * 4)

        scrollSpans.forEach { span ->
            require(!span.source.isEmpty && !span.destination.isEmpty)
            require(
                span.destination.left >= 0 && span.destination.top >= 0 &&
                    span.destination.right <= scrollWidth && span.destination.bottom <= scrollHeight,
            ) { "Scroll span falls outside the logical scroll layer" }
            spans += span.copy(destination = span.destination.offset(leftWidth, topHeight))
        }

        fixedBands.forEach { band ->
            val parallelToScroll = when (band.side) {
                FixedSide.TOP, FixedSide.BOTTOM -> horizontalScroll
                FixedSide.LEFT, FixedSide.RIGHT -> !horizontalScroll
            }
            if (parallelToScroll) {
                val targetLength = if (horizontalScroll) scrollWidth else scrollHeight
                val crossOffset = when (band.side) {
                    FixedSide.TOP -> 0
                    FixedSide.BOTTOM -> outputHeight - band.height
                    FixedSide.LEFT -> 0
                    FixedSide.RIGHT -> outputWidth - band.width
                }
                val mainOffset = if (horizontalScroll) leftWidth else topHeight
                appendRepeatedBand(
                    spans = spans,
                    band = band,
                    horizontal = horizontalScroll,
                    targetLength = targetLength,
                    mainOffset = mainOffset,
                    crossOffset = crossOffset,
                )
            } else {
                spans += endCapSpan(
                    band = band,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    includeLeadingCorner = when (band.side) {
                        FixedSide.TOP, FixedSide.BOTTOM -> FixedSide.LEFT in bySide
                        FixedSide.LEFT, FixedSide.RIGHT -> FixedSide.TOP in bySide
                    },
                    includeTrailingCorner = when (band.side) {
                        FixedSide.TOP, FixedSide.BOTTOM -> FixedSide.RIGHT in bySide
                        FixedSide.LEFT, FixedSide.RIGHT -> FixedSide.BOTTOM in bySide
                    },
                )
            }
        }
        return PlannedComposition(outputWidth, outputHeight, spans)
    }

    private fun appendRepeatedBand(
        spans: ArrayList<PlannedCompositionSpan>,
        band: FixedBandSource,
        horizontal: Boolean,
        targetLength: Int,
        mainOffset: Int,
        crossOffset: Int,
    ) {
        val fullSourceLength = if (horizontal) band.width else band.height
        val sourceStart = band.repeatSourceLeadingInset
        val sourceEnd = fullSourceLength - band.repeatSourceTrailingInset
        val anchorCount = band.anchorCount.coerceIn(1, 2)
        val singleTrailingAnchor = anchorCount == 1 &&
            band.anchorPosition == FixedAnchorPosition.TRAILING
        val repeat = normalizedRepeat(
            band = band,
            horizontal = horizontal,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
            anchorCount = anchorCount,
            singleTrailingAnchor = singleTrailingAnchor,
        )
        val repeatStart = if (horizontal) repeat.left else repeat.top
        val repeatEnd = if (horizontal) repeat.right else repeat.bottom
        val repeatLength = repeatEnd - repeatStart
        val leadingLength = if (singleTrailingAnchor) 0 else repeatStart - sourceStart
        val trailingLength = if (anchorCount == 2) {
            sourceEnd - repeatEnd
        } else if (singleTrailingAnchor) {
            sourceEnd - repeatEnd
        } else {
            0
        }
        if (!singleTrailingAnchor) {
            require(leadingLength > 0) { "The leading fixed block must not be empty" }
        }
        if (anchorCount == 2 || singleTrailingAnchor) {
            require(trailingLength > 0) { "The trailing fixed block must not be empty" }
        }
        require(targetLength >= leadingLength + trailingLength) {
            "The output is shorter than the fixed blocks (${leadingLength + trailingLength}px)"
        }
        val repeatedLength = targetLength - leadingLength - trailingLength
        val repeatedSpanCount = (
            (repeatedLength.toLong() + repeatLength - 1L) / repeatLength
            ).toInt()
        val additionalSpans = repeatedSpanCount +
            (if (leadingLength > 0) 1 else 0) +
            (if (trailingLength > 0) 1 else 0)
        spans.ensureCapacity(Math.addExact(spans.size, additionalSpans))

        if (leadingLength > 0) {
            spans += axisSpan(
                band = band,
                horizontal = horizontal,
                sourceStart = sourceStart,
                length = leadingLength,
                destinationStart = mainOffset,
                crossOffset = crossOffset,
            )
        }
        var completed = 0
        while (completed < repeatedLength) {
            val length = minOf(repeatLength, repeatedLength - completed)
            spans += axisSpan(
                band = band,
                horizontal = horizontal,
                sourceStart = repeatStart,
                length = length,
                destinationStart = mainOffset + leadingLength + completed,
                crossOffset = crossOffset,
            )
            completed += length
        }
        if (trailingLength > 0) {
            spans += axisSpan(
                band = band,
                horizontal = horizontal,
                sourceStart = sourceEnd - trailingLength,
                length = trailingLength,
                destinationStart = mainOffset + targetLength - trailingLength,
                crossOffset = crossOffset,
            )
        }
    }

    private fun normalizedRepeat(
        band: FixedBandSource,
        horizontal: Boolean,
        sourceStart: Int,
        sourceEnd: Int,
        anchorCount: Int,
        singleTrailingAnchor: Boolean,
    ): PixelRect {
        val requested = band.repeatRegion
        val leftRange = if (horizontal) sourceStart..sourceEnd else 0..band.width
        val topRange = if (horizontal) 0..band.height else sourceStart..sourceEnd
        val left = requested?.left?.coerceIn(leftRange) ?: leftRange.first
        val top = requested?.top?.coerceIn(topRange) ?: topRange.first
        val right = requested?.right?.coerceIn(left, leftRange.last) ?: leftRange.last
        val bottom = requested?.bottom?.coerceIn(top, topRange.last) ?: topRange.last
        val normalized = PixelRect(left, top, right, bottom)
        val repeatStart = if (horizontal) normalized.left else normalized.top
        val repeatEnd = if (horizontal) normalized.right else normalized.bottom
        val hasRequiredAnchors = when {
            anchorCount >= 2 -> repeatStart > sourceStart && repeatEnd < sourceEnd
            singleTrailingAnchor -> repeatEnd < sourceEnd
            else -> repeatStart > sourceStart
        }
        val valid = repeatEnd > repeatStart && hasRequiredAnchors
        return if (valid) {
            normalized
        } else {
            defaultRepeatAfterSourceCrop(
                band = band,
                horizontal = horizontal,
                sourceStart = sourceStart,
                sourceEnd = sourceEnd,
                anchorCount = anchorCount,
                singleTrailingAnchor = singleTrailingAnchor,
            )
        }
    }

    private fun defaultRepeatAfterSourceCrop(
        band: FixedBandSource,
        horizontal: Boolean,
        sourceStart: Int,
        sourceEnd: Int,
        anchorCount: Int,
        singleTrailingAnchor: Boolean,
    ): PixelRect {
        val sourceLength = sourceEnd - sourceStart
        val minimumLength = if (anchorCount >= 2) 3 else 2
        require(sourceLength >= minimumLength) {
            "The cropped fixed band is too short to keep its fixed blocks"
        }
        val leadingAnchorLength = (sourceLength * 0.2f).toInt()
            .coerceIn(1, sourceLength - 1)
        val repeatStart = if (singleTrailingAnchor) {
            sourceStart
        } else {
            sourceStart + leadingAnchorLength
        }
        val repeatEnd = if (anchorCount >= 2 || singleTrailingAnchor) {
            val maximumTrailing = sourceEnd - repeatStart - 1
            val trailingAnchorLength = (sourceLength * 0.2f).toInt()
                .coerceIn(1, maximumTrailing)
            sourceEnd - trailingAnchorLength
        } else {
            sourceEnd
        }
        require(repeatEnd > repeatStart) { "The cropped fixed band has no repeatable pixels" }
        return if (horizontal) {
            PixelRect(repeatStart, 0, repeatEnd, band.height)
        } else {
            PixelRect(0, repeatStart, band.width, repeatEnd)
        }
    }

    private fun axisSpan(
        band: FixedBandSource,
        horizontal: Boolean,
        sourceStart: Int,
        length: Int,
        destinationStart: Int,
        crossOffset: Int,
    ): PlannedCompositionSpan {
        val source = if (horizontal) {
            PixelRect(sourceStart, 0, sourceStart + length, band.height)
        } else {
            PixelRect(0, sourceStart, band.width, sourceStart + length)
        }
        val destination = if (horizontal) {
            PixelRect(destinationStart, crossOffset, destinationStart + length, crossOffset + band.height)
        } else {
            PixelRect(crossOffset, destinationStart, crossOffset + band.width, destinationStart + length)
        }
        return PlannedCompositionSpan(band.sourceId, source, destination)
    }

    private fun endCapSpan(
        band: FixedBandSource,
        outputWidth: Int,
        outputHeight: Int,
        includeLeadingCorner: Boolean,
        includeTrailingCorner: Boolean,
    ): PlannedCompositionSpan {
        val horizontal = band.side == FixedSide.TOP || band.side == FixedSide.BOTTOM
        val sourceLength = if (horizontal) band.width else band.height
        val sourceStart = if (includeLeadingCorner) 0 else band.leadingCornerExtent
        val sourceEnd = if (includeTrailingCorner) {
            sourceLength
        } else {
            sourceLength - band.trailingCornerExtent
        }
        val targetLength = if (horizontal) outputWidth else outputHeight
        require(sourceEnd - sourceStart == targetLength) {
            "The end cap source length ${sourceEnd - sourceStart}px does not match " +
                "the output length ${targetLength}px"
        }
        val left = if (band.side == FixedSide.RIGHT) outputWidth - band.width else 0
        val top = if (band.side == FixedSide.BOTTOM) outputHeight - band.height else 0
        val source = if (horizontal) {
            PixelRect(sourceStart, 0, sourceEnd, band.height)
        } else {
            PixelRect(0, sourceStart, band.width, sourceEnd)
        }
        return PlannedCompositionSpan(
            sourceId = band.sourceId,
            source = source,
            destination = if (horizontal) {
                PixelRect(0, top, outputWidth, top + band.height)
            } else {
                PixelRect(left, 0, left + band.width, outputHeight)
            },
        )
    }

    private fun FixedBandSource.axisLength(): Int = when (side) {
        FixedSide.TOP, FixedSide.BOTTOM -> width
        FixedSide.LEFT, FixedSide.RIGHT -> height
    }

    private fun addDimensions(first: Int, second: Int, third: Int): Int = Math.addExact(
        Math.addExact(first, second),
        third,
    )
}
