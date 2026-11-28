package com.laoke.weshirt.data

/** Only a recent observation for the same active registration can replace its timer. */
internal fun AppState.newerRunningMachine(task: LaundryTask, now: Long): Machine? {
    if (boardFromCache || task.done || task.completionConfirmed || task.currentTask == false || task.isArchived(now + task.clockOffsetMs)) return null
    val owner = profile?.id ?: return null
    val machine = machines.firstOrNull { it.id == task.machineId } ?: return null
    val running = machine.state == "occupied" || machine.state == "abnormal" && machine.localFinishAt()?.let { it > now } == true
    if (!running || machine.timingStale || machine.timingSource != "upstream" || machine.finishAt == null) return null
    if (machine.occupantId.isNotBlank() && machine.occupantId != owner) return null
    val observedAt = machine.timingObservedAt ?: return null
    val registration = task.createdAt ?: task.startAt
    if (registration != null && observedAt < registration) return null
    if (now + machine.clockOffsetMs - observedAt !in -30_000L until 180_000L) return null
    if (task.timingObservedAt?.let { observedAt <= it } == true) return null
    return machine
}

internal fun AppState.laundryDisplayTask(task: LaundryTask, now: Long): LaundryTask {
    // Older servers do not give affirmative ownership for this particular task.
    if (task.currentTask != true) return task
    val machine = newerRunningMachine(task, now) ?: return task
    return task.copy(endAt = machine.finishAt, clockOffsetMs = machine.clockOffsetMs, timingSource = machine.timingSource,
        machineState = machine.state, timingObservedAt = machine.timingObservedAt, timingStale = false)
}

internal fun AppState.laundryTimingCatchUpAt(now: Long): Long? = tasks.mapNotNull { task ->
    val machine = newerRunningMachine(task, now) ?: return@mapNotNull null
    val roomEnd = machine.localFinishAt()?.takeIf { it > now } ?: return@mapNotNull null
    val taskEnd = task.endAt?.minus(task.clockOffsetMs)
    machine.timingObservedAt.takeIf {
        task.timingStale || task.takeState == "pending" || taskEnd == null || kotlin.math.abs(roomEnd - taskEnd) >= 60_000
    }
}.maxOrNull()

internal fun LaundryTask.deviceStillRunning(now: Long): Boolean =
    !completionConfirmed && !timingStale && currentTask == true &&
        (machineState == "occupied" || machineState == "abnormal" && timingSource == "upstream" &&
            endAt?.let { it > now + clockOffsetMs } == true) &&
        timingObservedAt?.let { now + clockOffsetMs - it in -30_000L until 180_000L } != false

internal fun LaundryTask.canShowTake(now: Long): Boolean = completionConfirmed || !deviceStillRunning(now) &&
    (takeState == "pending" || timingSource != "upstream" && endAt?.let { it <= now + clockOffsetMs } == true)
