package com.playtranslate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * OCR-Only live mode. When auto/live is on, polls at the configured capture
 * interval, runs OCR, and broadcasts to texthooker clients via the WS server.
 * No translation, no overlay paint — `runCaptureOcrTranslate` short-circuits
 * before the translate step when overlayMode is OCR_ONLY.
 *
 * Hotkey and in-app button paths still work the same way (OneShotManager →
 * runOcr → WS broadcast); this mode just adds the polling loop on top.
 */
class OcrOnlyMode(
    private val service: CaptureService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.OCR_ONLY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null
    private var lastOcrText: String? = null

    override fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                if (service.holdActive) {
                    delay(100)
                    continue
                }
                when (val outcome = service.runCaptureOcrTranslate(displayId)) {
                    is CaptureService.PipelineOutcome.Success -> {
                        val pipeline = outcome.pipeline
                        val dedupKey = pipeline.result.originalText
                            .filter { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
                        if (lastOcrText != null &&
                            !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                            delay(Prefs(service).captureIntervalMs)
                            continue
                        }
                        lastOcrText = dedupKey
                        service.emitResult(pipeline.result)
                    }
                    CaptureService.PipelineOutcome.NoText -> service.emitLiveNoText()
                    is CaptureService.PipelineOutcome.Failed -> {
                        service.emitError(outcome.message)
                        service.emitLiveNoText()
                    }
                }
                delay(Prefs(service).captureIntervalMs)
            }
        }
    }

    override fun stop() {
        pollingJob?.cancel()
        scope.cancel()
    }

    override fun refresh() {
        lastOcrText = null
    }

    override fun getCachedState(): CachedOverlayState? = null
}
