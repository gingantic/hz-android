package com.rhnxdev.hzplayer.browser.adblock

import android.webkit.JavascriptInterface
import android.webkit.WebView

/** An element the user tapped in picker mode, plus the CSS selector that hides it. */
data class PickedElement(
    val selector: String,
    val matchCount: Int,
    val canWiden: Boolean,
)

/**
 * JS bridge for the "block element" picker: reports the selector of whatever
 * element the user taps while picker mode is on.
 *
 * The script is injected once per document and driven from Kotlin through
 * `window.__hzElementPicker` (start / stop / widen / hide).
 */
class ElementPickerBridge(
    private val onPicked: (selector: String, matchCount: Int, canWiden: Boolean) -> Unit,
) {

    @JavascriptInterface
    fun onElementPicked(selector: String, matchCount: Int, canWiden: Boolean) {
        if (selector.isBlank() || selector.length > MAX_SELECTOR_LENGTH) return
        onPicked(selector, matchCount, canWiden)
    }

    companion object {
        const val INTERFACE_NAME = "HzElementPicker"
        private const val MAX_SELECTOR_LENGTH = 1024

        /** Run the picker script; safe to call repeatedly (self-guarded). */
        fun injectPickerJs(webView: WebView) {
            webView.post { webView.evaluateJavascript(PICKER_JS, null) }
        }

        /**
         * Drive the injected script. [script] is a single expression such as
         * `start()`; failures are logged — a dead renderer must not crash the host.
         */
        fun eval(webView: WebView, script: String) {
            webView.post {
                webView.evaluateJavascript(
                    "(function(){ try { if (window.__hzElementPicker) window.__hzElementPicker.$script; } catch(e) {} })();",
                    null,
                )
            }
        }

        /** Hide [selector] immediately, before the persisted rule is re-applied. */
        fun hide(webView: WebView, selector: String) {
            val quoted = org.json.JSONObject.quote(selector)
            eval(webView, "hide($quoted)")
        }

        // Built once and reused — the script is ~200 lines and would otherwise be
        // rebuilt (interpolation + trimIndent) on every injection.
        private val PICKER_JS: String by lazy {
            """
                (function() {
                    if (window.__hzElementPicker) return;

                    var OVERLAY_ID = 'hz-picker-overlay';
                    var STYLE_ID   = 'hz-picker-style';
                    var HIDE_ID    = 'hz-picker-hide';
                    var MAX_DEPTH  = 8;
                    var TAP_SLOP   = 10;

                    var active = false;
                    var current = null;
                    var startPoint = null;
                    var lastTouchPick = 0;

                    function byId(id) { return document.getElementById(id); }
                    function tagOf(el) { return el && el.tagName ? el.tagName.toLowerCase() : ''; }

                    function ensureStyle() {
                        var s = byId(STYLE_ID);
                        if (!s) {
                            s = document.createElement('style');
                            s.id = STYLE_ID;
                            s.innerHTML = 'html.hz-picking, html.hz-picking * {' +
                                '-webkit-user-select:none !important; user-select:none !important;' +
                                '-webkit-touch-callout:none !important; }';
                            (document.head || document.documentElement).appendChild(s);
                        }
                    }

                    function ensureOverlay() {
                        var el = byId(OVERLAY_ID);
                        if (!el) {
                            el = document.createElement('div');
                            el.id = OVERLAY_ID;
                            // fixed + viewport coords: immune to ancestor positioning
                            el.style.cssText = 'position:fixed;pointer-events:none !important;' +
                                'z-index:2147483647;box-sizing:border-box;margin:0;padding:0;' +
                                'border:2px solid #FF3B30;background:rgba(255,59,48,0.15);';
                            (document.body || document.documentElement).appendChild(el);
                        }
                        return el;
                    }

                    function outline(el) {
                        var ov = ensureOverlay();
                        var r = el.getBoundingClientRect();
                        ov.style.left   = r.left + 'px';
                        ov.style.top    = r.top + 'px';
                        ov.style.width  = r.width + 'px';
                        ov.style.height = r.height + 'px';
                        ov.style.display = 'block';
                    }

                    function esc(v) {
                        if (window.CSS && typeof CSS.escape === 'function') return CSS.escape(v);
                        return String(v).replace(/([^\w-])/g, function(m) { return '\\' + m; });
                    }

                    function classesOf(el) {
                        // getAttribute, not className: className is an SVGAnimatedString on SVG
                        var raw = el.getAttribute ? el.getAttribute('class') : null;
                        if (!raw) return [];
                        return raw.trim().split(/\s+/).filter(function(c) { return !!c; });
                    }

                    // mirror of SelectorHeuristics.kt — keep in sync.
                    // Words stable by convention regardless of shape — never random.
                    var STABLE = {ad:1,ads:1,advert:1,advertisement:1,banner:1,promo:1,
                        promoted:1,sponsor:1,sponsored:1,sidebar:1,widget:1,slot:1,
                        container:1,wrapper:1,overlay:1,modal:1,popup:1,interstitial:1};

                    // group 1 = stable stem, group 2 = alnum tail (adbox_7fa21 -> adbox).
                    var STEM_RE = /^([A-Za-z][A-Za-z0-9_-]*?)[-_]?([0-9a-z]{5,})${'$'}/i;

                    /** True when a token looks like a per-load random id/class. */
                    function looksRandom(t) {
                        t = String(t).trim();
                        if (t.length <= 3) return false;
                        if (STABLE[t.toLowerCase()]) return false;
                        if (/^[0-9a-f]{6,}${'$'}/i.test(t)) return true;          // hex blob
                        if (/^[0-9]{6,}${'$'}/.test(t)) return true;              // pure numeric
                        if (/^[0-9a-f-]{20,}${'$'}/i.test(t) && /[0-9]/.test(t)) return true; // UUID-like
                        // stem + alnum tail: random only when the tail carries a digit
                        var m = STEM_RE.exec(t);
                        if (m && /[0-9]/.test(m[2])) return true;
                        if (/^[a-z0-9]{8,}${'$'}/i.test(t)) {                     // vowel/digit density
                            var vowels = (t.match(/[aeiou]/gi) || []).length;
                            var digits = (t.match(/[0-9]/g) || []).length;
                            if (vowels <= 1 || digits / t.length >= 0.3) return true;
                        }
                        return false;
                    }

                    /** Stable prefix of a stem+random-tail token, or '' when none. */
                    function stemOf(t) {
                        var m = STEM_RE.exec(String(t).trim());
                        if (!m || !/[0-9]/.test(m[2])) return '';
                        var stem = m[1];
                        return (stem.length >= 4 && !looksRandom(stem)) ? stem : '';
                    }

                    // Safe double-quoted attribute-value body: strip CR/NL, escape \\ and
                    // ", cap length so the whole selector stays well under 512 chars.
                    function attrVal(v) {
                        return String(v).replace(/[\r\n]/g, '')
                            .replace(/\\/g, '\\\\').replace(/"/g, '\\"').slice(0, 120);
                    }

                    // 1-based index among same-tag siblings (for :nth-of-type).
                    function nthOfType(el) {
                        var p = el.parentElement;
                        if (!p) return 1;
                        var i = 0, n = 0, tag = el.tagName;
                        var ch = p.children;
                        for (; i < ch.length; i++) {
                            if (ch[i].tagName === tag) { n++; if (ch[i] === el) return n; }
                        }
                        return n || 1;
                    }

                    // "Reasonably specific": matches at least one node but not a crowd.
                    // 8 is a small, tunable cap — a wider match falls through to classes.
                    function selective(sel) {
                        try { var n = document.querySelectorAll(sel).length; return n >= 1 && n <= 8; }
                        catch (e) { return false; }
                    }

                    function isUnique(sel, el) {
                        try {
                            var all = document.querySelectorAll(sel);
                            return all.length === 1 && all[0] === el;
                        } catch (e) { return false; }
                    }

                    // Stable attributes to try, in order: ad-specific markers first,
                    // then semantic/testing hooks whose value or presence survives reloads.
                    var ATTRS = ['data-ad', 'data-ad-slot', 'data-ad-unit', 'data-adunit',
                        'data-google-query-id', 'aria-label', 'data-testid', 'data-test',
                        'data-qa', 'role', 'itemprop', 'data-widget', 'data-section'];

                    // tag[attr="value"] (stable value) or tag[attr] (random value, meaningful
                    // presence) — accepted only when reasonably specific. '' when none fit.
                    function attrPart(el, tag) {
                        if (!el.getAttribute) return '';
                        for (var i = 0; i < ATTRS.length; i++) {
                            var a = ATTRS[i];
                            if (!el.hasAttribute(a)) continue;
                            var v = el.getAttribute(a);
                            var cand = (v && !looksRandom(v))
                                ? tag + '[' + a + '="' + attrVal(v) + '"]'
                                : tag + '[' + a + ']';
                            if (selective(cand)) return cand;
                        }
                        return '';
                    }

                    // Stable classes: non-random tokens, ad/semantic ones first.
                    function stableClasses(el) {
                        var cls = classesOf(el).filter(function(c) { return !looksRandom(c); });
                        cls.sort(function(a, b) {
                            var ka = STABLE[a.toLowerCase()] || /ad/i.test(a) ? 0 : 1;
                            var kb = STABLE[b.toLowerCase()] || /ad/i.test(b) ? 0 : 1;
                            return ka - kb;
                        });
                        return cls;
                    }

                    /**
                     * Randomness-aware, always tag-first — a bare `.class`/`#id` rule never
                     * matches this engine, and a random token would never match on reload.
                     * Priority: stable id > stable attribute > stable class/stem > structure.
                     */
                    function partFor(el) {
                        var tag = tagOf(el) || '*';

                        // 1. id — only when it is NOT random
                        var id = el.getAttribute ? el.getAttribute('id') : null;
                        if (id && id.trim()) {
                            id = id.trim();
                            if (!looksRandom(id) && isUnique(tag + '#' + esc(id), el)) {
                                return tag + '#' + esc(id);
                            }
                            var idStem = stemOf(id);
                            if (idStem) {
                                var ip = tag + '[id^="' + attrVal(idStem) + '"]';
                                if (selective(ip)) return ip;
                            }
                        }

                        // 2. stable attribute
                        var ap = attrPart(el, tag);
                        if (ap) return ap;

                        // 3. stable classes (1-2 tokens), then a class prefix/stem
                        var cls = stableClasses(el);
                        var n = Math.min(cls.length, 2);
                        for (; n >= 1; n--) {
                            var c = tag + '.' + cls.slice(0, n).map(esc).join('.');
                            if (isUnique(c, el) || selective(c)) return c;
                        }
                        if (cls.length) return tag + '.' + cls.slice(0, 2).map(esc).join('.');
                        // prefix with random suffix: ad-slot__container_x7f9k2 -> [class*="ad-slot__container"]
                        var all = classesOf(el);
                        for (var j = 0; j < all.length; j++) {
                            var st = stemOf(all[j]);
                            if (st) {
                                var cp = tag + '[class*="' + attrVal(st) + '"]';
                                if (selective(cp)) return cp;
                            }
                        }

                        // 4. structural fallback — nth-of-type is stabler than nth-child
                        return tag + ':nth-of-type(' + nthOfType(el) + ')';
                    }

                    // partFor emits only stable parts, so uniqueness is reached through
                    // structure (nth-of-type), never a random id/class — a stable
                    // non-unique selector is preferred over a unique-but-random one.
                    function selectorFor(el) {
                        var node = el;
                        var parts = [];
                        while (node && node.nodeType === 1 && parts.length < MAX_DEPTH) {
                            var tag = tagOf(node);
                            if (tag === 'html' || tag === 'body') {
                                parts.unshift(tag);
                                break;
                            }
                            parts.unshift(partFor(node));
                            var sel = parts.join(' > ');
                            if (isUnique(sel, el)) return sel;
                            node = node.parentElement;
                        }
                        return parts.join(' > ');
                    }

                    // Shadow DOM: hit testing retargets to the host, so re-target
                    // explicitly — internals are unreachable anyway.
                    function lightDom(el) {
                        var node = el;
                        while (node && node.getRootNode && node.getRootNode() !== document) {
                            var host = node.getRootNode().host;
                            if (!host) return null;
                            node = host;
                        }
                        return node;
                    }

                    function notify() {
                        if (!current) return;
                        var sel = selectorFor(current);
                        var count = 0;
                        try { count = document.querySelectorAll(sel).length; } catch (e) { count = 0; }
                        var parent = current.parentElement;
                        var pt = tagOf(parent);
                        var canWiden = !!parent && pt !== 'body' && pt !== 'html';
                        try {
                            window.$INTERFACE_NAME.onElementPicked(sel, count, canWiden);
                        } catch (e) {}
                    }

                    function pick(x, y) {
                        if (!active) return;
                        var el = lightDom(document.elementFromPoint(x, y));
                        if (!el || el.id === OVERLAY_ID || el.id === STYLE_ID || el.id === HIDE_ID) return;
                        var tag = tagOf(el);
                        if (tag === 'html' || tag === 'body') return;
                        current = el;
                        outline(el);
                        notify();
                    }

                    function swallow(e) { if (active) e.stopImmediatePropagation(); }

                    // starve the page's own handlers without preventDefault, so the
                    // compositor keeps scrolling
                    function onTouchStart(e) {
                        if (!active) return;
                        var t = e.touches && e.touches[0];
                        startPoint = t ? { x: t.clientX, y: t.clientY } : null;
                        swallow(e);
                    }

                    function onTouchEnd(e) {
                        if (!active) return;
                        swallow(e);
                        var t = e.changedTouches && e.changedTouches[0];
                        if (!t || !startPoint) return;
                        var moved = Math.abs(t.clientX - startPoint.x) > TAP_SLOP ||
                                    Math.abs(t.clientY - startPoint.y) > TAP_SLOP;
                        startPoint = null;
                        if (moved) return;
                        // suppresses the synthesized click, so a link never navigates
                        try { e.preventDefault(); } catch (err) {}
                        lastTouchPick = Date.now();
                        pick(t.clientX, t.clientY);
                    }

                    // mouse / emulator fallback: touchend already handled real taps
                    function onClick(e) {
                        if (!active) return;
                        try { e.preventDefault(); } catch (err) {}
                        e.stopImmediatePropagation();
                        if (Date.now() - lastTouchPick < 800) return;
                        pick(e.clientX, e.clientY);
                    }

                    function onMouseDown(e) {
                        if (!active) return;
                        try { e.preventDefault(); } catch (err) {}
                        e.stopImmediatePropagation();
                    }

                    function onContextMenu(e) {
                        if (!active) return;
                        try { e.preventDefault(); } catch (err) {}
                        e.stopImmediatePropagation();
                    }

                    function reposition() { if (active && current) outline(current); }

                    var passive = { capture: true, passive: true };
                    var blocking = { capture: true, passive: false };
                    var events = [
                        ['touchstart', onTouchStart, passive], ['touchmove', swallow, passive],
                        ['pointerdown', swallow, passive], ['pointerup', swallow, passive],
                        ['touchend', onTouchEnd, blocking], ['click', onClick, blocking],
                        ['mousedown', onMouseDown, blocking], ['contextmenu', onContextMenu, blocking],
                        ['scroll', reposition, passive], ['resize', reposition, passive],
                    ];

                    function add() { events.forEach(function(e) { window.addEventListener(e[0], e[1], e[2]); }); }
                    function remove() { events.forEach(function(e) { window.removeEventListener(e[0], e[1], e[2]); }); }

                    function start() {
                        if (active) return;
                        active = true;
                        current = null;
                        ensureStyle();
                        if (document.documentElement) document.documentElement.classList.add('hz-picking');
                        add();
                    }

                    function stop() {
                        if (!active) return;
                        active = false;
                        remove();
                        if (document.documentElement) document.documentElement.classList.remove('hz-picking');
                        var ov = byId(OVERLAY_ID);
                        if (ov) ov.remove();
                        current = null;
                    }

                    function widen() {
                        if (!active || !current) return;
                        var parent = current.parentElement;
                        if (!parent) return;
                        var pt = tagOf(parent);
                        if (pt === 'html' || pt === 'body') return;
                        current = parent;
                        outline(parent);
                        notify();
                    }

                    /* Outlives stop(): the element stays hidden until the persisted
                       rule (or a reload) takes over. */
                    function hide(sel) {
                        try {
                            var s = byId(HIDE_ID);
                            if (!s) {
                                s = document.createElement('style');
                                s.id = HIDE_ID;
                                (document.head || document.documentElement).appendChild(s);
                            }
                            var rule = sel + ' { display:none !important; }';
                            if (s.innerHTML.indexOf(rule) === -1) s.innerHTML += rule + '\n';
                        } catch (e) {}
                        stop();
                    }

                    window.__hzElementPicker = { start: start, stop: stop, widen: widen, hide: hide };
                })();
            """.trimIndent()
        }
    }
}
