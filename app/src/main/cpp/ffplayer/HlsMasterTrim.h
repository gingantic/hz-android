#pragma once

#include <cstddef>
#include <string>
#include <vector>

/**
 * Pure helpers that cut an HLS master playlist down to one variant.
 *
 * FFmpeg's hls demuxer opens and probes the first segments of EVERY variant and rendition
 * inside avformat_open_input, and offers no option to skip any. A 24-variant master therefore
 * costs dozens of fresh TCP+TLS connections before the first frame, and the burst gets
 * handshakes reset by CDNs. The engine plays one video stream, so the rest is wasted work.
 *
 * Header-only and free of FFmpeg/Android types so the exact same source can be compiled and
 * tested on a desktop host.
 */
namespace hls_trim {

namespace detail {

inline bool startsWith(const std::string& s, const char* prefix) {
    return s.compare(0, std::char_traits<char>::length(prefix), prefix) == 0;
}

inline bool contains(const std::string& s, const char* needle) {
    return s.find(needle) != std::string::npos;
}

inline std::string lower(std::string s) {
    for (char& c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

/** Value of `key` in an `#EXT-X-...:K=V,K="V"` attribute list; empty when absent. */
inline std::string attrValue(const std::string& line, const std::string& key) {
    const size_t colon = line.find(':');
    if (colon == std::string::npos) return {};
    const size_t n = line.size();
    size_t i = colon + 1;
    while (i < n) {
        const size_t eq = line.find('=', i);
        if (eq == std::string::npos) break;
        const std::string name = line.substr(i, eq - i);
        const size_t v = eq + 1;
        std::string value;
        if (v < n && line[v] == '"') {
            size_t close = line.find('"', v + 1);
            if (close == std::string::npos) close = n;
            value = line.substr(v + 1, close - v - 1);
            i = close + 1;
            if (i < n && line[i] == ',') ++i;
        } else {
            size_t comma = line.find(',', v);
            if (comma == std::string::npos) comma = n;
            value = line.substr(v, comma - v);
            i = comma + 1;
        }
        if (name == key) return value;
    }
    return {};
}

inline bool isVideoCodec(const std::string& codec) {
    static const char* const kPrefixes[] = {"avc", "hvc", "hev", "dvh", "dav1", "av0", "vp0", "vp9", "mp4v", "theo"};
    const std::string c = lower(codec);
    for (const char* p : kPrefixes) {
        if (startsWith(c, p)) return true;
    }
    return false;
}

/** True when CODECS is present and lists no video codec and RESOLUTION is absent. */
inline bool isKnownAudioOnly(const std::string& streamInf) {
    if (!attrValue(streamInf, "RESOLUTION").empty()) return false;
    const std::string codecs = attrValue(streamInf, "CODECS");
    if (codecs.empty()) return false;  // unknown: assume it can carry video
    size_t start = 0;
    while (start <= codecs.size()) {
        size_t comma = codecs.find(',', start);
        if (comma == std::string::npos) comma = codecs.size();
        std::string one = codecs.substr(start, comma - start);
        const size_t b = one.find_first_not_of(' ');
        if (b != std::string::npos && isVideoCodec(one.substr(b))) return false;
        start = comma + 1;
    }
    return true;
}

}  // namespace detail

/**
 * Cheap check on the first bytes of a playlist: true for a master playlist (declares variants
 * and no segments). Lets the caller skip buffering the whole body for media playlists.
 *
 * @param buf — first bytes of the playlist
 * @param len — number of bytes in [buf]
 */
inline bool looksLikeMaster(const char* buf, size_t len) {
    const std::string head(buf, len);
    const size_t start = head.compare(0, 3, "\xEF\xBB\xBF") == 0 ? 3 : 0;
    if (head.compare(start, 7, "#EXTM3U") != 0) return false;
    return detail::contains(head, "#EXT-X-STREAM-INF") && !detail::contains(head, "#EXTINF");
}

/**
 * Cuts [text] down to one variant plus the rendition groups that variant references.
 *
 * Keeps the first variant that is not known to be audio-only, the same stream the engine
 * would have picked (it plays the first video stream). All other variants and the
 * EXT-X-MEDIA renditions of other groups are dropped. Every other line is kept verbatim.
 *
 * @param text — full master playlist
 * @param totalVariants — receives the number of variants found, may be null
 * @return the trimmed playlist, or empty when [text] should be used unchanged (not a master,
 *         fewer than two variants, malformed, or nothing to gain)
 */
inline std::string trimMasterToOneVariant(const std::string& text, int* totalVariants = nullptr) {
    using namespace detail;
    constexpr size_t kMaxBytes = 1u << 20;
    if (totalVariants) *totalVariants = 0;
    if (text.empty() || text.size() > kMaxBytes) return {};

    std::vector<std::string> lines;
    {
        size_t pos = 0;
        while (pos <= text.size()) {
            size_t nl = text.find('\n', pos);
            if (nl == std::string::npos) nl = text.size();
            std::string line = text.substr(pos, nl - pos);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            lines.push_back(std::move(line));
            pos = nl + 1;
        }
    }

    const size_t bom = lines.empty() ? 0 : (lines[0].compare(0, 3, "\xEF\xBB\xBF") == 0 ? 3 : 0);
    if (lines.empty() || lines[0].compare(bom, 7, "#EXTM3U") != 0) return {};

    struct Variant {
        size_t infoLine;
        size_t uriLine;
    };
    std::vector<Variant> variants;
    for (size_t i = 0; i < lines.size(); ++i) {
        if (startsWith(lines[i], "#EXTINF")) return {};  // media playlist
        if (!startsWith(lines[i], "#EXT-X-STREAM-INF:")) continue;
        size_t j = i + 1;
        while (j < lines.size() && lines[j].empty()) ++j;
        if (j >= lines.size() || lines[j][0] == '#') return {};  // malformed: leave it alone
        variants.push_back({i, j});
        i = j;
    }
    if (totalVariants) *totalVariants = static_cast<int>(variants.size());
    if (variants.size() < 2) return {};

    size_t chosen = 0;
    for (size_t k = 0; k < variants.size(); ++k) {
        if (!isKnownAudioOnly(lines[variants[k].infoLine])) {
            chosen = k;
            break;
        }
    }
    const std::string& chosenInf = lines[variants[chosen].infoLine];
    const std::string audioGroup = attrValue(chosenInf, "AUDIO");
    const std::string subtitleGroup = attrValue(chosenInf, "SUBTITLES");
    const std::string videoGroup = attrValue(chosenInf, "VIDEO");

    std::vector<bool> drop(lines.size(), false);
    for (size_t k = 0; k < variants.size(); ++k) {
        if (k == chosen) continue;
        for (size_t l = variants[k].infoLine; l <= variants[k].uriLine; ++l) drop[l] = true;
    }
    for (size_t i = 0; i < lines.size(); ++i) {
        if (!startsWith(lines[i], "#EXT-X-MEDIA:")) continue;
        const std::string type = attrValue(lines[i], "TYPE");
        const std::string group = attrValue(lines[i], "GROUP-ID");
        const std::string* wanted = nullptr;
        if (type == "AUDIO") wanted = &audioGroup;
        else if (type == "SUBTITLES") wanted = &subtitleGroup;
        else if (type == "VIDEO") wanted = &videoGroup;
        if (wanted && (wanted->empty() || group != *wanted)) drop[i] = true;
    }

    std::string out;
    out.reserve(text.size());
    bool first = true;
    for (size_t i = 0; i < lines.size(); ++i) {
        if (drop[i]) continue;
        if (!first) out.push_back('\n');
        out += lines[i];
        first = false;
    }
    out.push_back('\n');
    return out;
}

}  // namespace hls_trim
