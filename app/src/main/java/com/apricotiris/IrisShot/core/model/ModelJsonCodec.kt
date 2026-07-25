package com.apricotiris.IrisShot.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central JSON codec for preferences and session files.
 * Parsing is intentionally tolerant so an app update does not invalidate old captures.
 */
object ModelJsonCodec {
    fun rectToJson(rect: RectConfig): JSONObject = JSONObject()
        .put("left", rect.left)
        .put("top", rect.top)
        .put("right", rect.right)
        .put("bottom", rect.bottom)

    fun rectFromJson(json: JSONObject): RectConfig = RectConfig(
        json.optInt("left"),
        json.optInt("top"),
        json.optInt("right"),
        json.optInt("bottom")
    )

    fun fixedLayerConfigToJson(layer: FixedLayerConfig): JSONObject = JSONObject()
        .put("side", layer.side.name)
        .put("anchorCount", layer.anchorCount.coerceIn(1, 2))
        .put("anchorPosition", layer.anchorPosition.name)
        .putNullable("repeatRegion", layer.repeatRegion?.let(::rectToJson))

    fun fixedLayerConfigFromJson(json: JSONObject): FixedLayerConfig = FixedLayerConfig(
        side = json.optEnum("side", FixedSide.TOP),
        anchorCount = json.optInt("anchorCount", 1).coerceIn(1, 2),
        anchorPosition = json.optEnum("anchorPosition", FixedAnchorPosition.LEADING),
        repeatRegion = json.optJSONObject("repeatRegion")?.let(::rectFromJson),
    )

    fun templateToJson(template: GameCaptureTemplate): JSONObject = JSONObject()
        .put("schemaVersion", 6)
        .put("id", template.id)
        .put("packageName", template.packageName)
        .put("appName", template.appName)
        .put("templateName", template.templateName)
        .put("orientation", template.orientation)
        .put("screenWidth", template.screenWidth)
        .put("screenHeight", template.screenHeight)
        .put("scrollDirection", template.scrollDirection.name)
        .put("captureRegion", rectToJson(template.captureRegion))
        .put("stitchRegion", rectToJson(template.stitchRegion))
        .put("fixedLayers", JSONArray().also { array ->
            template.fixedLayers.forEach { array.put(fixedLayerConfigToJson(it)) }
        })
        .put("gestureRegion", rectToJson(template.gestureRegion))
        .putNullable(
            "navigationContentBoundsAtGeometry",
            template.navigationContentBoundsAtGeometry?.let(::rectToJson),
        )
        .putNullable("topFixedRegion", template.topFixedRegion?.let(::rectToJson))
        .putNullable("bottomFixedRegion", template.bottomFixedRegion?.let(::rectToJson))
        .put("hudMaskRegions", JSONArray().also { array ->
            template.hudMaskRegions.forEach { array.put(rectToJson(it)) }
        })
        .put("gestureDistancePx", template.gestureDistancePx)
        .put("gestureDurationMs", template.gestureDurationMs)
        .put("waitAfterGestureMs", template.waitAfterGestureMs)
        .put("gestureStableTimeMs", template.gestureStableTimeMs)
        .put("postGestureWaitEnabled", template.postGestureWaitEnabled)
        .put("postGestureWaitMs", template.postGestureWaitMs)
        .put("waitAfterOverlayHiddenMs", template.waitAfterOverlayHiddenMs)
        .put("minOverlapRatio", template.minOverlapRatio.toDouble())
        .put("maxOverlapRatio", template.maxOverlapRatio.toDouble())
        .put("captureSettingsVersion", template.captureSettingsVersion)
        .put("previewEnabled", template.previewEnabled)
        .put("cleanCapture", template.cleanCapture)
        .put("previewWidthDp", template.previewWidthDp)
        .put("previewMaxHeightDp", template.previewMaxHeightDp)
        .put("previewGapDurationMs", template.previewGapDurationMs)
        .put("previewCenterXRatio", template.previewCenterXRatio.toDouble())
        .put("previewCenterYRatio", template.previewCenterYRatio.toDouble())
        .put("maxOutputHeight", template.maxOutputHeight)
        .put("detectOverlap", true)
        .put("cropDuplicate", true)
        .put("keepOriginalFrames", template.keepOriginalFrames)
        .put("keepTempFilesOnFailure", true)
        .put("namingRule", template.namingRule)
        .put("autoOpenResult", template.autoOpenResult)
        .put("outputFormat", template.outputFormat.name)
        .put("jpegQuality", ImageOutputFormat.normalizeJpegQuality(template.jpegQuality))
        .put("stitchMethod", template.stitchMethod.name)
        .put("createdAt", template.createdAt)
        .put("updatedAt", template.updatedAt)

    fun templateFromJson(json: JSONObject): GameCaptureTemplate {
        val now = System.currentTimeMillis()
        val createdAt = json.optLong("createdAt", now)
        val direction = json.optEnum("scrollDirection", ScrollDirection.UP)
        val storedCapture = json.optJSONObject("captureRegion")?.let(::rectFromJson)
            ?: RectConfig(0, 0, 0, 0)
        val legacyTop = json.optJSONObject("topFixedRegion")?.let(::rectFromJson)
        val legacyBottom = json.optJSONObject("bottomFixedRegion")?.let(::rectFromJson)
        val hasStitchRegion = json.optJSONObject("stitchRegion") != null
        val migratedCapture = if (hasStitchRegion || storedCapture.isEmpty || !direction.isVertical) {
            storedCapture
        } else {
            RectConfig(
                storedCapture.left,
                legacyTop?.top ?: storedCapture.top,
                storedCapture.right,
                legacyBottom?.bottom ?: storedCapture.bottom,
            )
        }
        val stitch = json.optJSONObject("stitchRegion")?.let(::rectFromJson)
            ?: storedCapture
        val fixedLayers = if (json.has("fixedLayers")) {
            json.optJSONArray("fixedLayers").toObjectList(::fixedLayerConfigFromJson)
        } else {
            // Schema 4 pinned markers were an editor-only horizontal representation. Its derived
            // stitch band is authoritative, so infer the generic fixed sides from that band.
            GameCaptureTemplate.inferFixedLayers(migratedCapture, stitch, direction)
        }
        val captureSettingsVersion = resolveTemplateCaptureSettingsVersion(
            storedVersion = json.takeIf { it.has("captureSettingsVersion") }
                ?.optInt("captureSettingsVersion"),
            hasAllCurrentFields = TEMPLATE_CAPTURE_SETTING_FIELDS.all(json::has),
        )
        return GameCaptureTemplate(
            id = json.optLong("id", 0L),
            packageName = json.optString("packageName", ""),
            appName = json.optString("appName", ""),
            templateName = json.optString("templateName", ""),
            orientation = json.optString("orientation", "landscape"),
            screenWidth = json.optInt("screenWidth", 0),
            screenHeight = json.optInt("screenHeight", 0),
            scrollDirection = direction,
            captureRegion = migratedCapture,
            stitchRegion = stitch,
            fixedLayers = fixedLayers,
            gestureRegion = json.optJSONObject("gestureRegion")?.let(::rectFromJson)
                ?: RectConfig(0, 0, 0, 0),
            navigationContentBoundsAtGeometry = json
                .optJSONObject("navigationContentBoundsAtGeometry")
                ?.let(::rectFromJson),
            topFixedRegion = legacyTop,
            bottomFixedRegion = legacyBottom,
            hudMaskRegions = json.optJSONArray("hudMaskRegions").toRectList(),
            gestureDistancePx = json.optInt(
                "gestureDistancePx",
                DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_PX,
            ),
            gestureDurationMs = json.optLong("gestureDurationMs", DefaultCaptureConfig.GESTURE_DURATION_MS),
            waitAfterGestureMs = json.optLong("waitAfterGestureMs", DefaultCaptureConfig.WAIT_AFTER_GESTURE_MS),
            gestureStableTimeMs = json.optLong(
                "gestureStableTimeMs",
                DefaultCaptureConfig.GESTURE_STABLE_TIME_MS,
            ),
            postGestureWaitEnabled = json.optBoolean(
                "postGestureWaitEnabled",
                DefaultCaptureConfig.POST_GESTURE_WAIT_ENABLED,
            ),
            postGestureWaitMs = json.optLong(
                "postGestureWaitMs",
                DefaultCaptureConfig.POST_GESTURE_WAIT_MS,
            ),
            waitAfterOverlayHiddenMs = json.optLong(
                "waitAfterOverlayHiddenMs",
                DefaultCaptureConfig.WAIT_AFTER_OVERLAY_HIDDEN_MS
            ),
            minOverlapRatio = json.optDouble(
                "minOverlapRatio",
                DefaultCaptureConfig.MIN_OVERLAP_RATIO.toDouble()
            ).toFloat(),
            maxOverlapRatio = json.optDouble(
                "maxOverlapRatio",
                DefaultCaptureConfig.MAX_OVERLAP_RATIO.toDouble()
            ).toFloat(),
            captureSettingsVersion = captureSettingsVersion,
            previewEnabled = json.optBoolean("previewEnabled", true),
            cleanCapture = json.optBoolean("cleanCapture", false),
            previewWidthDp = json.optInt(
                "previewWidthDp",
                DefaultCaptureConfig.PREVIEW_INITIAL_WIDTH_DP,
            ),
            previewMaxHeightDp = json.optInt(
                "previewMaxHeightDp",
                DefaultCaptureConfig.PREVIEW_MAX_HEIGHT_DP,
            ),
            previewGapDurationMs = json.optInt(
                "previewGapDurationMs",
                DefaultCaptureConfig.PREVIEW_GAP_DURATION_MS,
            ),
            previewCenterXRatio = json.optDouble(
                "previewCenterXRatio",
                DefaultCaptureConfig.PREVIEW_CENTER_X_RATIO.toDouble(),
            ).toFloat(),
            previewCenterYRatio = json.optDouble(
                "previewCenterYRatio",
                DefaultCaptureConfig.PREVIEW_CENTER_Y_RATIO.toDouble(),
            ).toFloat(),
            maxOutputHeight = json.optInt(
                "maxOutputHeight",
                DefaultCaptureConfig.DEFAULT_MAX_OUTPUT_HEIGHT,
            ),
            detectOverlap = true,
            cropDuplicate = true,
            keepOriginalFrames = json.optBoolean("keepOriginalFrames", false),
            keepTempFilesOnFailure = true,
            namingRule = json.optString("namingRule", "TIMESTAMP"),
            autoOpenResult = json.optBoolean("autoOpenResult", true),
            outputFormat = json.optEnum("outputFormat", ImageOutputFormat.JPEG),
            jpegQuality = ImageOutputFormat.normalizeJpegQuality(
                json.optInt("jpegQuality", ImageOutputFormat.DEFAULT_JPEG_QUALITY),
            ),
            stitchMethod = json.optEnum("stitchMethod", StitchMethod.TRANSLATION_HARD_CUT),
            createdAt = createdAt,
            updatedAt = json.optLong("updatedAt", createdAt)
        ).withNormalizedRegions()
    }

    fun frameToJson(frame: CapturedFrame): JSONObject = JSONObject()
        .put("index", frame.index)
        .put("timestamp", frame.timestamp)
        .putNullable("fullBitmapPath", frame.fullBitmapPath)
        .put("scrollBitmapPath", frame.scrollBitmapPath)
        .putNullable("analysisBitmapPath", frame.analysisBitmapPath)
        .put("offsetX", frame.offsetX)
        .put("offsetY", frame.offsetY)
        .put("confidence", frame.confidence.toDouble())
        .put("accepted", frame.accepted)
        .putNullable("matchReason", frame.matchReason)
        .put("width", frame.width)
        .put("height", frame.height)

    fun frameFromJson(json: JSONObject): CapturedFrame = CapturedFrame(
        index = json.optInt("index", 0),
        timestamp = json.optLong("timestamp", 0L),
        fullBitmapPath = json.optNullableString("fullBitmapPath"),
        scrollBitmapPath = json.optString("scrollBitmapPath", ""),
        analysisBitmapPath = json.optNullableString("analysisBitmapPath"),
        offsetX = json.optInt("offsetX", 0),
        offsetY = json.optInt("offsetY", 0),
        confidence = json.optDouble("confidence", 0.0).toFloat(),
        accepted = json.optBoolean("accepted", false),
        matchReason = json.optNullableString("matchReason"),
        width = json.optInt("width", 0),
        height = json.optInt("height", 0)
    )

    fun scrollChunkToJson(chunk: ScrollChunk): JSONObject = JSONObject()
        .put("path", chunk.path)
        .put("width", chunk.width)
        .put("height", chunk.height)

    fun scrollChunkFromJson(json: JSONObject): ScrollChunk = ScrollChunk(
        path = json.optString("path", ""),
        width = json.optInt("width", 0),
        height = json.optInt("height", 0),
    )

    fun capturedFixedLayerToJson(layer: CapturedFixedLayer): JSONObject = JSONObject()
        .put("side", layer.side.name)
        .put("bitmapPath", layer.bitmapPath)
        .put("width", layer.width)
        .put("height", layer.height)
        .put("anchorCount", layer.anchorCount.coerceIn(1, 2))
        .put("anchorPosition", layer.anchorPosition.name)
        .putNullable("repeatRegion", layer.repeatRegion?.let(::rectToJson))

    fun capturedFixedLayerFromJson(json: JSONObject): CapturedFixedLayer {
        val width = json.optInt("width", 0).coerceAtLeast(0)
        val height = json.optInt("height", 0).coerceAtLeast(0)
        val repeat = json.optJSONObject("repeatRegion")
            ?.let(::rectFromJson)
            ?.clampTo(width, height)
            ?.takeUnless { it.isEmpty }
        return CapturedFixedLayer(
            side = json.optEnum("side", FixedSide.TOP),
            bitmapPath = json.optString("bitmapPath", ""),
            width = width,
            height = height,
            anchorCount = json.optInt("anchorCount", 1).coerceIn(1, 2),
            anchorPosition = json.optEnum("anchorPosition", FixedAnchorPosition.LEADING),
            repeatRegion = repeat,
        )
    }

    fun exportRecordToJson(record: ResultExportRecord): JSONObject = JSONObject()
        .put("uris", JSONArray().also { array -> record.uris.forEach(array::put) })
        .putNullable("destinationTreeUri", record.destinationTreeUri)
        .put("format", record.format.name)
        .put("width", record.width)
        .put("totalHeight", record.totalHeight)
        .put("segmented", record.segmented)
        .put("displayNames", JSONArray().also { array -> record.displayNames.forEach(array::put) })
        .put("includeTop", record.includeTop)
        .put("includeBottom", record.includeBottom)
        .put("parameterSignature", record.parameterSignature)
        .put("exportedAt", record.exportedAt)

    fun exportRecordFromJson(json: JSONObject): ResultExportRecord = ResultExportRecord(
        uris = json.optJSONArray("uris").toStringList(),
        destinationTreeUri = json.optNullableString("destinationTreeUri"),
        format = json.optEnum("format", ImageOutputFormat.JPEG),
        width = json.optInt("width", 0),
        totalHeight = json.optLong("totalHeight", 0L),
        segmented = json.optBoolean("segmented", false),
        displayNames = json.optJSONArray("displayNames").toStringList(),
        includeTop = json.optBoolean("includeTop", true),
        includeBottom = json.optBoolean("includeBottom", true),
        parameterSignature = json.optString("parameterSignature", ""),
        exportedAt = json.optLong("exportedAt", 0L),
    )

    fun resultToJson(result: LayeredCaptureResult): JSONObject = JSONObject()
        .put("schemaVersion", result.schemaVersion)
        .put("id", result.id)
        .put("sessionId", result.sessionId)
        .put("appName", result.appName)
        .put("capturedPackageName", result.capturedPackageName)
        .putNullable("recordName", result.recordName)
        .putNullable("topFixedBitmapPath", result.topFixedBitmapPath)
        .put("fixedLayers", JSONArray().also { array ->
            result.fixedLayers.forEach { array.put(capturedFixedLayerToJson(it)) }
        })
        .put("scrollLongBitmapPath", result.scrollLongBitmapPath)
        .putNullable("thumbnailBitmapPath", result.thumbnailBitmapPath)
        .put("scrollChunks", JSONArray().also { array ->
            result.scrollChunks.forEach { array.put(scrollChunkToJson(it)) }
        })
        .put("scrollContentWidth", result.scrollContentWidth)
        .put("scrollContentHeight", result.scrollContentHeight)
        .putNullable("bottomFixedBitmapPath", result.bottomFixedBitmapPath)
        .put("selectedScrollStart", result.selectedScrollStart)
        .put("selectedScrollEnd", result.selectedScrollEnd)
        .put("scrollExportRegions", JSONArray().also { array ->
            result.scrollExportRegions.forEach { region ->
                array.put(
                    JSONObject()
                        .put("start", region.start)
                        .put("end", region.end)
                        .put("enabled", region.enabled),
                )
            }
        })
        .put("seamPositions", JSONArray().also { array -> result.seamPositions.forEach(array::put) })
        .put("sourceFrames", JSONArray().also { array ->
            result.sourceFrames.forEach { array.put(frameToJson(it)) }
        })
        .putNullable("templateSnapshot", result.templateSnapshot?.let(::templateToJson))
        .putNullable("lastExport", result.lastExport?.let(::exportRecordToJson))
        .put("createdAt", result.createdAt)
        .put("updatedAt", result.updatedAt)
        .put("outputFormat", result.outputFormat.name)
        .put(
            "jpegQuality",
            ImageOutputFormat.normalizeJpegQuality(result.jpegQuality),
        )
        .put(
            "lowConfidenceSeams",
            JSONArray().also { array -> result.lowConfidenceSeams.forEach(array::put) }
        )

    fun resultFromJson(json: JSONObject): LayeredCaptureResult {
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val templateSnapshot = json.optJSONObject("templateSnapshot")?.let(::templateFromJson)
        return LayeredCaptureResult(
            schemaVersion = json.optInt("schemaVersion", 1),
            topFixedBitmapPath = json.optNullableString("topFixedBitmapPath"),
            fixedLayers = json.optJSONArray("fixedLayers").toObjectList(::capturedFixedLayerFromJson),
            scrollLongBitmapPath = json.optString("scrollLongBitmapPath", ""),
            thumbnailBitmapPath = json.optNullableString("thumbnailBitmapPath"),
            scrollChunks = json.optJSONArray("scrollChunks").toObjectList(::scrollChunkFromJson),
            scrollContentWidth = json.optInt("scrollContentWidth", 0),
            scrollContentHeight = json.optInt("scrollContentHeight", 0),
            bottomFixedBitmapPath = json.optNullableString("bottomFixedBitmapPath"),
            selectedScrollStart = json.optInt("selectedScrollStart", 0),
            selectedScrollEnd = json.optInt("selectedScrollEnd", 0),
            scrollExportRegions = json.optJSONArray("scrollExportRegions")
                .toObjectList { value ->
                    ScrollExportRegion(
                        start = value.optInt("start", 0),
                        end = value.optInt("end", 0),
                        enabled = value.optBoolean("enabled", true),
                    )
                },
            seamPositions = json.optJSONArray("seamPositions").toIntList(),
            sourceFrames = json.optJSONArray("sourceFrames").toObjectList(::frameFromJson),
            id = json.optString("id", ""),
            sessionId = json.optString("sessionId", ""),
            appName = json.optString("appName", ""),
            capturedPackageName = json.optString("capturedPackageName", "")
                .ifBlank { templateSnapshot?.packageName.orEmpty() },
            recordName = json.optNullableString("recordName"),
            templateSnapshot = templateSnapshot,
            lastExport = json.optJSONObject("lastExport")?.let(::exportRecordFromJson),
            createdAt = createdAt,
            updatedAt = json.optLong("updatedAt", createdAt),
            lowConfidenceSeams = json.optJSONArray("lowConfidenceSeams").toIntList(),
            // Results written before schema 3 were always lossless PNG.
            outputFormat = json.optEnum("outputFormat", ImageOutputFormat.PNG),
            jpegQuality = ImageOutputFormat.normalizeJpegQuality(
                json.optInt("jpegQuality", ImageOutputFormat.DEFAULT_JPEG_QUALITY),
            ),
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(key, fallback.name)) }.getOrDefault(fallback)

    private fun JSONArray?.toRectList(): List<RectConfig> =
        toObjectList(::rectFromJson)

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) add(optInt(index))
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun <T> JSONArray?.toObjectList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { json ->
                    runCatching { mapper(json) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    internal fun resolveTemplateCaptureSettingsVersion(
        storedVersion: Int?,
        hasAllCurrentFields: Boolean,
    ): Int = when {
        storedVersion != null -> storedVersion.coerceAtLeast(
            GameCaptureTemplate.LEGACY_CAPTURE_SETTINGS_VERSION,
        )
        hasAllCurrentFields -> GameCaptureTemplate.CURRENT_CAPTURE_SETTINGS_VERSION
        else -> GameCaptureTemplate.LEGACY_CAPTURE_SETTINGS_VERSION
    }

    private val TEMPLATE_CAPTURE_SETTING_FIELDS = setOf(
        "previewEnabled",
        "cleanCapture",
        "previewWidthDp",
        "previewMaxHeightDp",
        "previewGapDurationMs",
        "maxOutputHeight",
        "detectOverlap",
        "cropDuplicate",
        "keepOriginalFrames",
        "keepTempFilesOnFailure",
        "namingRule",
        "autoOpenResult",
        "outputFormat",
        "jpegQuality",
    )
}
