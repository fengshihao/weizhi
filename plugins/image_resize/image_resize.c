#include "weizhi_image_resize_api.h"

#include <stdlib.h>
#include <string.h>

static void write_u32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

/* Packed result: little-endian width, height, then RGBA. Nearest-neighbor. */
WEIZHI_PLUGIN_EXPORT WeizhiBuf weizhi_image_resize_resize_rgba(WeizhiBuf rgba, int32_t width, int32_t height,
                                                              int32_t max_edge) {
    WeizhiBuf out;
    int32_t longest;
    int32_t nw;
    int32_t nh;
    size_t need;
    size_t pixels;
    int32_t y;
    memset(&out, 0, sizeof(out));
    if (width < 1 || height < 1 || max_edge < 1 || max_edge > 4096 || rgba.data == NULL) {
        return out;
    }
    pixels = (size_t)width * (size_t)height;
    if (rgba.len != pixels * 4) {
        return out;
    }
    longest = width > height ? width : height;
    if (max_edge >= longest) {
        nw = width;
        nh = height;
    } else {
        nw = (int32_t)((width * (double)max_edge / longest) + 0.5);
        nh = (int32_t)((height * (double)max_edge / longest) + 0.5);
        if (nw < 1) {
            nw = 1;
        }
        if (nh < 1) {
            nh = 1;
        }
    }
    need = 8u + (size_t)nw * (size_t)nh * 4u;
    out.data = (uint8_t *)malloc(need);
    if (out.data == NULL) {
        return out;
    }
    out.len = need;
    write_u32(out.data, (uint32_t)nw);
    write_u32(out.data + 4, (uint32_t)nh);
    for (y = 0; y < nh; y++) {
        int32_t sy = (int32_t)(((y + 0.5) * height) / nh);
        int32_t x;
        if (sy < 0) {
            sy = 0;
        }
        if (sy >= height) {
            sy = height - 1;
        }
        for (x = 0; x < nw; x++) {
            int32_t sx = (int32_t)(((x + 0.5) * width) / nw);
            size_t src;
            size_t dst;
            if (sx < 0) {
                sx = 0;
            }
            if (sx >= width) {
                sx = width - 1;
            }
            src = ((size_t)sy * (size_t)width + (size_t)sx) * 4u;
            dst = 8u + ((size_t)y * (size_t)nw + (size_t)x) * 4u;
            memcpy(out.data + dst, rgba.data + src, 4);
        }
    }
    return out;
}
