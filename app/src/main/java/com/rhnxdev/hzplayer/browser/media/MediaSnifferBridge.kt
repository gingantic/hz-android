package com.rhnxdev.hzplayer.browser.media

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log

class MediaSnifferBridge(
    private val onMediaDetected: (url: String, title: String, mimeType: String, headers: Map<String, String>) -> Unit,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onPipRequested: () -> Unit = {},
) {
    @JavascriptInterface
    fun onMediaFound(url: String, title: String, mimeType: String) {
        if (url.isNotBlank()) {
            onMediaDetected(url, title, mimeType, emptyMap())
        }
    }

    /** Called from JS whenever a page <video> starts/stops playing. */
    @JavascriptInterface
    fun onVideoPlaybackChanged(isPlaying: Boolean) {
        onPlaybackStateChanged(isPlaying)
    }

    /** Called from JS when the page invokes the web Picture-in-Picture API. */
    @JavascriptInterface
    fun onEnterPipRequested() {
        onPipRequested()
    }

    @JavascriptInterface
    fun onMediaFoundWithHeaders(url: String, title: String, mimeType: String, headersJson: String) {
        if (url.isNotBlank()) {
            val headersMap = parseHeadersJson(headersJson)
            onMediaDetected(url, title, mimeType, headersMap)
        }
    }

    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        try {
            val obj = org.json.JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JS headers JSON: ${e.message}")
        }
        return result
    }

    companion object {
        private const val TAG = "MediaSnifferBridge"
        const val INTERFACE_NAME = "HzMediaSniffer"

        /**
         * Injects DOM media listeners for HTML5 video/audio elements, fetch, and XMLHttpRequest.
         */
        fun injectSnifferJs(webView: WebView) {
            val js = """
                (function() {
                    if (window.__hzMediaSnifferInjected) return;
                    window.__hzMediaSnifferInjected = true;

                    function notifyMedia(rawUrl, mimeType, headersObj) {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        if (rawUrl.startsWith('blob:') || rawUrl.startsWith('data:')) return;
                        try {
                            var resolvedUrl = rawUrl;
                            if (!rawUrl.startsWith('http://') && !rawUrl.startsWith('https://') && !rawUrl.startsWith('//')) {
                                resolvedUrl = new URL(rawUrl, window.location.href).href;
                            } else if (rawUrl.startsWith('//')) {
                                resolvedUrl = window.location.protocol + rawUrl;
                            }
                            if (window.${INTERFACE_NAME}) {
                                var headersJson = headersObj ? JSON.stringify(headersObj) : '';
                                if (window.${INTERFACE_NAME}.onMediaFoundWithHeaders) {
                                    window.${INTERFACE_NAME}.onMediaFoundWithHeaders(resolvedUrl, document.title || '', mimeType || '', headersJson);
                                } else {
                                    window.${INTERFACE_NAME}.onMediaFound(resolvedUrl, document.title || '', mimeType || '');
                                }
                            }
                        } catch(e) {}
                    }

                    function isMediaCandidateUrl(url) {
                        if (!url || typeof url !== 'string') return false;
                        var lower = url.toLowerCase();

                        // Disguised HLS playlists and master streams
                        var isDisguisedHls = lower.indexOf('cl-master') !== -1 || lower.indexOf('master') !== -1 ||
                                             lower.indexOf('playlist') !== -1 || lower.indexOf('manifest') !== -1 ||
                                             lower.indexOf('index-f') !== -1 || lower.indexOf('stream-f') !== -1 ||
                                             lower.indexOf('vnd.apple.mpegurl') !== -1;

                        // Discard static non-media assets UNLESS matching a master stream pattern
                        if (!isDisguisedHls && (lower.indexOf('.woff') !== -1 || lower.indexOf('.woff2') !== -1 ||
                            lower.indexOf('.css') !== -1 || lower.indexOf('.png') !== -1 || lower.indexOf('.jpg') !== -1 || lower.indexOf('.ttf') !== -1)) {
                            return false;
                        }
                        if (lower.indexOf('.js') !== -1 && !isDisguisedHls) return false;

                        return isDisguisedHls ||
                               lower.indexOf('.m3u8') !== -1 ||
                               lower.indexOf('.mpd') !== -1 ||
                               lower.indexOf('.mp4') !== -1 ||
                               lower.indexOf('.webm') !== -1 ||
                               lower.indexOf('.mkv') !== -1 ||
                               lower.indexOf('/hls/') !== -1 ||
                               lower.indexOf('/dash/') !== -1 ||
                               lower.indexOf('videoplayback') !== -1 ||
                               (lower.indexOf('format=') !== -1 && (lower.indexOf('m3u8') !== -1 || lower.indexOf('mp4') !== -1));
                    }

                    // 1. Scan existing <video>, <audio>, <source> elements, <iframe> embeds, and network performance resources
                    function scanPerformanceResources() {
                        try {
                            if (window.performance && typeof window.performance.getEntriesByType === 'function') {
                                var resources = window.performance.getEntriesByType('resource');
                                for (var r = 0; r < resources.length; r++) {
                                    var resName = resources[r].name;
                                    if (resName && isMediaCandidateUrl(resName)) {
                                        notifyMedia(resName, '');
                                    }
                                }
                            }
                        } catch(e) {}
                    }

                    function scanMediaElements() {
                        try {
                            var elements = document.querySelectorAll('video, audio, source');
                            for (var i = 0; i < elements.length; i++) {
                                var el = elements[i];
                                var src = el.src || el.currentSrc;
                                if (src) notifyMedia(src, el.type || '');
                            }
                        } catch(e) {}

                        try {
                            var iframes = document.querySelectorAll('iframe');
                            for (var k = 0; k < iframes.length; k++) {
                                var iframe = iframes[k];
                                var iframeSrc = iframe.src;
                                if (iframeSrc && iframeSrc.indexOf('about:blank') === -1) {
                                    if (isMediaCandidateUrl(iframeSrc)) {
                                        notifyMedia(iframeSrc, 'text/html');
                                    }
                                }
                                try {
                                    if (iframe.contentDocument) {
                                        var innerVideos = iframe.contentDocument.querySelectorAll('video, audio, source');
                                        for (var j = 0; j < innerVideos.length; j++) {
                                            var v = innerVideos[j];
                                            var vsrc = v.src || v.currentSrc;
                                            if (vsrc) notifyMedia(vsrc, v.type || '');
                                        }
                                    }
                                } catch(err) {}
                            }
                        } catch(e) {}

                        scanPerformanceResources();
                    }

                    // 2. Intercept HTMLMediaElement property setters & lifecycle methods
                    try {
                        var origPlay = HTMLMediaElement.prototype.play;
                        HTMLMediaElement.prototype.play = function() {
                            if (this.src) notifyMedia(this.src, this.type || '');
                            if (this.currentSrc) notifyMedia(this.currentSrc, this.type || '');
                            return origPlay.apply(this, arguments);
                        };

                        var origLoad = HTMLMediaElement.prototype.load;
                        HTMLMediaElement.prototype.load = function() {
                            if (this.src) notifyMedia(this.src, this.type || '');
                            if (this.currentSrc) notifyMedia(this.currentSrc, this.type || '');
                            return origLoad.apply(this, arguments);
                        };
                    } catch(e) {}

                    try {
                        var mediaSrcProp = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                        if (mediaSrcProp && mediaSrcProp.set) {
                            var origMediaSrcSet = mediaSrcProp.set;
                            Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                                get: mediaSrcProp.get,
                                set: function(val) {
                                    if (val) notifyMedia(val, this.type || '');
                                    return origMediaSrcSet.call(this, val);
                                },
                                configurable: true,
                                enumerable: true
                            });
                        }
                    } catch(e) {}

                    try {
                        var sourceSrcProp = Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype, 'src');
                        if (sourceSrcProp && sourceSrcProp.set) {
                            var origSourceSrcSet = sourceSrcProp.set;
                            Object.defineProperty(HTMLSourceElement.prototype, 'src', {
                                get: sourceSrcProp.get,
                                set: function(val) {
                                    if (val) notifyMedia(val, this.type || '');
                                    return origSourceSrcSet.call(this, val);
                                },
                                configurable: true,
                                enumerable: true
                            });
                        }
                    } catch(e) {}

                    // Helper to scan text/JSON response bodies for embedded video stream URLs or manifest signatures
                    function sniffResponseContent(text, baseUrl, headersObj) {
                        if (!text || typeof text !== 'string') return;
                        try {
                            // 1. If response body contains HLS manifest header (#EXTM3U) or DASH MPD header (<MPD)
                            if (text.indexOf('#EXTM3U') !== -1 || text.indexOf('#EXT-X-STREAM-INF') !== -1 || text.indexOf('<MPD') !== -1) {
                                notifyMedia(baseUrl, text.indexOf('<MPD') !== -1 ? 'application/dash+xml' : 'application/x-mpegURL', headersObj);
                            }

                            // 2. Scan response text/JSON for embedded video URLs (e.g. "https://.../master.m3u8", "file": "...", "url": "...")
                            var streamUrlRegex = /https?:\/\/[^\s"'<>\\]+\.(m3u8|mpd|mp4|webm|mkv)(\?[^\s"'<>]*)?/gi;
                            var match;
                            while ((match = streamUrlRegex.exec(text)) !== null) {
                                var foundUrl = match[0];
                                if (foundUrl) {
                                    notifyMedia(foundUrl, '', headersObj);
                                }
                            }
                        } catch(e) {}
                    }

                    // 3. Intercept fetch API for request and response body sniffing
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            var url = arguments[0];
                            var options = arguments[1] || {};
                            var reqUrl = (typeof url === 'string') ? url : (url && url.url ? url.url : '');
                            var reqHeaders = {};
                            if (options.headers) {
                                if (typeof options.headers.forEach === 'function') {
                                    options.headers.forEach(function(v, k) { reqHeaders[k] = v; });
                                } else if (typeof options.headers === 'object') {
                                    reqHeaders = options.headers;
                                }
                            }
                            if (reqUrl && isMediaCandidateUrl(reqUrl)) {
                                notifyMedia(reqUrl, '', reqHeaders);
                            }

                            return origFetch.apply(this, arguments).then(function(response) {
                                try {
                                    if (response && response.clone) {
                                        var cType = (response.headers && response.headers.get('content-type')) || '';
                                        if (cType.indexOf('video') !== -1 || cType.indexOf('mpegurl') !== -1 || cType.indexOf('dash') !== -1) {
                                            notifyMedia(response.url || reqUrl, cType, reqHeaders);
                                        } else {
                                            response.clone().text().then(function(text) {
                                                sniffResponseContent(text, response.url || reqUrl, reqHeaders);
                                            }).catch(function(){});
                                        }
                                    }
                                } catch(e) {}
                                return response;
                            });
                        };
                    }

                    // 4. Intercept XMLHttpRequest for request and response body sniffing
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__hzUrl = url;
                        this.__hzHeaders = {};
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                        if (!this.__hzHeaders) this.__hzHeaders = {};
                        this.__hzHeaders[header] = value;
                        return origSetHeader.apply(this, arguments);
                    };

                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function() {
                        var xhr = this;
                        if (typeof xhr.__hzUrl === 'string' && isMediaCandidateUrl(xhr.__hzUrl)) {
                            notifyMedia(xhr.__hzUrl, '', xhr.__hzHeaders || {});
                        }
                        try {
                            var origOnReady = xhr.onreadystatechange;
                            xhr.onreadystatechange = function() {
                                if (xhr.readyState === 4) {
                                    try {
                                        var cType = xhr.getResponseHeader('content-type') || '';
                                        if (cType.indexOf('video') !== -1 || cType.indexOf('mpegurl') !== -1 || cType.indexOf('dash') !== -1) {
                                            notifyMedia(xhr.__hzUrl, cType, xhr.__hzHeaders || {});
                                        }
                                        if (xhr.responseText) {
                                            sniffResponseContent(xhr.responseText, xhr.__hzUrl, xhr.__hzHeaders || {});
                                        }
                                    } catch(e) {}
                                }
                                if (origOnReady) return origOnReady.apply(this, arguments);
                            };
                        } catch(e) {}
                        return origSend.apply(this, arguments);
                    };

                    // 5. Hook URL.createObjectURL for MediaSource/Blob streams
                    try {
                        var origCreateObjectURL = URL.createObjectURL;
                        if (origCreateObjectURL) {
                            URL.createObjectURL = function(obj) {
                                var blobUrl = origCreateObjectURL.apply(this, arguments);
                                scanMediaElements();
                                return blobUrl;
                            };
                        }
                    } catch(e) {}

                    // 6. Anti-Popunder & Transparent Overlay Suppressor
                    function cleanupPopunderOverlays() {
                        try {
                            var elements = document.querySelectorAll('div, iframe');
                            for (var i = 0; i < elements.length; i++) {
                                var el = elements[i];
                                if (el.tagName === 'VIDEO' || el.querySelector('video')) continue;
                                var style = window.getComputedStyle(el);
                                if (style.position === 'fixed' || style.position === 'absolute') {
                                    var opacity = parseFloat(style.opacity);
                                    var w = el.offsetWidth || 0;
                                    var h = el.offsetHeight || 0;
                                    var isFullScreen = (w >= window.innerWidth * 0.8 && h >= window.innerHeight * 0.8);
                                    // Only strip near-invisible click-catchers (popunder traps).
                                    // Visible fullscreen overlays with high z-index are legit UI
                                    // (site captchas, cookie banners, login modals) — removing
                                    // them made custom captchas vanish right after a click.
                                    if (isFullScreen && opacity <= 0.05) {
                                        if (el.parentNode) el.parentNode.removeChild(el);
                                    }
                                }
                            }
                        } catch(e) {}
                    }

                    document.addEventListener('click', function() {
                        setTimeout(cleanupPopunderOverlays, 100);
                        setTimeout(scanMediaElements, 300);
                    }, true);
                    setInterval(function() {
                        cleanupPopunderOverlays();
                        scanMediaElements();
                    }, 1500);

                    // 7. Video playback state tracking + web PiP API bridge.
                    //    WebView doesn't implement requestPictureInPicture(), so site
                    //    PiP buttons silently no-op — polyfill it to enter native PiP.
                    var hzPlayingVideos = 0;
                    function notifyPlayback() {
                        try {
                            if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onVideoPlaybackChanged) {
                                window.${INTERFACE_NAME}.onVideoPlaybackChanged(hzPlayingVideos > 0);
                            }
                        } catch(e) {}
                    }
                    function recountPlayingVideos() {
                        try {
                            var count = 0;
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                var v = vids[i];
                                if (!v.paused && !v.ended && v.readyState > 2) count++;
                            }
                            hzPlayingVideos = count;
                            notifyPlayback();
                        } catch(e) {}
                    }
                    document.addEventListener('play', recountPlayingVideos, true);
                    document.addEventListener('playing', recountPlayingVideos, true);
                    document.addEventListener('pause', recountPlayingVideos, true);
                    document.addEventListener('ended', recountPlayingVideos, true);
                    document.addEventListener('emptied', recountPlayingVideos, true);
                    setInterval(recountPlayingVideos, 2000);
                    recountPlayingVideos();

                    try {
                        HTMLVideoElement.prototype.requestPictureInPicture = function() {
                            var vid = this;
                            // Fullscreen the video first (routes into onShowCustomView)
                            // so the native PiP window shows only the video, not the page
                            try {
                                if (vid.requestFullscreen) {
                                    var p = vid.requestFullscreen();
                                    if (p && p.catch) p.catch(function(){});
                                } else if (vid.webkitRequestFullscreen) {
                                    vid.webkitRequestFullscreen();
                                }
                            } catch(e) {}
                            try {
                                if (window.${INTERFACE_NAME} && window.${INTERFACE_NAME}.onEnterPipRequested) {
                                    window.${INTERFACE_NAME}.onEnterPipRequested();
                                }
                            } catch(e) {}
                            return Promise.resolve({});
                        };
                        Object.defineProperty(document, 'pictureInPictureEnabled', {
                            get: function() { return true; },
                            configurable: true
                        });
                        Object.defineProperty(HTMLVideoElement.prototype, 'disablePictureInPicture', {
                            get: function() { return false; },
                            set: function(v) {},
                            configurable: true
                        });
                    } catch(e) {}

                    // Initial scan & dynamic mutation observer & media event listeners
                    scanMediaElements();
                    cleanupPopunderOverlays();
                    var observer = new MutationObserver(function() {
                        scanMediaElements();
                        cleanupPopunderOverlays();
                    });
                    if (document.body) {
                        observer.observe(document.body, { childList: true, subtree: true });
                    } else {
                        document.addEventListener('DOMContentLoaded', function() {
                            if (document.body) observer.observe(document.body, { childList: true, subtree: true });
                        });
                    }
                })();
            """.trimIndent()

            webView.post {
                webView.evaluateJavascript(js, null)
            }
        }
    }
}
