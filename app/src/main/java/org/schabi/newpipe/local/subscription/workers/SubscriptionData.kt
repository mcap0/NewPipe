package org.schabi.newpipe.local.subscription.workers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.schabi.newpipe.BuildConfig

@Serializable
class SubscriptionData(
    val subscriptions: List<SubscriptionItem>,
    /**
     * The folders (feed groups) and their channels. Not part of the format of the official
     * NewPipe; files without it (older exports) are still read, with no folders.
     */
    @SerialName("feed_groups")
    val feedGroups: List<FeedGroupItem> = emptyList()
) {
    @SerialName("app_version")
    private val appVersion = BuildConfig.VERSION_NAME

    @SerialName("app_version_int")
    private val appVersionInt = BuildConfig.VERSION_CODE
}

@Serializable
data class SubscriptionItem(
    @SerialName("service_id")
    val serviceId: Int,
    val url: String,
    val name: String
)

/**
 * A folder (feed group) in the JSON export.
 *
 * @param iconId the id of the [org.schabi.newpipe.local.subscription.FeedGroupIcon]
 * @param subscriptions the channels in the folder, identified like in [SubscriptionItem]
 */
@Serializable
data class FeedGroupItem(
    val name: String,
    @SerialName("icon_id")
    val iconId: Int = 0,
    @SerialName("sort_order")
    val sortOrder: Long = -1,
    val subscriptions: List<FeedGroupSubscriptionItem> = emptyList()
)

@Serializable
data class FeedGroupSubscriptionItem(
    @SerialName("service_id")
    val serviceId: Int,
    val url: String
)
