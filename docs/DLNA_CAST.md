# Cast to TV (DLNA/UPnP)

"Cast to TV" sends the video being watched in NewPipe to a smart TV or another DLNA
MediaRenderer on the same local network. It was written for TVs that phones cannot reach with
Chromecast or Miracast, e.g. Samsung TVs (Tizen) with Google Pixel phones.

It is a lightweight implementation of the few parts of UPnP that are needed, without an UPnP
library: about 1500 lines of Kotlin, most of them unit tested on the JVM.

## How to use it

1. Connect the phone and the TV to the same Wi-Fi network and turn the TV on.
2. Open a video and tap **Cast to TV** (cast icon, next to Download), or long press a video in
   any list and choose **Cast to TV**.
3. Choose the TV in the list. The first time, Samsung TVs show a popup asking to allow the
   device: accept it.
4. The controller opens: play/pause, seek bar, next, volume, stop, quality, audio track,
   subtitles and the queue. The notification has play/pause, stop and next, so the video keeps
   playing with NewPipe in the background.
5. After closing the controller, open it again from the **casting bar** at the bottom of the
   main screen, from the notification, or with **Cast to TV** on any video.
6. While a video is cast, **Cast to TV** on another video offers to play it now, to add it to
   the TV queue, to open the controls or to cast to another TV. At the end of a video the next
   one of the queue starts.

The position reached on the TV is saved in the history every few seconds and when casting
stops, like with the local player: the video continues from there on the TV or on the phone,
and casting a partly watched video starts where it was left (if "Resume playback" is enabled).

If something does not work: **Settings → Cast to TV → Cast to TV log → Copy log**, and paste
the log in a bug report. The same log is in logcat with the tag `DlnaCast`.

## Architecture

```
 phone                                                    TV (MediaRenderer)
 ─────                                                    ──────────────────
 CastDeviceDialog ── SSDP M-SEARCH (UDP multicast) ─────▶ 239.255.255.250:1900
                  ◀─ HTTP/1.1 200 OK, LOCATION: … ──────
                  ── GET device description (XML) ──────▶ friendlyName, controlURLs

 CastService ────── SOAP SetAVTransportURI + DIDL-Lite ─▶ AVTransport
             ────── SOAP Play/Pause/Seek/Stop ──────────▶
             ────── GetTransportInfo/GetPositionInfo ───▶ (every second)
             ────── GetVolume/SetVolume ────────────────▶ RenderingControl

 CastProxyServer ◀─ GET/HEAD http://phone:port/v/<token>.mp4 (Range) ── TV player
       │
       └─ OkHttpUpstream ── POST/Range ──▶ googlevideo.com (HTTPS)
```

Code in `app/src/main/java/org/schabi/newpipe/cast/`:

| File | Role |
| --- | --- |
| `dlna/Ssdp.kt` | M-SEARCH request (ST `urn:schemas-upnp-org:device:MediaRenderer:1` and `…:service:AVTransport:1`), answer parsing, 4 second search sending the request three times |
| `dlna/DlnaDevice.kt` | Device description parsing: `friendlyName`, AVTransport and RenderingControl `controlURL`s, embedded devices, `URLBase` and relative URLs (RFC 3986) |
| `dlna/DlnaDiscovery.kt` | SSDP search + parallel download of the descriptions, duplicates removed by UDN |
| `dlna/Soap.kt`, `dlna/DlnaClient.kt` | SOAP envelopes, `SOAPACTION` header, answer and `UPnPError` parsing; the AVTransport and RenderingControl actions |
| `dlna/DidlLite.kt` | DIDL-Lite metadata (title, creator, `object.item.videoItem`, `res` with `protocolInfo="http-get:*:video/mp4:*"` and duration), UPnP time format |
| `dlna/XmlUtils.kt` | XML escaping (`&`, `<`, `>`, `"`, `'`; emoji kept, invalid characters removed) and safe parsing (no DTD, no external entities) |
| `dlna/LanHttp.kt` | Minimal HTTP/1.1 client restricted to local addresses (see below) |
| `dlna/CastStreamSelector.kt` | Stream choice: progressive MP4 with audio up to 720p, or H.264 + AAC streams to mux up to 1080p; the qualities and audio tracks that can be chosen |
| `dlna/SrtConverter.kt` | TTML and WebVTT subtitles to SRT, shifted for streams started in the middle |
| `dlna/CastLog.kt` | Circular in-memory log (1000 entries) shown in the settings |
| `proxy/CastProxyServer.kt`, `proxy/HttpRange.kt` | Local HTTP server: `GET`, `HEAD`, single `Range` requests answered with `206 Partial Content`, DLNA headers |
| `OkHttpUpstream.kt` | Downloads the video for the proxy, like NewPipe's `YoutubeHttpDataSource` |
| `CastItem.kt` | A video of the queue, with its qualities, audio tracks and subtitles |
| `CastService.kt` | Foreground service (`connectedDevice`) owning the session and the queue, polling, saving the position in the history, notification, Wi-Fi and wake locks |
| `CastDeviceDialog.kt`, `CastControllerActivity.kt`, `CastLauncher.kt`, `CastBar.kt` | User interface |
| `../settings/CastSettingsFragment.kt` | "Send direct link" option and the log |

Everything in `dlna/` and `proxy/` is plain Kotlin without Android dependencies, so it is
covered by JVM unit tests in `app/src/test/java/org/schabi/newpipe/cast/`.

### Why a proxy

The TV gets `http://<phone ip>:<port>/v/<random token>.mp4` instead of the googlevideo URL:

- many DLNA renderers cannot play HTTPS URLs;
- googlevideo URLs are more than 1000 characters long, which some renderers cannot handle;
- YouTube expects the requests NewPipe's player makes (POST body, `rn` parameter, client
  specific `User-Agent`); a TV making a plain GET is often answered with HTTP 403.

Only registered URLs are served, under a random 96 bit token, so the proxy cannot be used as an
open proxy by other devices on the network. The proxy runs only during a session and listens
only on the Wi-Fi address. **Settings → Cast to TV → Send direct link** gives the TV the
original URL instead; it only helps with TVs that play HTTPS and with services that do not
need special requests.

While a session runs, a Wi-Fi lock and a partial wake lock keep the phone able to serve the
video with the screen off.

### Why not OkHttp for the TV

Apps targeting Android 9+ cannot use cleartext HTTP with OkHttp or `HttpURLConnection` unless
cleartext traffic is allowed for the whole app, and the network security config cannot limit
this to private IP ranges. DLNA devices only speak HTTP, so `LanHttp` (a ~150 lines HTTP/1.1
client with chunked encoding support) is used for UPnP only, and it refuses any address that is
not private, link-local or loopback. OkHttp is still used to download the video over HTTPS.

### Permissions

- `CHANGE_WIFI_MULTICAST_STATE`: `WifiManager.MulticastLock` during the search, otherwise many
  phones drop the SSDP answers;
- `ACCESS_WIFI_STATE`: Wi-Fi lock during the session;
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`: the foreground service type for controlling an
  external device over the network.

No location or nearby devices permission is needed (the SSID is never read).

## Limits

- **720p maximum.** DLNA renderers need one file with both audio and video. YouTube only
  provides such "progressive" MP4 streams at low resolutions (today usually only 360p, itag
  18); higher resolutions only exist as separate DASH video and audio streams. When no
  progressive MP4 exists, casting is not possible and a message is shown.
- Only regular videos: live streams (HLS/DASH) and audio-only content are not offered.
- Phone and TV must be on the same network, and the network must allow multicast and
  device-to-device traffic (guest networks and "AP isolation" block it).
- One TV at a time.
- Subtitles are sent as SRT, announced with the Samsung `sec:CaptionInfoEx` element and
  `CaptionInfo.sec` header and with a second `res` of type `text/srt`. Some TVs only show them
  after enabling subtitles in their own menu; others ignore external subtitles.
- Changing quality, audio track or subtitles sends the video again from the same position.
- Another audio track than the original one is only available as a separate stream, so it is
  always muxed into MPEG-TS.

## Next steps

1. **1080p and above (phase 2):** mux the DASH video-only and audio streams in the proxy into a
   single fragmented MP4 (or MPEG-TS) on the fly. Seeking then needs either time based seeking
   (`TimeSeekRange.dlna.org`) or an index of the output.
2. Queue: use `SetNextAVTransportURI` for gapless changes between videos.
3. GENA event subscriptions (`LastChange`) instead of polling every second.
4. A media session, so that the phone volume keys control the TV volume.

## Testing

Unit tests (`./gradlew testDebugUnitTest`) cover SSDP parsing, device descriptions (relative
URLs and `URLBase`), DIDL-Lite generation and escaping, SOAP envelopes and errors, the SOAP
client against a fake renderer, the LAN HTTP client, stream selection, the log and the proxy
(GET, HEAD, open, closed and suffix ranges, 404/405/416/502).

There are no automated tests with a real TV. When reporting a problem please include the log
(Settings → Cast to TV → Cast to TV log → Copy log) and the TV model.
