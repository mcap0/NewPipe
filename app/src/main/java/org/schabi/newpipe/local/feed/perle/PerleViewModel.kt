/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity

class PerleViewModel(context: Context, groupId: Long) : ViewModel() {
    private val repository = PerleRepository(context)

    private val mutableState = MutableLiveData<PerleState>()
    val stateLiveData: LiveData<PerleState> = mutableState

    /** The feed group (folder) whose channels are shown, or [FeedGroupEntity.GROUP_ALL_ID]. */
    var groupId: Long = groupId
        private set

    private var cache: PerleCache? = null
    private var disposable: Disposable? = null

    // identifies the current load, so that late progress updates of a finished or replaced load
    // are ignored instead of hiding its result behind the progress bar
    private var loadGeneration = 0
    private val saveDisposables = CompositeDisposable()

    // a single thread, so that hide and undo are saved in the order they happened
    private val saveScheduler = Schedulers.single()

    init {
        load(forceRefresh = false)
    }

    /**
     * Shows the cached videos if they are less than 7 days old, otherwise downloads them again.
     */
    fun load(forceRefresh: Boolean) {
        disposable?.dispose()
        val generation = ++loadGeneration
        mutableState.value = PerleState.Loading(0, 0)

        val loadedGroupId = groupId
        val source = Single.fromCallable { PerleCacheHolder(repository.readCache(loadedGroupId)) }
            .flatMap { holder ->
                val cached = holder.cache
                if (!forceRefresh && cached != null && repository.isFresh(cached)) {
                    Single.just(cached)
                } else {
                    repository.fetch(loadedGroupId) { done, total ->
                        postProgress(generation, done, total)
                    }
                }
            }
            .doOnSuccess { cache = it }

        disposable = source
            .map { buildState(it) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ mutableState.value = it }, { mutableState.value = PerleState.Error(it) })
    }

    /**
     * Shows the progress on the main thread, but only while [generation] is still loading: the
     * last progress update can arrive after the result, which must not be replaced.
     */
    private fun postProgress(generation: Int, done: Int, total: Int) {
        AndroidSchedulers.mainThread().scheduleDirect {
            if (generation == loadGeneration && mutableState.value is PerleState.Loading) {
                mutableState.value = PerleState.Loading(done, total)
            }
        }
    }

    /**
     * Shows the videos of another feed group (folder), from the cache if it is recent.
     */
    fun selectGroup(newGroupId: Long) {
        if (newGroupId == groupId) {
            return
        }
        groupId = newGroupId
        cache = null
        load(forceRefresh = false)
    }

    /**
     * @return the folders the user can choose from, loaded in the background
     */
    fun loadFeedGroups(): Single<List<FeedGroupEntity>> {
        return Single.fromCallable { repository.feedGroups() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    /**
     * Mixes the already downloaded videos again, without downloading anything.
     */
    fun shuffle() {
        val currentCache = cache ?: return
        if (mutableState.value !is PerleState.Loaded) {
            return // do not interrupt a download
        }
        disposable?.dispose()
        disposable = Single.fromCallable { buildState(currentCache) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ mutableState.value = it }, { mutableState.value = PerleState.Error(it) })
    }

    /**
     * Hides the video now and every time Perle is opened again.
     *
     * @return the position the video had in the list, to be passed to [unhide] to undo
     */
    fun hide(stream: StreamEntity): Int {
        val loaded = mutableState.value as? PerleState.Loaded ?: return -1
        val position = loaded.items.indexOfFirst { it.url == stream.url }
        if (position < 0) {
            return -1
        }
        mutableState.value = loaded.copy(
            items = loaded.items.filterIndexed { i, _ -> i != position },
            scrollToTop = false
        )
        saveHidden(stream.url, true)
        return position
    }

    /**
     * Undoes [hide], putting the video back where it was.
     */
    fun unhide(stream: StreamEntity, position: Int) {
        val loaded = mutableState.value as? PerleState.Loaded ?: return
        if (loaded.items.none { it.url == stream.url }) {
            val items = loaded.items.toMutableList()
            items.add(position.coerceIn(0, items.size), stream)
            mutableState.value = loaded.copy(items = items, scrollToTop = false)
        }
        saveHidden(stream.url, false)
    }

    /**
     * Shows again all the videos hidden with [hide].
     */
    fun clearHidden() {
        saveDisposables.add(
            Completable.fromAction { repository.clearHidden() }
                .subscribeOn(saveScheduler)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    // do not interrupt a download, its result will include the videos anyway
                    { if (mutableState.value is PerleState.Loaded) shuffle() },
                    { Log.e(TAG, "Could not clear hidden videos", it) }
                )
        )
    }

    private fun saveHidden(url: String, hidden: Boolean) {
        saveDisposables.add(
            Completable.fromAction { repository.setHidden(url, hidden) }
                .subscribeOn(saveScheduler)
                .subscribe({}, { Log.e(TAG, "Could not save hidden video $url", it) })
        )
    }

    private fun buildState(cache: PerleCache): PerleState.Loaded {
        val excluded = repository.watchedUrls() + repository.hiddenUrls()
        var uid = 0L
        val channels = cache.channels.map { videos ->
            // the uid is only used by the adapter to tell items apart
            videos.map { it.toStreamEntity(++uid) }.filter { it.url !in excluded }
        }
        return PerleState.Loaded(
            PerleSelection.interleave(channels),
            cache.timestamp,
            cache.failedChannels
        )
    }

    override fun onCleared() {
        super.onCleared()
        disposable?.dispose()
        saveDisposables.dispose()
    }

    /** Wrapper needed because RxJava does not allow null values. */
    private class PerleCacheHolder(val cache: PerleCache?)

    companion object {
        private const val TAG = "PerleViewModel"

        fun getFactory(context: Context, groupId: Long) = viewModelFactory {
            initializer {
                PerleViewModel(context.applicationContext, groupId)
            }
        }
    }
}

sealed interface PerleState {
    data class Loading(val done: Int, val total: Int) : PerleState

    data class Loaded(
        val items: List<StreamEntity>,
        val timestamp: Long,
        val failedChannels: Int,
        val scrollToTop: Boolean = true
    ) : PerleState

    data class Error(val error: Throwable) : PerleState
}
