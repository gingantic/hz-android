#include "ass_direct.h"

#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <ass/ass.h>

#define LOG_TAG "assrender"
#define LOGI(...) ((void)0)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   LOG_TAG, __VA_ARGS__)
#define LOGD(...) ((void)0)
#define LOGV(...) ((void)0)

struct AssDirectContext {
    int width;
    int height;
    float font_scale;

    ASS_Library  *ass_library;
    ASS_Renderer *ass_renderer;
    ASS_Track    *ass_track;

    char **font_buffers;
    int    font_buffers_count;
    int    font_buffers_capacity;

    /* diagnostic: how many times ass_set_fonts has been called */
    int fonts_set_count;
};

#include <stdarg.h>

/* Forward every libass message to Android logcat with the same level mapping.
 * Known benign/noisy warnings are demoted to VERBOSE to avoid log spam. */
static void ass_message_cb(int level, const char *fmt, va_list args, void *data) {
    if (level > 2) return;
    /* Format the message first so we can inspect its content */
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);

    /* Fully suppress known benign per-event spam — fires for every line that
     * uses an unsupported effect (e.g. \t(fx)), i.e. every frame at worst. */
    if (strstr(buf, "Unknown transition effect") ||
        strstr(buf, "unknown transition") ||
        strstr(buf, "Unknown ScriptType")) {
        return;
    }

    /* Demote raw ASS section dumps to VERBOSE (rare, only at parse time). */
    if (strstr(buf, "Event: [") ||            /* raw ASS section dumps */
        strstr(buf, "Style: ["))              /* raw ASS section dumps */
    {
        __android_log_print(ANDROID_LOG_VERBOSE, "libass", "%s", buf);
        return;
    }

    int priority;
    if      (level <= 1) priority = ANDROID_LOG_ERROR;
    else if (level == 2) priority = ANDROID_LOG_WARN;
    else if (level == 3) priority = ANDROID_LOG_INFO;
    else                 priority = ANDROID_LOG_DEBUG;
    /* Use tag "libass" so you can filter separately: adb logcat -s libass */
    __android_log_print(priority, "libass", "%s", buf);
}

/* ─────────────────────────────── INIT ─────────────────────────────────── */

AssDirectContext *ass_direct_init(int width, int height, float font_scale) {
    LOGI("[INIT] ass_direct_init called: %dx%d scale=%.2f", width, height, font_scale);

    AssDirectContext *ctx = calloc(1, sizeof(AssDirectContext));
    if (!ctx) { LOGE("[INIT] calloc failed"); return NULL; }

    ctx->width      = width;
    ctx->height     = height;
    ctx->font_scale = font_scale;

    /* ── Library ── */
    ctx->ass_library = ass_library_init();
    if (!ctx->ass_library) {
        LOGE("[INIT] ass_library_init() returned NULL");
        free(ctx); return NULL;
    }
    LOGI("[INIT] ass_library_init() OK  ptr=%p", (void*)ctx->ass_library);

    ass_set_message_cb(ctx->ass_library, ass_message_cb, NULL);
    LOGI("[INIT] message callback installed");

    /* Extract embedded fonts from script headers automatically */
    ass_set_extract_fonts(ctx->ass_library, 1);
    LOGI("[INIT] ass_set_extract_fonts(1) done");

    /* ── Renderer ── */
    ctx->ass_renderer = ass_renderer_init(ctx->ass_library);
    if (!ctx->ass_renderer) {
        LOGE("[INIT] ass_renderer_init() returned NULL");
        ass_library_done(ctx->ass_library);
        free(ctx); return NULL;
    }
    LOGI("[INIT] ass_renderer_init() OK  ptr=%p", (void*)ctx->ass_renderer);

    ass_set_storage_size(ctx->ass_renderer, width, height);
    ass_set_frame_size  (ctx->ass_renderer, width, height);
    ass_set_font_scale  (ctx->ass_renderer, (double)font_scale);
    LOGI("[INIT] storage/frame size=%dx%d  font_scale=%.2f", width, height, font_scale);

    /*
     * AUTODETECT: uses both system/fontconfig fonts AND any fonts added via
     * ass_add_font() into the library's memory.
     * NOTE: ass_set_fonts() is synchronous — it may block while scanning.
     *       The last param (1) means "update font cache" (scan now).
     */
    LOGI("[FONT] calling ass_set_fonts(AUTODETECT, update=1) — may take a moment…");
    ass_set_fonts(ctx->ass_renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
    ctx->fonts_set_count++;
    LOGI("[FONT] ass_set_fonts call #%d done", ctx->fonts_set_count);

    LOGI("[INIT] ass_direct_init COMPLETE: handle=%p", (void*)ctx);
    return ctx;
}

/* ─────────────────────────────── HEADER ───────────────────────────────── */

int ass_direct_load_header(AssDirectContext *ctx,
                           const char *header_data, int header_size) {
    LOGI("[TRACK] ass_direct_load_header: %d bytes", header_size);
    if (!ctx || !header_data || header_size <= 0) {
        LOGE("[TRACK] invalid args: ctx=%p data=%p size=%d",
             (void*)ctx, (void*)header_data, header_size);
        return -1;
    }

    if (ctx->ass_track) {
        LOGI("[TRACK] freeing previous track");
        ass_free_track(ctx->ass_track);
        ctx->ass_track = NULL;
    }

    ctx->ass_track = ass_new_track(ctx->ass_library);
    if (!ctx->ass_track) {
        LOGE("[TRACK] ass_new_track() returned NULL");
        return -1;
    }
    LOGI("[TRACK] ass_new_track() OK  ptr=%p", (void*)ctx->ass_track);

    /* Print first 200 chars of header for inspection */
    char preview[201];
    int  preview_len = header_size < 200 ? header_size : 200;
    memcpy(preview, header_data, preview_len);
    preview[preview_len] = '\0';
    LOGD("[TRACK] header preview: %s", preview);

    ass_process_codec_private(ctx->ass_track, (char *)header_data, header_size);

    LOGI("[TRACK] header loaded: %d styles, %d events",
         ctx->ass_track->n_styles, ctx->ass_track->n_events);

    /* Log each style name so we can verify font names */
    for (int i = 0; i < ctx->ass_track->n_styles; i++) {
        ASS_Style *s = &ctx->ass_track->styles[i];
        LOGI("[TRACK] style[%d]: name='%s' font='%s' size=%.1f bold=%d italic=%d",
             i,
             s->Name       ? s->Name       : "(null)",
             s->FontName   ? s->FontName   : "(null)",
             s->FontSize,
             s->Bold, s->Italic);
    }
    return 0;
}

/* ─────────────────────────────── FONTS ────────────────────────────────── */

void ass_direct_add_font(AssDirectContext *ctx, const char *name,
                         const char *data, int data_size) {
    LOGI("[FONT] ass_direct_add_font: name='%s' size=%d bytes  (library=%p)",
         name ? name : "(null)", data_size, (void*)(ctx ? ctx->ass_library : NULL));

    if (!ctx || !data || data_size <= 0) {
        LOGE("[FONT] invalid args — skipping");
        return;
    }

    /* Peek at the first 4 bytes to sanity-check the font magic */
    if (data_size >= 4) {
        const unsigned char *b = (const unsigned char *)data;
        LOGD("[FONT] magic bytes: %02X %02X %02X %02X  (TTF=00 01 00 00 | OTF=4F 54 54 4F | TTC=74 74 63 66)",
             b[0], b[1], b[2], b[3]);
    }

    /* Keep a persistent copy so libass can reference the buffer at render time */
    char *persistent = malloc(data_size);
    if (!persistent) {
        LOGE("[FONT] malloc(%d) failed for '%s'", data_size, name ? name : "?");
        return;
    }
    memcpy(persistent, data, data_size);

    /* Grow the tracking array */
    if (ctx->font_buffers_count >= ctx->font_buffers_capacity) {
        int new_cap = ctx->font_buffers_capacity == 0 ? 8
                                                      : ctx->font_buffers_capacity * 2;
        char **nb = realloc(ctx->font_buffers, new_cap * sizeof(char *));
        if (!nb) {
            LOGE("[FONT] realloc failed — dropping font '%s'", name ? name : "?");
            free(persistent);
            return;
        }
        ctx->font_buffers         = nb;
        ctx->font_buffers_capacity = new_cap;
        LOGD("[FONT] font_buffers expanded to capacity %d", new_cap);
    }
    ctx->font_buffers[ctx->font_buffers_count++] = persistent;

    LOGI("[FONT] calling ass_add_font: slot=%d  name='%s'  size=%d",
         ctx->font_buffers_count - 1, name ? name : "(null)", data_size);
    ass_add_font(ctx->ass_library, name, persistent, data_size);
    LOGI("[FONT] ass_add_font done — total fonts in library: %d",
         ctx->font_buffers_count);
}

void ass_direct_reload_fonts(AssDirectContext *ctx) {
    LOGI("[FONT] ass_direct_reload_fonts called  (fonts in lib=%d)",
         ctx ? ctx->font_buffers_count : -1);
    if (!ctx) return;

    /* Recreate renderer so the font scanner picks up newly added fonts */
    LOGI("[FONT] destroying old renderer (%p)…", (void*)ctx->ass_renderer);
    if (ctx->ass_renderer) {
        ass_renderer_done(ctx->ass_renderer);
        ctx->ass_renderer = NULL;
    }

    ctx->ass_renderer = ass_renderer_init(ctx->ass_library);
    if (!ctx->ass_renderer) {
        LOGE("[FONT] ass_renderer_init() returned NULL in reload_fonts");
        return;
    }
    LOGI("[FONT] new renderer created: %p", (void*)ctx->ass_renderer);

    ass_set_storage_size(ctx->ass_renderer, ctx->width, ctx->height);
    ass_set_frame_size  (ctx->ass_renderer, ctx->width, ctx->height);
    ass_set_font_scale  (ctx->ass_renderer, (double)ctx->font_scale);

    LOGI("[FONT] calling ass_set_fonts(AUTODETECT) — will scan all %d buffered fonts…",
         ctx->font_buffers_count);
    ass_set_fonts(ctx->ass_renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
    ctx->fonts_set_count++;
    LOGI("[FONT] ass_set_fonts call #%d done — reload complete", ctx->fonts_set_count);
}

/* ─────────────────────────────── FRAME SIZE ───────────────────────────── */

void ass_direct_set_frame_size(AssDirectContext *ctx, int width, int height) {
    LOGI("[INIT] set_frame_size: %dx%d → %dx%d",
         ctx ? ctx->width : -1, ctx ? ctx->height : -1, width, height);
    if (!ctx) return;
    ctx->width  = width;
    ctx->height = height;
    if (ctx->ass_renderer) {
        ass_set_storage_size(ctx->ass_renderer, width, height);
        ass_set_frame_size  (ctx->ass_renderer, width, height);
    }
}

/* ─────────────────────────────── DATA ─────────────────────────────────── */

void ass_direct_process_chunk(AssDirectContext *ctx, const char *data, int size,
                               int64_t start_ms, int64_t duration_ms) {
    if (!ctx || !ctx->ass_track || !data || size <= 0) {
        LOGW("[TRACK] process_chunk skipped: ctx=%p track=%p data=%p size=%d",
             (void*)ctx,
             ctx ? (void*)ctx->ass_track : NULL,
             (void*)data, size);
        return;
    }
    /*
     * data must be MKV body format (NO "Dialogue:" prefix, NO timestamps):
     *   ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
     * start_ms / duration_ms carry the timing.
     */
    char preview[121];
    int  plen = size < 120 ? size : 120;
    memcpy(preview, data, plen);
    preview[plen] = '\0';
    LOGD("[TRACK] process_chunk start=%lldms dur=%lldms  body='%s'",
         (long long)start_ms, (long long)duration_ms, preview);

    ass_process_chunk(ctx->ass_track, (char *)data, size, start_ms, duration_ms);
}

/* ─────────────────────────────── RENDER ───────────────────────────────── */

int ass_direct_render(AssDirectContext *ctx, int64_t time_ms, uint8_t *out_pixels) {
    if (!ctx || !ctx->ass_track || !ctx->ass_renderer || !out_pixels) {
        LOGW("[RENDER] skipped: ctx=%p track=%p renderer=%p pixels=%p",
             (void*)ctx,
             ctx ? (void*)ctx->ass_track    : NULL,
             ctx ? (void*)ctx->ass_renderer : NULL,
             (void*)out_pixels);
        return 0;
    }

    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->ass_renderer, ctx->ass_track,
                                      time_ms, &changed);

    /* Clear output */
    memset(out_pixels, 0, (size_t)ctx->width * ctx->height * 4);
    if (!img) return 0;

    int has_content = 0;
    while (img) {
        if (img->w == 0 || img->h == 0) { img = img->next; continue; }
        has_content = 1;

        /*
         * libass ASS_Image colour is packed as 0xRRGGBBAA where AA is
         * the OPACITY complement (0=transparent, 255=opaque).
         *
         * Android Bitmap.Config.ARGB_8888 stores pixels in memory as:
         *   byte[0]=B  byte[1]=G  byte[2]=R  byte[3]=A   (little-endian BGRA)
         *
         * So we must write: dst[0]=b, dst[1]=g, dst[2]=r, dst[3]=alpha.
         */
        uint8_t r = (img->color >> 24) & 0xFF;
        uint8_t g = (img->color >> 16) & 0xFF;
        uint8_t b = (img->color >>  8) & 0xFF;
        uint8_t a = 255 - (img->color & 0xFF);   /* 0=transparent → flip to opacity */

        uint8_t *src = img->bitmap;
        for (int y = 0; y < img->h; y++) {
            if (img->dst_y + y < 0 || img->dst_y + y >= ctx->height) {
                src += img->stride; continue;
            }
            uint8_t *dst = out_pixels
                         + ((img->dst_y + y) * ctx->width + img->dst_x) * 4;
            for (int x = 0; x < img->w; x++) {
                if (img->dst_x + x < 0 || img->dst_x + x >= ctx->width) {
                    dst += 4; continue;
                }
                /* src[x] is the glyph alpha mask (0=transparent, 255=opaque) */
                uint8_t alpha = (uint8_t)(((uint32_t)src[x] * a) >> 8);
                if (alpha == 0) { dst += 4; continue; }

                /* Premultiply source color by its alpha to support Android's premultiplied Bitmap */
                uint8_t rs = (uint8_t)(((uint32_t)r * alpha) >> 8);
                uint8_t gs = (uint8_t)(((uint32_t)g * alpha) >> 8);
                uint8_t bs = (uint8_t)(((uint32_t)b * alpha) >> 8);

                uint8_t dst_a = dst[3];
                if (dst_a == 0) {
                    /* destination is transparent — write source directly (premultiplied RGBA order) */
                    dst[0] = rs;
                    dst[1] = gs;
                    dst[2] = bs;
                    dst[3] = alpha;
                } else {
                    /* Porter-Duff SRC_OVER blend in premultiplied space (RGBA order) */
                    uint32_t inv = 255 - alpha;
                    dst[0] = (uint8_t)(rs + ((uint32_t)dst[0] * inv) / 255);
                    dst[1] = (uint8_t)(gs + ((uint32_t)dst[1] * inv) / 255);
                    dst[2] = (uint8_t)(bs + ((uint32_t)dst[2] * inv) / 255);
                    dst[3] = (uint8_t)(alpha + ((uint32_t)dst_a * inv) / 255);
                }
                dst += 4;
            }
            src += img->stride;
        }
        img = img->next;
    }
    return has_content;
}

/* ─────────────────────────────── FLUSH / DESTROY ──────────────────────── */

void ass_direct_flush(AssDirectContext *ctx) {
    if (!ctx || !ctx->ass_track) {
        LOGW("[TRACK] flush called but ctx/track is null");
        return;
    }
    LOGI("[TRACK] flushing %d events", ctx->ass_track->n_events);
    ass_flush_events(ctx->ass_track);
    LOGI("[TRACK] flush done");
}

void ass_direct_destroy(AssDirectContext *ctx) {
    LOGI("[INIT] ass_direct_destroy called: ctx=%p", (void*)ctx);
    if (!ctx) return;

    LOGI("[INIT] destroying: track=%p renderer=%p library=%p  fonts=%d",
         (void*)ctx->ass_track, (void*)ctx->ass_renderer,
         (void*)ctx->ass_library, ctx->font_buffers_count);

    if (ctx->ass_track)    ass_free_track(ctx->ass_track);
    if (ctx->ass_renderer) ass_renderer_done(ctx->ass_renderer);
    if (ctx->ass_library)  ass_library_done(ctx->ass_library);

    if (ctx->font_buffers) {
        for (int i = 0; i < ctx->font_buffers_count; i++)
            free(ctx->font_buffers[i]);
        free(ctx->font_buffers);
    }

    free(ctx);
    LOGI("[INIT] ass_direct_destroy complete");
}
