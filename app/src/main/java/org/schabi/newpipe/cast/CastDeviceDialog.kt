/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.cast.dlna.CastLog
import org.schabi.newpipe.cast.dlna.CastMedia
import org.schabi.newpipe.cast.dlna.DlnaDevice
import org.schabi.newpipe.cast.dlna.DlnaDiscovery
import org.schabi.newpipe.cast.dlna.LocalNetwork
import org.schabi.newpipe.databinding.DialogCastDevicesBinding
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Searches the TVs on the local network, lists them as they are found and starts casting on
 * the chosen one.
 */
class CastDeviceDialog : DialogFragment() {
    private var _binding: DialogCastDevicesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ArrayAdapter<String>
    private val devices = ArrayList<DlnaDevice>()
    private var discovery: Disposable? = null
    private var isSearching = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogCastDevicesBinding.inflate(LayoutInflater.from(requireContext()))
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ArrayList())
        binding.castDeviceList.adapter = adapter
        binding.castDeviceList.setOnItemClickListener { _, _, position, _ ->
            devices.getOrNull(position)?.let { onDeviceChosen(it) }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.cast_to_tv)
            .setView(binding.root)
            .setNeutralButton(R.string.cast_retry, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // set here, so that pressing the button does not close the dialog
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { search() }
            updateViews()
        }
        search()
        return dialog
    }

    override fun onDestroy() {
        discovery?.dispose()
        _binding = null
        super.onDestroy()
    }

    private fun search() {
        if (isSearching) {
            return
        }
        devices.clear()
        adapter.clear()
        isSearching = true
        updateViews()

        val appContext = requireContext().applicationContext
        discovery = Observable.create<DlnaDevice> { rawEmitter ->
            // devices are found on several threads at once
            val emitter = rawEmitter.serialize()
            val local = LocalNetwork.find()
            if (local == null) {
                emitter.onError(NoLocalNetworkException())
                return@create
            }
            CastLog.d("Searching TVs on ${local.networkInterface.name} (${local.address.hostAddress})")
            val lock = acquireMulticastLock(appContext)
            try {
                DlnaDiscovery.discover(local.networkInterface) { emitter.onNext(it) }
            } finally {
                lock?.takeIf { it.isHeld }?.release()
            }
            emitter.onComplete()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { device ->
                    devices.add(device)
                    adapter.add(device.friendlyName)
                },
                { error ->
                    isSearching = false
                    if (error is NoLocalNetworkException) {
                        CastLog.w("No local network address: the phone is not on Wi-Fi")
                        showMessage(R.string.cast_error_no_wifi)
                    } else {
                        CastLog.e("Search failed", error)
                        showMessage(R.string.cast_no_devices)
                    }
                },
                {
                    isSearching = false
                    CastLog.d("Search finished, ${devices.size} renderer(s) found")
                    updateViews()
                }
            )
    }

    private fun updateViews() {
        val binding = _binding ?: return
        binding.castSearching.isVisible = isSearching
        binding.castMessage.isVisible = !isSearching && devices.isEmpty()
        binding.castMessage.setText(R.string.cast_no_devices)
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !isSearching
    }

    private fun showMessage(message: Int) {
        updateViews()
        _binding?.castMessage?.apply {
            isVisible = true
            setText(message)
        }
    }

    private fun onDeviceChosen(device: DlnaDevice) {
        val context = requireContext()
        val arguments = requireArguments()

        @Suppress("DEPRECATION")
        val media = arguments.getSerializable(ARG_MEDIA) as CastMedia
        CastService.start(context, device, media)
        markAsViewed(context.applicationContext, arguments.getInt(ARG_SERVICE_ID), arguments.getString(ARG_URL))
        startActivity(Intent(context, CastControllerActivity::class.java))
        dismiss()
    }

    private class NoLocalNetworkException : Exception()

    companion object {
        const val TAG = "CastDeviceDialog"
        private const val ARG_MEDIA = "media"
        private const val ARG_SERVICE_ID = "service_id"
        private const val ARG_URL = "url"

        fun newInstance(media: CastMedia, serviceId: Int, url: String) = CastDeviceDialog().apply {
            arguments = bundleOf(ARG_MEDIA to media, ARG_SERVICE_ID to serviceId, ARG_URL to url)
        }

        /** Without this lock many phones drop incoming multicast and SSDP answers. */
        private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
            return try {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.createMulticastLock("NewPipe:CastDiscovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                CastLog.w("Could not acquire the multicast lock", e)
                null
            }
        }

        /** Adds the video to the watch history, as if it was played in NewPipe. */
        private fun markAsViewed(context: Context, serviceId: Int, url: String?) {
            if (url == null) {
                return
            }
            // the info is already in the cache, it was loaded to show the video
            ExtractorHelper.getStreamInfo(serviceId, url, false)
                .flatMapMaybe { HistoryRecordManager(context).onViewed(it) }
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { },
                    { Log.w(TAG, "Could not add the cast video to the history", it) }
                )
        }
    }
}
