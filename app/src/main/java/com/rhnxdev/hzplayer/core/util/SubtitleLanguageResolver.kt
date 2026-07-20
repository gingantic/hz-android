package com.rhnxdev.hzplayer.core.util

/**
 * Result of resolving a subtitle track's raw name/label to a language.
 *
 * @property displayName label to show in the UI — the cleaned-up original text
 *   (bare codes like "eng" are expanded to "English"; filenames stay intact so
 *   multiple files of the same language remain distinguishable).
 * @property countryCode ISO 3166-1 alpha-2 code (FlagKit also accepts codes such
 *   as "GB-WLS") used to render the flag, or null when the language is unknown
 *   or stateless.
 * @property sortKey canonical English language name (lowercase) used to group
 *   and alphabetize tracks by language; falls back to the raw name.
 */
data class SubtitleLanguage(
    val displayName: String,
    val countryCode: String?,
    val sortKey: String,
)

/**
 * Best-effort mapping of subtitle track names to languages + country flags.
 *
 * Track labels arrive in many shapes depending on the container and how the
 * file was added: "English", "eng", "Subtitle - jpn", "Chinese (Simplified)",
 * "Movie.2023.1080p.WEB-DL.pt-BR.srt", native names ("日本語"), etc. The resolver
 * tries, in order: a direct/head match, a region-qualified code ("zh-CN"), and
 * finally a filename token scan from the end.
 */
object SubtitleLanguageResolver {

    private class Lang(val display: String, val flag: String?, val keys: List<String>)

    // ── Language table ────────────────────────────────────────────────────────
    // keys = ISO 639-1/639-2 codes, English names and native names (lowercased).
    private val LANGS: List<Lang> = run {
        fun lang(display: String, flag: String?, vararg keys: String) =
            Lang(display, flag, keys.asList())
        listOf(
            lang("English", "GB", "en", "eng", "english", "en-gb", "en-uk"),
            lang("English (US)", "US", "en-us", "english (us)", "american english"),
            lang("Japanese", "JP", "ja", "jpn", "japanese", "日本語", "にほんご"),
            lang("Chinese", "CN", "zh", "zho", "chi", "chinese", "mandarin", "中文", "简体中文", "简体字", "simplified chinese", "zh-cn", "zh-hans"),
            lang("Chinese (Traditional)", "TW", "zh-tw", "zh-hk", "zh-hant", "繁体中文", "繁體中文", "traditional chinese"),
            lang("Cantonese", "HK", "yue", "cantonese", "粵語", "粤语"),
            lang("Korean", "KR", "ko", "kor", "korean", "한국어", "조선말"),
            lang("Spanish", "ES", "es", "spa", "spanish", "castellano", "español", "espanol"),
            lang("Spanish (Latin America)", "MX", "es-mx", "es-419", "es-la", "latin american spanish", "spanish (latin america)"),
            lang("French", "FR", "fr", "fra", "fre", "french", "français", "francais"),
            lang("German", "DE", "de", "deu", "ger", "german", "deutsch"),
            lang("Portuguese", "PT", "pt", "por", "portuguese", "português", "portugues"),
            lang("Portuguese (Brazil)", "BR", "pt-br", "brazilian portuguese", "brazilian", "português (brasil)"),
            lang("Russian", "RU", "ru", "rus", "russian", "русский"),
            lang("Italian", "IT", "it", "ita", "italian", "italiano"),
            lang("Hindi", "IN", "hi", "hin", "hindi", "हिन्दी", "हिंदी"),
            lang("Indonesian", "ID", "id", "ind", "indonesian", "bahasa indonesia", "indonesia"),
            lang("Malay", "MY", "ms", "msa", "may", "malay", "bahasa melayu", "melayu"),
            lang("Turkish", "TR", "tr", "tur", "turkish", "türkçe", "turkce"),
            lang("Arabic", "SA", "ar", "ara", "arabic", "العربية", "عربي"),
            lang("Persian", "IR", "fa", "fas", "per", "persian", "farsi", "فارسی"),
            lang("Hebrew", "IL", "he", "heb", "iw", "hebrew", "עברית"),
            lang("Thai", "TH", "th", "tha", "thai", "ไทย"),
            lang("Vietnamese", "VN", "vi", "vie", "vietnamese", "tiếng việt", "tieng viet"),
            lang("Dutch", "NL", "nl", "nld", "dut", "dutch", "nederlands", "flemish", "vlaams"),
            lang("Polish", "PL", "pl", "pol", "polish", "polski"),
            lang("Swedish", "SE", "sv", "swe", "swedish", "svenska"),
            lang("Danish", "DK", "da", "dan", "danish", "dansk"),
            lang("Norwegian", "NO", "no", "nor", "nb", "nob", "norwegian", "norsk", "bokmål"),
            lang("Finnish", "FI", "fi", "fin", "finnish", "suomi"),
            lang("Greek", "GR", "el", "ell", "gre", "greek", "ελληνικά", "ελληνικα"),
            lang("Czech", "CZ", "cs", "ces", "cze", "czech", "čeština", "cestina"),
            lang("Slovak", "SK", "sk", "slk", "slo", "slovak", "slovenčina", "slovencina"),
            lang("Hungarian", "HU", "hu", "hun", "hungarian", "magyar"),
            lang("Romanian", "RO", "ro", "ron", "rum", "romanian", "română", "romana"),
            lang("Moldavian", "MD", "mo", "mol", "moldavian"),
            lang("Bulgarian", "BG", "bg", "bul", "bulgarian", "български"),
            lang("Ukrainian", "UA", "uk", "ukr", "ukrainian", "українська"),
            lang("Belarusian", "BY", "be", "bel", "belarusian", "беларуская"),
            lang("Serbian", "RS", "sr", "srp", "scc", "serbian", "српски", "srpski"),
            lang("Croatian", "HR", "hr", "hrv", "scr", "croatian", "hrvatski"),
            lang("Bosnian", "BA", "bs", "bos", "bosnian", "bosanski"),
            lang("Montenegrin", "ME", "cnr", "montenegrin"),
            lang("Slovenian", "SI", "sl", "slv", "slovenian", "slovenščina", "slovenscina"),
            lang("Estonian", "EE", "et", "est", "estonian", "eesti"),
            lang("Latvian", "LV", "lv", "lav", "latvian", "latviešu", "latviesu"),
            lang("Lithuanian", "LT", "lt", "lit", "lithuanian", "lietuvių", "lietuviu"),
            lang("Bengali", "BD", "bn", "ben", "bengali", "বাংলা"),
            lang("Tamil", "IN", "ta", "tam", "tamil", "தமிழ்"),
            lang("Telugu", "IN", "te", "tel", "telugu", "తెలుగు"),
            lang("Urdu", "PK", "ur", "urd", "urdu", "اردو"),
            lang("Punjabi", "IN", "pa", "pan", "punjabi", "ਪੰਜਾਬੀ"),
            lang("Gujarati", "IN", "gu", "guj", "gujarati", "ગુજરાતી"),
            lang("Marathi", "IN", "mr", "mar", "marathi", "मराठी"),
            lang("Kannada", "IN", "kn", "kan", "kannada", "ಕನ್ನಡ"),
            lang("Malayalam", "IN", "ml", "mal", "malayalam", "മലയാളം"),
            lang("Assamese", "IN", "as", "asm", "assamese", "অসমীয়া"),
            lang("Sindhi", "PK", "sd", "snd", "sindhi", "سنڌي"),
            lang("Nepali", "NP", "ne", "nep", "nepali", "नेपाली"),
            lang("Sinhala", "LK", "si", "sin", "sinhala", "sinhalese", "සිංහල"),
            lang("Burmese", "MM", "my", "mya", "bur", "burmese", "မြန်မာ"),
            lang("Khmer", "KH", "km", "khm", "khmer", "ខ្មែរ"),
            lang("Lao", "LA", "lo", "lao", "laotian", "ລາວ"),
            lang("Filipino", "PH", "tl", "tgl", "fil", "filipino", "tagalog"),
            lang("Swahili", "TZ", "sw", "swa", "swahili", "kiswahili"),
            lang("Amharic", "ET", "am", "amh", "amharic", "አማርኛ"),
            lang("Somali", "SO", "so", "som", "somali", "soomaali"),
            lang("Hausa", "NG", "ha", "hau", "hausa"),
            lang("Igbo", "NG", "ig", "ibo", "igbo"),
            lang("Yoruba", "NG", "yo", "yor", "yoruba", "yorùbá"),
            lang("Zulu", "ZA", "zu", "zul", "zulu", "isizulu"),
            lang("Afrikaans", "ZA", "af", "afr", "afrikaans"),
            lang("Armenian", "AM", "hy", "hye", "arm", "armenian", "հայերեն"),
            lang("Azerbaijani", "AZ", "az", "aze", "azerbaijani", "azərbaycanca", "azeri"),
            lang("Georgian", "GE", "ka", "kat", "geo", "georgian", "ქართული"),
            lang("Kazakh", "KZ", "kk", "kaz", "kazakh", "қазақша", "казахский"),
            lang("Kyrgyz", "KG", "ky", "kir", "kyrgyz", "кыргызча"),
            lang("Uzbek", "UZ", "uz", "uzb", "uzbek", "oʻzbekcha", "uzbekcha"),
            lang("Tajik", "TJ", "tg", "tgk", "tajik", "тоҷикӣ"),
            lang("Turkmen", "TM", "tk", "tuk", "turkmen", "түркменче"),
            lang("Tatar", "RU", "tt", "tat", "tatar", "татарча"),
            lang("Mongolian", "MN", "mn", "mon", "mongolian", "монгол"),
            lang("Tibetan", "CN", "bo", "bod", "tib", "tibetan"),
            lang("Uyghur", "CN", "ug", "uig", "uyghur", "ئۇيغۇرچە"),
            lang("Kurdish", "IQ", "ku", "kur", "kurdish", "kurdî", "کوردی"),
            lang("Pashto", "AF", "ps", "pus", "pashto", "پښتو"),
            lang("Dari", "AF", "prs", "dari"),
            lang("Albanian", "AL", "sq", "sqi", "alb", "albanian", "shqip"),
            lang("Macedonian", "MK", "mk", "mkd", "mac", "macedonian", "македонски"),
            lang("Catalan", "ES", "ca", "cat", "catalan", "català", "catala"),
            lang("Basque", "ES", "eu", "eus", "baq", "basque", "euskara"),
            lang("Galician", "ES", "gl", "glg", "galician", "galego"),
            lang("Welsh", "GB-WLS", "cy", "cym", "wel", "welsh", "cymraeg"),
            lang("Irish", "IE", "ga", "gle", "irish", "gaeilge"),
            lang("Scottish Gaelic", "GB-SCT", "gd", "gla", "scottish gaelic", "gàidhlig"),
            lang("Icelandic", "IS", "is", "isl", "ice", "icelandic", "íslenska", "islenska"),
            lang("Faroese", "FO", "fo", "fao", "faroese", "føroyskt"),
            lang("Maltese", "MT", "mt", "mlt", "maltese", "malti"),
            lang("Luxembourgish", "LU", "lb", "ltz", "luxembourgish", "lëtzebuergesch"),
            lang("Esperanto", null, "eo", "epo", "esperanto"),
            lang("Latin", "VA", "la", "lat", "latin"),
            lang("Haitian Creole", "HT", "ht", "hat", "haitian creole", "kreyòl", "kreyol"),
            lang("Guarani", "PY", "gn", "grn", "guarani"),
            lang("Malagasy", "MG", "mg", "mlg", "malagasy"),
            lang("Maori", "NZ", "mi", "mri", "mao", "maori"),
            lang("Samoan", "WS", "sm", "smo", "samoan"),
            lang("Tongan", "TO", "to", "ton", "tongan"),
            lang("Fijian", "FJ", "fj", "fij", "fijian"),
            lang("Greenlandic", "GL", "kl", "kal", "greenlandic", "kalaallisut"),
            lang("Dhivehi", "MV", "dv", "div", "dhivehi", "divehi"),
            lang("Dzongkha", "BT", "dz", "dzo", "dzongkha"),
        )
    }

    private val LOOKUP: Map<String, Lang> = buildMap {
        for (l in LANGS) for (key in l.keys) put(key.lowercase(), l)
    }

    private val SUBTITLE_EXTENSIONS = setOf(
        "srt", "ass", "ssa", "vtt", "sub", "idx", "txt", "dfxp", "ttml", "sbv", "lrc",
    )

    /** Release-group / quality noise tokens skipped during the filename scan. */
    private val NOISE_TOKENS = setOf(
        "sdh", "hi", "cc", "forced", "full", "complete", "normal", "hearing", "impaired",
        "optional", "external", "embedded", "dub", "dubbed", "sub", "subs", "subtitle",
        "subtitles", "caption", "captions", "srt", "ass", "ssa", "vtt", "idx",
        "web", "webrip", "webdl", "bluray", "brrip", "bdrip", "hdtv", "dvdrip", "remux",
        "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ac3", "dts", "atmos",
        "truehd", "10bit", "8bit", "hdr", "hdr10", "imax", "proper", "repack", "extended",
        "unrated", "multi", "dual", "1080p", "720p", "2160p", "480p", "4k", "mkv", "mp4", "avi",
    )

    /** Region sub-tags that are release noise, not countries ("movie.it.hd"). */
    private val NOISE_REGIONS = setOf(
        "hd", "sd", "tv", "web", "dl", "rip", "sub", "subs", "sdh", "hi", "cc", "ac",
        "dts", "hdr", "dub", "dubbed", "multi", "dual", "uncut", "full", "complete",
    )

    // Matches "zh-CN", "pt.br", "es_419", "zh.hant" …
    private val REGION_PATTERN =
        Regex("\\b([a-z]{2,3})[-_.]([a-z]{2,4}|419)\\b", RegexOption.IGNORE_CASE)

    private val PREFIX_PATTERN =
        Regex("^(subtitles?|captions?)\\s*[:\\-–—#]?\\s*", RegexOption.IGNORE_CASE)

    fun resolve(rawName: String): SubtitleLanguage {
        val raw = rawName.trim()
        if (raw.isEmpty()) return SubtitleLanguage(raw, null, "")

        val noExt = stripExtension(raw)

        matchHead(noExt)?.let { return it }
        matchRegionQualified(noExt)?.let { return it }
        matchToken(noExt)?.let { return it }

        return SubtitleLanguage(raw, null, raw.lowercase())
    }

    // ── Strategies ────────────────────────────────────────────────────────────

    /**
     * Matches labels where the language is the whole name or its head:
     * "English", "eng", "Subtitle - jpn", "English (SDH)", "Chinese (Traditional)".
     */
    private fun matchHead(name: String): SubtitleLanguage? {
        val stripped = name.replace(PREFIX_PATTERN, "").trim()
        if (stripped.isEmpty()) return null

        val heads = linkedSetOf(
            stripped,
            stripped.substringBeforeLast(" (").trim().removeSuffix(")").trim(),
            stripped.substringBeforeLast(" [").trim().removeSuffix("]").trim(),
            stripped.substringBeforeLast(" - ").trim(),
            stripped.substringBeforeLast(" – ").trim(),
        )
        for (head in heads) {
            if (head.isEmpty()) continue
            val lang = LOOKUP[head.lowercase()] ?: continue
            val qualifier = stripped.removePrefix(head).trim()
            val flag = refineFlag(lang, qualifier)
            val isBareCode = head.length <= 3 && head.all { it in 'a'..'z' || it in 'A'..'Z' }
            val display = when {
                !isBareCode -> stripped
                qualifier.isEmpty() -> lang.display
                else -> "${lang.display} $qualifier"
            }
            return SubtitleLanguage(display, flag, lang.display.lowercase())
        }
        return null
    }

    /** Matches region-qualified codes anywhere in the name: "movie.zh-CN", "sub.pt.br". */
    private fun matchRegionQualified(name: String): SubtitleLanguage? {
        for (match in REGION_PATTERN.findAll(name).toList().asReversed()) {
            val lang = LOOKUP[match.groupValues[1].lowercase()] ?: continue
            val region = match.groupValues[2].lowercase()
            if (region in NOISE_REGIONS) continue
            val flag = when (region) {
                "hans", "cn", "sg" -> "CN"
                "hant", "tw", "hk", "mo" -> "TW"
                "419", "mx", "la", "ar", "co", "cl", "pe", "ve", "ec", "uy", "py", "bo",
                "gt", "do", "ni", "cr", "pa", "sv", "hn", "pr", "cu",
                -> if (lang.display.startsWith("Spanish")) "MX" else region.uppercase()
                "br" -> "BR"
                else -> if (region.length == 2) region.uppercase() else lang.flag
            }
            return SubtitleLanguage(lang.display, flag, lang.display.lowercase())
        }
        return null
    }

    /**
     * Scans filename tokens from the end ("Movie.2023.1080p.WEB.English" → English),
     * skipping quality/release noise. The original name is kept as the label so
     * several files of the same language stay distinguishable.
     */
    private fun matchToken(name: String): SubtitleLanguage? {
        val tokens = name.split(Regex("[._\\-\\s]+")).filter { it.isNotBlank() }
        var checked = 0
        for (token in tokens.asReversed()) {
            if (checked >= 5) break
            val t = token.lowercase()
            if (t.all { it.isDigit() }) continue // years, track numbers
            if (t in NOISE_TOKENS) continue
            checked++
            val lang = LOOKUP[t] ?: continue
            // Keep the (extension-stripped) filename as label so several files of
            // the same language remain distinguishable in the list.
            return SubtitleLanguage(name, lang.flag, lang.display.lowercase())
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.substring(dot + 1).lowercase() in SUBTITLE_EXTENSIONS) {
            return name.substring(0, dot)
        }
        return name
    }

    /** Narrows a language's default flag using a qualifier like "(Brazil)" or "(SDH)". */
    private fun refineFlag(lang: Lang, qualifier: String): String? {
        if (qualifier.isEmpty()) return lang.flag
        val q = qualifier.lowercase()
        return when {
            lang.display == "Chinese" &&
                (q.contains("simpl") || q.contains("简体") || q.contains("簡体")) -> "CN"
            lang.display == "Chinese" &&
                (q.contains("trad") || q.contains("繁体") || q.contains("繁體") || q.contains("hk")) -> "TW"
            lang.display == "Portuguese" && q.contains("braz") -> "BR"
            lang.display.startsWith("Spanish") &&
                (q.contains("latin") || q.contains("latam") || q.contains("419") || q.contains("mex")) -> "MX"
            lang.display.startsWith("English") &&
                (q.contains("us") || q.contains("american")) -> "US"
            lang.display.startsWith("English") &&
                (q.contains("uk") || q.contains("brit")) -> "GB"
            lang.display.startsWith("English") && q.contains("austral") -> "AU"
            lang.display.startsWith("English") && q.contains("canad") -> "CA"
            lang.display == "French" && q.contains("canad") -> "CA"
            else -> lang.flag
        }
    }
}
