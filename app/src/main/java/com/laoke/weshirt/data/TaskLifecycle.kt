package com.laoke.weshirt.data

private const val HOUR = 3_600_000L
fun LaundryTask.isArchived(now: Long): Boolean = archived || createdAt?.let { now >= it + 72 * HOUR } == true
fun LaundryTask.isExpired(now: Long): Boolean = expiresAt?.let { now >= it } ?: (createdAt?.let { now >= it + 336 * HOUR } == true)

data class LaundryRecords(val current: List<LaundryTask>, val history: List<LaundryTask>)
fun laundryRecords(tasks: List<LaundryTask>, now: Long): LaundryRecords {
    val retained = tasks.filterNot { it.isExpired(now) }.sortedByDescending { it.createdAt ?: it.startAt ?: 0 }
    return LaundryRecords(retained.filterNot { it.isArchived(now) }, retained.filter { it.isArchived(now) })
}
