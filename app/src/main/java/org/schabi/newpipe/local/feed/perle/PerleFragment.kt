/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.local.feed.perle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.OnItemLongClickListener
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.databinding.FragmentPerleBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.info_list.dialog.StreamDialogEntry
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout

/**
 * Shows the most viewed videos of the channels in a feed group, giving the same weight to
 * every channel, so that channels which rarely upload are not buried in the feed.
 */
class PerleFragment : BaseFragment() {
    private var _binding: FragmentPerleBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: PerleViewModel
    private lateinit var groupAdapter: GroupieAdapter
    private val disposables = CompositeDisposable()

    private var groupId = FeedGroupEntity.GROUP_ALL_ID
    private var groupName = ""

    /** The subtitle this fragment put in the action bar, to remove only that one. */
    private var shownSubtitle: CharSequence? = null

    init {
        setHasOptionsMenu(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getLong(KEY_GROUP_ID, FeedGroupEntity.GROUP_ALL_ID)
            ?: FeedGroupEntity.GROUP_ALL_ID
        groupName = arguments?.getString(KEY_GROUP_NAME) ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_perle, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        _binding = FragmentPerleBinding.bind(rootView)
        super.onViewCreated(rootView, savedInstanceState)

        groupAdapter = GroupieAdapter().apply {
            setOnItemClickListener(listenerStreamItem)
            setOnItemLongClickListener(listenerStreamItem)
            spanCount = if (shouldUseGridLayout(context)) getGridSpanCountStreams(context) else 1
        }
        binding.perleItemsList.layoutManager = GridLayoutManager(requireContext(), groupAdapter.spanCount).apply {
            spanSizeLookup = groupAdapter.spanSizeLookup
        }
        binding.perleItemsList.adapter = groupAdapter

        val factory = PerleViewModel.getFactory(requireContext(), groupId)
        viewModel = ViewModelProvider(this, factory)[PerleViewModel::class.java]
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(::handleState) }
    }

    override fun onDestroyView() {
        disposables.clear()
        binding.perleItemsList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // /////////////////////////////////////////////////////////////////////////
    // Menu
    // /////////////////////////////////////////////////////////////////////////

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        activity?.supportActionBar?.let { actionBar ->
            actionBar.setDisplayShowTitleEnabled(true)
            actionBar.setTitle(R.string.perle_title)
            showSubtitle(actionBar)
        }
        inflater.inflate(R.menu.menu_perle_fragment, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_item_perle_shuffle -> {
                viewModel.shuffle()
                true
            }

            R.id.menu_item_perle_refresh -> {
                viewModel.load(forceRefresh = true)
                true
            }

            R.id.menu_item_perle_group -> {
                showGroupChooser()
                true
            }

            R.id.menu_item_perle_show_hidden -> {
                viewModel.clearHidden()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroyOptionsMenu() {
        super.onDestroyOptionsMenu()
        // this can be called when the fragment is no longer attached, so no resources are used
        val actionBar = activity?.supportActionBar
        if (actionBar != null && shownSubtitle != null && actionBar.subtitle == shownSubtitle) {
            actionBar.subtitle = null
        }
        shownSubtitle = null
    }

    private fun showSubtitle(actionBar: ActionBar) {
        val subtitle = groupName.ifEmpty { getString(R.string.perle_all_subscriptions) }
        actionBar.subtitle = subtitle
        shownSubtitle = subtitle
    }

    /**
     * Lets the user choose which folder (feed group) Perle shows, or all the subscriptions.
     */
    private fun showGroupChooser() {
        disposables.add(
            viewModel.loadFeedGroups().subscribe(
                { groups ->
                    val context = context ?: return@subscribe
                    val names = listOf(getString(R.string.perle_all_subscriptions)) + groups.map { it.name }
                    val ids = listOf(FeedGroupEntity.GROUP_ALL_ID) + groups.map { it.uid }
                    AlertDialog.Builder(context)
                        .setTitle(R.string.perle_choose_group)
                        .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(groupId).coerceAtLeast(0)) { dialog, which ->
                            dialog.dismiss()
                            selectGroup(ids[which], if (which == 0) "" else names[which])
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                { ErrorUtil.showSnackbar(this, ErrorInfo(it, UserAction.REQUESTED_FEED, "Loading feed groups")) }
            )
        )
    }

    private fun selectGroup(newGroupId: Long, newGroupName: String) {
        groupId = newGroupId
        groupName = newGroupName
        // so that the same folder is shown after the fragment is recreated
        arguments = bundleOf(KEY_GROUP_ID to groupId, KEY_GROUP_NAME to groupName)
        if (isAdded) {
            activity?.supportActionBar?.let(::showSubtitle)
        }
        viewModel.selectGroup(groupId)
    }

    // /////////////////////////////////////////////////////////////////////////
    // State handling
    // /////////////////////////////////////////////////////////////////////////

    private fun handleState(state: PerleState) {
        binding.perleLoadingView.isVisible = state is PerleState.Loading
        binding.perleItemsList.isVisible = state is PerleState.Loaded && state.items.isNotEmpty()
        binding.perleEmptyText.isVisible =
            (state is PerleState.Loaded && state.items.isEmpty()) || state is PerleState.Error
        binding.perleInfoText.isVisible = state is PerleState.Loaded

        when (state) {
            is PerleState.Loading -> {
                binding.perleProgressBar.isIndeterminate = state.total <= 0
                binding.perleProgressBar.max = state.total
                binding.perleProgressBar.progress = state.done
                binding.perleProgressText.text = if (state.total > 0) {
                    getString(R.string.perle_progress, state.done, state.total)
                } else {
                    getString(R.string.perle_loading)
                }
            }

            is PerleState.Loaded -> handleLoaded(state)

            is PerleState.Error -> ErrorUtil.showSnackbar(
                this,
                ErrorInfo(state.error, UserAction.REQUESTED_FEED, "Loading Perle")
            )
        }
    }

    private fun handleLoaded(state: PerleState.Loaded) {
        val itemVersion = when (getItemViewMode(requireContext())) {
            ItemViewMode.GRID -> StreamItem.ItemVersion.GRID
            ItemViewMode.CARD -> StreamItem.ItemVersion.CARD
            else -> StreamItem.ItemVersion.NORMAL
        }
        groupAdapter.update(
            state.items.map { StreamItem(StreamWithState(it, null), itemVersion) }
        )
        if (state.scrollToTop) {
            binding.perleItemsList.scrollToPosition(0)
        }

        val updated = Localization.relativeTime(
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(state.timestamp), ZoneId.systemDefault())
        )
        binding.perleInfoText.text = if (state.failedChannels > 0) {
            getString(R.string.perle_info_failed, updated, state.failedChannels)
        } else {
            getString(R.string.perle_info, updated)
        }
    }

    private val listenerStreamItem = object : OnItemClickListener, OnItemLongClickListener {
        override fun onItemClick(item: Item<*>, view: View) {
            if (item is StreamItem) {
                val stream = item.streamWithState.stream
                NavigationHelper.openVideoDetailFragment(
                    requireContext(),
                    fm,
                    stream.serviceId,
                    stream.url,
                    stream.title,
                    null,
                    false
                )
            }
        }

        override fun onItemLongClick(item: Item<*>, view: View): Boolean {
            val context = context
            val activity = getActivity()
            if (item !is StreamItem || context == null || activity == null) {
                return false
            }
            val stream = item.streamWithState.stream
            InfoItemDialog.Builder(activity, context, this@PerleFragment, stream.toStreamInfoItem())
                .addEntry(StreamDialogEntry(R.string.perle_hide) { _, _ -> hide(stream) })
                .create()
                .show()
            return true
        }
    }

    private fun hide(stream: StreamEntity) {
        val position = viewModel.hide(stream)
        if (position >= 0) {
            Snackbar.make(binding.root, R.string.perle_hidden, Snackbar.LENGTH_LONG)
                .setAction(R.string.perle_undo) { viewModel.unhide(stream, position) }
                .show()
        }
    }

    companion object {
        private const val KEY_GROUP_ID = "ARG_GROUP_ID"
        private const val KEY_GROUP_NAME = "ARG_GROUP_NAME"

        @JvmStatic
        fun open(fragmentManager: FragmentManager, groupId: Long, groupName: String?) {
            val fragment = PerleFragment()
            fragment.arguments = bundleOf(KEY_GROUP_ID to groupId, KEY_GROUP_NAME to groupName)
            fragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.animator.custom_fade_in,
                    R.animator.custom_fade_out,
                    R.animator.custom_fade_in,
                    R.animator.custom_fade_out
                )
                .replace(R.id.fragment_holder, fragment)
                .addToBackStack(null)
                .commit()
        }
    }
}
