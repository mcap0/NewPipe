/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.database.stream.model.StreamEntity

class PerleViewModel(context: Context, private val groupId: Long) : ViewModel() {
    private val repository = PerleRepository(context)

    private val mutableState = MutableLiveData<PerleState>()
    val stateLiveData: LiveData<PerleState> = mutableState

    private var cache: PerleCache? = null
    private var disposable: Disposable? = null

    init {
        load(forceRefresh = false)
    }

    /**
     * Shows the cached videos if they are less than 7 days old, otherwise downloads them again.
     */
    fun load(forceRefresh: Boolean) {
        disposable?.dispose()
        mutableState.value = PerleState.Loading(0, 0)

        val source = Single.fromCallable { PerleCacheHolder(repository.readCache(groupId)) }
            .flatMap { holder ->
                val cached = holder.cache
                if (!forceRefresh && cached != null && repository.isFresh(cached)) {
                    Single.just(cached)
                } else {
                    repository.fetch(groupId) { done, total ->
                        mutableState.postValue(PerleState.Loading(done, total))
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
     * Mixes the already downloaded videos again, without downloading anything.
     */
    fun shuffle() {
        val currentCache = cache ?: return
        disposable?.dispose()
        disposable = Single.fromCallable { buildState(currentCache) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ mutableState.value = it }, { mutableState.value = PerleState.Error(it) })
    }

    private fun buildState(cache: PerleCache): PerleState.Loaded {
        val watched = repository.watchedUrls()
        var uid = 0L
        val channels = cache.channels.map { videos ->
            // the uid is only used by the adapter to tell items apart
            videos.map { it.toStreamEntity(++uid) }.filter { it.url !in watched }
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
    }

    /** Wrapper needed because RxJava does not allow null values. */
    private class PerleCacheHolder(val cache: PerleCache?)

    companion object {
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
        val failedChannels: Int
    ) : PerleState

    data class Error(val error: Throwable) : PerleState
}
