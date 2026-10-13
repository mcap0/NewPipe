/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.subscription.workers

import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon

/**
 * What to do with one folder when importing: create it ([existingId] is null) or add the
 * channels to the existing folder with the same name.
 */
data class FeedGroupImport(
    val existingId: Long?,
    val name: String,
    val icon: FeedGroupIcon,
    val subscriptionIds: List<Long>
)

/**
 * Converts the folders (feed groups) between the database and the JSON export of the
 * subscriptions. Channels are referenced by service id and url, since the database ids change
 * from one device to another.
 */
object FeedGroupBackup {

    /**
     * @param groups the folders, in any order
     * @param members the subscription ids of every folder, by folder id
     * @param subscriptions all the subscriptions
     */
    fun export(
        groups: List<FeedGroupEntity>,
        members: Map<Long, List<Long>>,
        subscriptions: List<SubscriptionEntity>
    ): List<FeedGroupItem> {
        val byId = subscriptions.associateBy { it.uid }
        return groups.sortedBy { it.sortOrder }.map { group ->
            FeedGroupItem(
                name = group.name,
                iconId = group.icon.id,
                sortOrder = group.sortOrder,
                subscriptions = members[group.uid].orEmpty()
                    .mapNotNull { byId[it] }
                    .filter { !it.url.isNullOrEmpty() }
                    .map { FeedGroupSubscriptionItem(it.serviceId, it.url!!) }
            )
        }
    }

    /**
     * Plans the import of the folders, after the subscriptions themselves were imported.
     *
     * A folder with the same name as an existing one is merged into it (its channels are added),
     * so that importing the same file twice does not create duplicates. Channels which are not
     * subscribed (e.g. because they could not be loaded during the import) are skipped.
     *
     * @param existingGroups the folders already in the database
     * @param existingMembers the subscription ids of the existing folders, by folder id
     * @param subscriptionIdOf finds the id of a subscription from its service id and url
     */
    fun planImport(
        items: List<FeedGroupItem>,
        existingGroups: List<FeedGroupEntity>,
        existingMembers: Map<Long, List<Long>>,
        subscriptionIdOf: (serviceId: Int, url: String) -> Long?
    ): List<FeedGroupImport> {
        val existingByName = existingGroups.associateBy { it.name.trim() }
        return items
            .filter { it.name.isNotBlank() }
            .sortedBy { it.sortOrder }
            .map { item ->
                val existing = existingByName[item.name.trim()]
                val imported = item.subscriptions.mapNotNull { subscriptionIdOf(it.serviceId, it.url) }
                val ids = (existing?.let { existingMembers[it.uid] }.orEmpty() + imported).distinct()
                FeedGroupImport(
                    existingId = existing?.uid,
                    name = item.name.trim(),
                    icon = existing?.icon ?: iconOf(item.iconId),
                    subscriptionIds = ids
                )
            }
    }

    private fun iconOf(id: Int) = FeedGroupIcon.entries.firstOrNull { it.id == id } ?: FeedGroupIcon.ALL
}
