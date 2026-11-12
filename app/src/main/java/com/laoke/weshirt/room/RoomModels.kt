package com.laoke.weshirt.room

/** Public, credential-free projection of a machine into the native room. */
data class RoomMachine(
    val id: String,
    val slot: String,
    val label: String,
    val state: RoomMachineState,
    val finishAt: Long? = null,
    val kind: String = "washer",
    val queueCount: Int = 0,
)

enum class RoomFacility { BOILER, WASHER_NOTICE, DRYER_NOTICE }

enum class RoomMachineState { IDLE, RUNNING, FINISHING, REVIEW, AWAITING_PICKUP, FAULT, UNKNOWN }

enum class PerformanceMode { AUTO, BATTERY, SMOOTH, HIGH }

enum class QualityReason { DEVICE, PERFORMANCE, POWER_SAVER, THERMAL, IDLE, BACKGROUND }

data class QualityState(
    val targetFps: Int,
    val resolutionScale: Float,
    val reason: QualityReason,
    val adaptiveLevel: Int,
    val showDowngradeNotice: Boolean = false,
) {
    val isReduced: Boolean get() = adaptiveLevel > 0 || reason == QualityReason.THERMAL
}

/** Counts completed renderer submissions; this is not an estimated or timer-derived FPS value. */
data class RoomRenderStatistics(
    val renderedFrames: Long,
    val rejectedFrames: Long,
    val engineReady: Boolean,
    val surfaceReady: Boolean,
    val materialCount: Int,
    val meshCount: Int,
    val machineCount: Int,
    val latestGpuDurationNanos: Long?,
    val failureEvent: String?,
    val disposed: Boolean,
    val pendingMaterialCompilations: Int = 0,
    val nativeDiagnostics: String = "",
    val cameraMoving: Boolean = false,
    val doorsAnimating: Boolean = false,
)
