package com.rhnxdev.hzplayer.browser.media

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log

class MediaSnifferBridge(
    private val onMediaDetected: (url: String, title: String, mimeType: String, headers: Map<String, String>) -> Unit
) {
    @JavascriptInterface
    fun onMediaFound(url: String, title: String, mimeType: String) {
        if (url.isNotBlank()) {
            onMediaDetected(url, title, mimeType, emptyMap())
        }
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

                        // Disguised HLS playlists (cl-master, master, playlist, index-f, stream-f)
                        var isDisguisedHls = lower.indexOf('cl-master') !== -1 || lower.indexOf('master') !== -1 ||
                                             lower.indexOf('playlist') !== -1 || lower.indexOf('index-f') !== -1 || lower.indexOf('stream-f') !== -1;

                        // Discard static text and font files unless matching a disguised stream pattern
                        if (!isDisguisedHls && (lower.indexOf('.txt') !== -1 || lower.indexOf('.woff') !== -1 || lower.indexOf('.woff2') !== -1 ||
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

                    // 1. Scan existing <video>, <audio>, <source> elements and <iframe> embeds
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

                    // 3. Intercept fetch API for .m3u8 / .mpd / media URLs
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
                            return origFetch.apply(this, arguments);
                        };
                    }

                    // 4. Intercept XMLHttpRequest
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
                        if (typeof this.__hzUrl === 'string' && isMediaCandidateUrl(this.__hzUrl)) {
                            notifyMedia(this.__hzUrl, '', this.__hzHeaders || {});
                        }
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
                                    var zIndex = parseInt(style.zIndex, 10);
                                    var w = el.offsetWidth || 0;
                                    var h = el.offsetHeight || 0;
                                    var isFullScreen = (w >= window.innerWidth * 0.8 && h >= window.innerHeight * 0.8);
                                    if (isFullScreen && (opacity <= 0.05 || zIndex >= 9999)) {
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
                    setInterval(cleanupPopunderOverlays, 1500);

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
