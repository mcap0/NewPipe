/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Loads the most viewed videos of every channel of a feed group and caches them on disk.
 */
class PerleRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)
    private val cacheDir = File(appContext.filesDir, CACHE_DIR)

    fun readCache(groupId: Long): PerleCache? {
        return try {
            val file = cacheFile(groupId)
            if (file.exists()) json.decodeFromString(PerleCache.serializer(), file.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Perle cache for group $groupId", e)
            null
        }
    }

    fun isFresh(cache: PerleCache): Boolean {
        return System.currentTimeMillis() - cache.timestamp < CACHE_DURATION_MILLIS
    }

    /**
     * Downloads the videos of every channel in the group, at most [MAX_CONCURRENT_CHANNELS] at a
     * time. Channels which fail to load are skipped and counted in [PerleCache.failedChannels].
     */
    fun fetch(groupId: Long, onProgress: (done: Int, total: Int) -> Unit): Single<PerleCache> {
        return Single.fromCallable { subscriptionsFor(groupId) }
            .flatMap { subscriptions ->
                val done = AtomicInteger()
                val failed = AtomicInteger()
                onProgress(0, subscriptions.size)

                Flowable.fromIterable(subscriptions)
                    .flatMapMaybe(
                        { subscription ->
                            Maybe.fromCallable { loadChannel(subscription) }
                                .subscribeOn(Schedulers.io())
                                .doOnError { e ->
                                    Log.w(TAG, "Skipping channel ${subscription.url}", e)
                                    failed.incrementAndGet()
                                }
                                .onErrorComplete()
                                .doFinally { onProgress(done.incrementAndGet(), subscriptions.size) }
                        },
                        false,
                        MAX_CONCURRENT_CHANNELS
                    )
                    .filter { it.isNotEmpty() }
                    .toList()
                    .map { channels ->
                        PerleCache(System.currentTimeMillis(), channels, failed.get())
                    }
            }
            .doOnSuccess { writeCache(groupId, it) }
    }

    /**
     * @return the urls of all the streams in the watch history
     */
    fun watchedUrls(): Set<String> {
        return database.streamHistoryDAO().historySortedById.blockingFirst()
            .mapTo(HashSet()) { it.streamEntity.url }
    }

    private fun subscriptionsFor(groupId: Long): List<SubscriptionEntity> {
        val subscriptionDAO = database.subscriptionDAO()
        return if (groupId == FeedGroupEntity.GROUP_ALL_ID) {
            subscriptionDAO.getAll().blockingFirst()
        } else {
            database.feedGroupDAO().getSubscriptionIdsFor(groupId).blockingFirst()
                .map { subscriptionDAO.getSubscription(it) }
        }
    }

    private fun loadChannel(subscription: SubscriptionEntity): List<PerleVideo> {
        val serviceId = subscription.serviceId
        val channelInfo = ExtractorHelper.getChannelInfo(serviceId, subscription.url, false)
            .blockingGet()
        val videosTab = channelInfo.tabs
            .firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: return emptyList()

        val popularTab = withPopularSort(serviceId, videosTab)
        val tab = popularTab ?: videosTab
        // when the service sorts by popularity the first page already has the best videos
        val maxPages = if (popularTab != null) 1 else MAX_PAGES_PER_CHANNEL

        val tabInfo = ExtractorHelper.getChannelTab(serviceId, tab, true).blockingGet()
        val items = ArrayList<StreamInfoItem>()
        items.addAll(tabInfo.relatedItems.filterIsInstance<StreamInfoItem>())
        var nextPage = tabInfo.nextPage
        var pages = 1
        while (pages < maxPages && Page.isValid(nextPage)) {
            val page = ExtractorHelper.getMoreChannelTabItems(serviceId, tab, nextPage)
                .blockingGet()
            items.addAll(page.items.filterIsInstance<StreamInfoItem>())
            nextPage = page.nextPage
            pages++
        }

        return PerleSelection.selectTopVideos(items).map { PerleVideo.from(it) }
    }

    /**
     * @return the given tab sorted by popularity, or null if the service does not support it
     */
    private fun withPopularSort(serviceId: Int, tab: ListLinkHandler): ListLinkHandler? {
        return try {
            val factory = NewPipe.getService(serviceId).channelTabLHFactory
            val popularSort = factory.availableSortFilter
                .firstOrNull { it.contains(POPULAR_SORT, ignoreCase = true) }
                ?: return null
            factory.fromQuery(tab.id, tab.contentFilters, popularSort, tab.baseUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Could not sort channel tab ${tab.url} by popularity", e)
            null
        }
    }

    private fun writeCache(groupId: Long, cache: PerleCache) {
        try {
            cacheDir.mkdirs()
            cacheFile(groupId).writeText(json.encodeToString(PerleCache.serializer(), cache))
        } catch (e: Exception) {
            Log.w(TAG, "Could not write Perle cache for group $groupId", e)
        }
    }

    private fun cacheFile(groupId: Long) = File(cacheDir, "group_$groupId.json")

    companion object {
        private const val TAG = "PerleRepository"
        private const val CACHE_DIR = "perle"
        private const val POPULAR_SORT = "popular"
        const val MAX_CONCURRENT_CHANNELS = 4
        const val MAX_PAGES_PER_CHANNEL = 5
        val CACHE_DURATION_MILLIS = TimeUnit.DAYS.toMillis(7)

        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class PerleCache(
    val timestamp: Long,
    val channels: List<List<PerleVideo>>,
    val failedChannels: Int = 0
)

@Serializable
data class PerleVideo(
    val serviceId: Int,
    val url: String,
    val title: String,
    val duration: Long,
    val uploader: String,
    val uploaderUrl: String? = null,
    val thumbnailUrl: String? = null,
    val viewCount: Long? = null,
    val textualUploadDate: String? = null,
    val uploadDate: String? = null
) {
    fun toStreamEntity(uid: Long) = StreamEntity(
        uid = uid,
        serviceId = serviceId,
        url = url,
        title = title,
        streamType = StreamType.VIDEO_STREAM,
        duration = duration,
        uploader = uploader,
        uploaderUrl = uploaderUrl,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        textualUploadDate = textualUploadDate,
        uploadDate = uploadDate?.let { OffsetDateTime.parse(it) }
    )

    companion object {
        fun from(item: StreamInfoItem) = PerleVideo(
            serviceId = item.serviceId,
            url = item.url,
            title = item.name.orEmpty(),
            duration = item.duration,
            uploader = item.uploaderName.orEmpty(),
            uploaderUrl = item.uploaderUrl,
            thumbnailUrl = ImageStrategy.imageListToDbUrl(item.thumbnails),
            viewCount = item.viewCount.takeIf { it >= 0 },
            textualUploadDate = item.textualUploadDate,
            uploadDate = item.uploadDate?.offsetDateTime()?.toString()
        )
    }
}
