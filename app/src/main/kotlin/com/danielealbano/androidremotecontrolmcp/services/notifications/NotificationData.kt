package com.danielealbano.androidremotecontrolmcp.services.notifications

data class NotificationData(
    val notificationId: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val timestamp: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String?,
    val groupKey: String?,
    val actions: List<NotificationActionData>,
)

data class NotificationActionData(
    val actionId: String,
    val index: Int,
    val title: String,
    val acceptsText: Boolean,
)
