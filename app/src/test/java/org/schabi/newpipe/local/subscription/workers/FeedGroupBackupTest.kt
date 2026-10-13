/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.workers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon

class FeedGroupBackupTest {
    private fun subscription(uid: Long, url: String) = SubscriptionEntity(uid = uid, serviceId = 0, url = url, name = "Channel $uid")

    private val subscriptions = listOf(
        subscription(1, "https://www.youtube.com/channel/A"),
        subscription(2, "https://www.youtube.com/channel/B"),
        subscription(3, "https://www.youtube.com/channel/C")
    )

    @Test
    fun exportsFoldersInOrderWithChannelUrls() {
        val groups = listOf(
            FeedGroupEntity(20, "Music", FeedGroupIcon.MUSIC, sortOrder = 1),
            FeedGroupEntity(10, "News", FeedGroupIcon.NEWS, sortOrder = 0)
        )
        val members = mapOf(10L to listOf(1L, 3L), 20L to listOf(2L, 99L))

        val items = FeedGroupBackup.export(groups, members, subscriptions)

        assertEquals(listOf("News", "Music"), items.map { it.name })
        assertEquals(FeedGroupIcon.NEWS.id, items[0].iconId)
        assertEquals(
            listOf(
                FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/A"),
                FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/C")
            ),
            items[0].subscriptions
        )
        // the unknown subscription 99 is skipped
        assertEquals(1, items[1].subscriptions.size)
    }

    @Test
    fun importCreatesNewFoldersAndMergesExistingOnes() {
        val items = listOf(
            FeedGroupItem(
                "Music",
                FeedGroupIcon.MUSIC.id,
                1,
                listOf(FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/B"))
            ),
            FeedGroupItem(
                "News",
                FeedGroupIcon.NEWS.id,
                0,
                listOf(
                    FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/A"),
                    FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/not-subscribed")
                )
            )
        )
        val existing = listOf(FeedGroupEntity(5, "Music", FeedGroupIcon.GAMING, sortOrder = 0))
        val ids = subscriptions.associate { it.url!! to it.uid }

        val plan = FeedGroupBackup.planImport(items, existing, mapOf(5L to listOf(3L))) { _, url -> ids[url] }

        assertEquals(2, plan.size)
        val news = plan[0]
        assertEquals("News", news.name)
        assertNull(news.existingId)
        assertEquals(FeedGroupIcon.NEWS, news.icon)
        assertEquals(listOf(1L), news.subscriptionIds)

        val music = plan[1]
        assertEquals(5L, music.existingId)
        assertEquals(FeedGroupIcon.GAMING, music.icon) // the user's icon is kept
        assertEquals(listOf(3L, 2L), music.subscriptionIds)
    }

    @Test
    fun importingTwiceDoesNotDuplicateChannels() {
        val items = listOf(FeedGroupItem("News", 0, 0, listOf(FeedGroupSubscriptionItem(0, "a"))))
        val existing = listOf(FeedGroupEntity(7, "News", FeedGroupIcon.ALL))
        val plan = FeedGroupBackup.planImport(items, existing, mapOf(7L to listOf(1L))) { _, _ -> 1L }
        assertEquals(listOf(1L), plan.single().subscriptionIds)
        assertEquals(7L, plan.single().existingId)
    }

    @Test
    fun unknownIconFallsBackToDefault() {
        val plan = FeedGroupBackup.planImport(listOf(FeedGroupItem("X", 9999)), emptyList(), emptyMap()) { _, _ -> null }
        assertEquals(FeedGroupIcon.ALL, plan.single().icon)
    }

    @Test
    fun jsonKeepsFoldersAndReadsOldFiles() {
        val groups = listOf(
            FeedGroupItem("Tom & Jerry 🎬", 3, 0, listOf(FeedGroupSubscriptionItem(0, "https://www.youtube.com/channel/A")))
        )
        val output = ByteArrayOutputStream()
        ImportExportJsonHelper.writeTo(listOf(SubscriptionItem(0, "https://www.youtube.com/channel/A", "A")), output, groups)
        val json = output.toString("UTF-8")
        assertTrue(json.contains("\"feed_groups\""))

        val data = ImportExportJsonHelper.readDataFrom(ByteArrayInputStream(json.toByteArray()))
        assertEquals(groups, data.feedGroups)
        assertEquals(1, data.subscriptions.size)

        val old = "{\"app_version\":\"0.24.0\",\"app_version_int\":990,\"subscriptions\":" +
            "[{\"service_id\":0,\"url\":\"https://www.youtube.com/channel/A\",\"name\":\"A\"}]," +
            "\"some_future_key\":1}"
        val oldData = ImportExportJsonHelper.readDataFrom(ByteArrayInputStream(old.toByteArray()))
        assertEquals(1, oldData.subscriptions.size)
        assertTrue(oldData.feedGroups.isEmpty())
    }
}
