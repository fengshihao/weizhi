#include "engine_internal.h"

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <zlib.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define WEIZHI_ZIP_MAX_ENTRIES 10000
#define WEIZHI_ZIP_LOCAL_SIG 0x04034b50u
#define WEIZHI_ZIP_CENTRAL_SIG 0x02014b50u
#define WEIZHI_ZIP_EOCD_SIG 0x06054b50u

typedef struct {
    uint8_t *data;
    size_t len;
    size_t cap;
} ZipBuf;

typedef struct {
    char *name;
    uint32_t crc;
    uint32_t comp_size;
    uint32_t uncomp_size;
    uint16_t method;
    uint32_t local_offset;
} ZipCentral;

static void zip_buf_free(ZipBuf *b) {
    free(b->data);
    b->data = NULL;
    b->len = 0;
    b->cap = 0;
}

static int zip_buf_reserve(ZipBuf *b, size_t need, size_t limit) {
    uint8_t *n;
    size_t cap;
    if (need > limit) {
        return -1;
    }
    if (need <= b->cap) {
        return 0;
    }
    cap = b->cap == 0 ? 4096 : b->cap;
    while (cap < need) {
        if (cap > limit / 2) {
            cap = limit;
            break;
        }
        cap *= 2;
    }
    if (cap < need || cap > limit) {
        return -1;
    }
    n = realloc(b->data, cap);
    if (n == NULL) {
        return -1;
    }
    b->data = n;
    b->cap = cap;
    return 0;
}

static int zip_buf_append(ZipBuf *b, const void *p, size_t n, size_t limit) {
    if (zip_buf_reserve(b, b->len + n, limit) != 0) {
        return -1;
    }
    if (n > 0) {
        memcpy(b->data + b->len, p, n);
    }
    b->len += n;
    return 0;
}

static void write_u16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
}

static void write_u32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

static uint16_t read_u16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t read_u32(const uint8_t *p) {
    return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

static int call_vfs(Engine *engine, WeizhiVfsOp op, const char *relpath, const char *relpath2,
                    const WeizhiBytes *in, WeizhiBytes *out, char *errbuf, size_t errbuf_len) {
    WeizhiBytes local;
    WeizhiBytes *use = out;
    int rc;
    if (engine == NULL || engine->vfs_sync == NULL) {
        snprintf(errbuf, errbuf_len, "workspace not set");
        return -1;
    }
    if (use == NULL) {
        memset(&local, 0, sizeof(local));
        use = &local;
    }
    rc = engine->vfs_sync(op, relpath, relpath2, in, use, errbuf, errbuf_len,
                          engine->vfs_ud != NULL ? engine->vfs_ud : engine);
    if (out == NULL) {
        weizhi_bytes_free(&local);
    }
    return rc;
}

static int mkdir_p(Engine *engine, const char *relpath, char *errbuf, size_t errbuf_len) {
    char tmp[PATH_MAX];
    size_t i;
    size_t len;
    if (relpath == NULL || relpath[0] == '\0' || !weizhi_path_ok(relpath)) {
        snprintf(errbuf, errbuf_len, "invalid path");
        return -1;
    }
    if (snprintf(tmp, sizeof(tmp), "%s", relpath) >= (int)sizeof(tmp)) {
        snprintf(errbuf, errbuf_len, "path too long");
        return -1;
    }
    len = strlen(tmp);
    for (i = 1; i < len; i++) {
        if (tmp[i] == '/') {
            tmp[i] = '\0';
            if (tmp[0] != '\0' && weizhi_path_ok(tmp)) {
                if (call_vfs(engine, WEIZHI_VFS_MKDIR, tmp, NULL, NULL, NULL, errbuf, errbuf_len) != 0) {
                    return -1;
                }
            }
            tmp[i] = '/';
        }
    }
    return call_vfs(engine, WEIZHI_VFS_MKDIR, relpath, NULL, NULL, NULL, errbuf, errbuf_len);
}

static int entry_name_safe(const char *name) {
    size_t i;
    if (name == NULL || name[0] == '\0') {
        return 0;
    }
    if (name[0] == '/' || name[0] == '\\') {
        return 0;
    }
    if (strcmp(name, "..") == 0 || strncmp(name, "../", 3) == 0) {
        return 0;
    }
    if (strstr(name, "/../") != NULL || strstr(name, "\\") != NULL) {
        return 0;
    }
    /* Trailing /.. */
    i = strlen(name);
    if (i >= 3 && strcmp(name + i - 3, "/..") == 0) {
        return 0;
    }
    /* Same rule as weizhi_path_ok: reject any ".." substring. */
    if (strstr(name, "..") != NULL) {
        return 0;
    }
    return 1;
}

static int inflate_raw(const uint8_t *in, size_t in_len, size_t expect_len, size_t limit,
                       uint8_t **out, size_t *out_len) {
    z_stream strm;
    uint8_t *buf;
    size_t cap;
    int ret;
    if (expect_len > limit) {
        return -2;
    }
    cap = expect_len > 0 ? expect_len : 4096;
    if (cap > limit) {
        return -2;
    }
    buf = malloc(cap == 0 ? 1 : cap);
    if (buf == NULL) {
        return -1;
    }
    memset(&strm, 0, sizeof(strm));
    if (inflateInit2(&strm, -MAX_WBITS) != Z_OK) {
        free(buf);
        return -1;
    }
    strm.next_in = (Bytef *)(uintptr_t)in;
    strm.avail_in = (uInt)in_len;
    strm.next_out = buf;
    strm.avail_out = (uInt)cap;
    for (;;) {
        ret = inflate(&strm, Z_NO_FLUSH);
        if (ret == Z_STREAM_END) {
            break;
        }
        if (ret != Z_OK) {
            inflateEnd(&strm);
            free(buf);
            return -1;
        }
        if (strm.avail_out == 0) {
            size_t used = strm.total_out;
            size_t ncap;
            uint8_t *nbuf;
            if (used >= limit) {
                inflateEnd(&strm);
                free(buf);
                return -2;
            }
            ncap = used * 2;
            if (ncap < used + 4096) {
                ncap = used + 4096;
            }
            if (ncap > limit) {
                ncap = limit;
            }
            if (ncap <= used) {
                inflateEnd(&strm);
                free(buf);
                return -2;
            }
            nbuf = realloc(buf, ncap);
            if (nbuf == NULL) {
                inflateEnd(&strm);
                free(buf);
                return -1;
            }
            buf = nbuf;
            cap = ncap;
            strm.next_out = buf + used;
            strm.avail_out = (uInt)(cap - used);
        }
    }
    inflateEnd(&strm);
    if (expect_len > 0 && strm.total_out != expect_len) {
        free(buf);
        return -1;
    }
    *out = buf;
    *out_len = (size_t)strm.total_out;
    return 0;
}

static int deflate_raw(const uint8_t *in, size_t in_len, size_t limit, uint8_t **out, size_t *out_len) {
    z_stream strm;
    uint8_t *buf;
    size_t cap;
    int ret;
    if (in_len > limit) {
        return -2;
    }
    cap = in_len + 64;
    if (cap > limit) {
        cap = limit;
    }
    if (cap == 0) {
        cap = 64;
    }
    buf = malloc(cap);
    if (buf == NULL) {
        return -1;
    }
    memset(&strm, 0, sizeof(strm));
    if (deflateInit2(&strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        free(buf);
        return -1;
    }
    strm.next_in = (Bytef *)(uintptr_t)in;
    strm.avail_in = (uInt)in_len;
    strm.next_out = buf;
    strm.avail_out = (uInt)cap;
    for (;;) {
        ret = deflate(&strm, Z_FINISH);
        if (ret == Z_STREAM_END) {
            break;
        }
        if (ret != Z_OK && ret != Z_BUF_ERROR) {
            deflateEnd(&strm);
            free(buf);
            return -1;
        }
        {
            size_t used = strm.total_out;
            size_t ncap;
            uint8_t *nbuf;
            if (used >= limit) {
                deflateEnd(&strm);
                free(buf);
                return -2;
            }
            ncap = used * 2 + 256;
            if (ncap > limit) {
                ncap = limit;
            }
            if (ncap <= used) {
                deflateEnd(&strm);
                free(buf);
                return -2;
            }
            nbuf = realloc(buf, ncap);
            if (nbuf == NULL) {
                deflateEnd(&strm);
                free(buf);
                return -1;
            }
            buf = nbuf;
            cap = ncap;
            strm.next_out = buf + used;
            strm.avail_out = (uInt)(cap - used);
        }
    }
    deflateEnd(&strm);
    *out = buf;
    *out_len = (size_t)strm.total_out;
    return 0;
}

int weizhi_zip_extract(Engine *engine, const char *zip_rel, const char *dest_rel, int *out_entries,
                       int *out_skipped, char *errbuf, size_t errbuf_len) {
    WeizhiBytes zip;
    size_t off = 0;
    size_t limit;
    size_t total_uncomp = 0;
    size_t max_total;
    int entries = 0;
    int skipped = 0;
    memset(&zip, 0, sizeof(zip));
    if (out_entries) {
        *out_entries = 0;
    }
    if (out_skipped) {
        *out_skipped = 0;
    }
    if (engine == NULL || zip_rel == NULL || dest_rel == NULL) {
        snprintf(errbuf, errbuf_len, "bad argument: zip.extractSync");
        return -1;
    }
    if (!weizhi_path_ok(zip_rel) || !weizhi_path_ok(dest_rel)) {
        snprintf(errbuf, errbuf_len, "invalid path");
        return -1;
    }
    limit = engine->limits.fs_io_bytes;
    max_total = limit * 4;
    if (max_total < limit) {
        max_total = limit;
    }
    if (call_vfs(engine, WEIZHI_VFS_READ, zip_rel, NULL, NULL, &zip, errbuf, errbuf_len) != 0) {
        return -1;
    }
    if (mkdir_p(engine, dest_rel, errbuf, errbuf_len) != 0) {
        weizhi_bytes_free(&zip);
        return -1;
    }
    while (off + 30 <= zip.len) {
        uint32_t sig = read_u32(zip.data + off);
        uint16_t flags;
        uint16_t method;
        uint32_t crc;
        uint32_t comp_size;
        uint32_t uncomp_size;
        uint16_t name_len;
        uint16_t extra_len;
        char name[PATH_MAX];
        size_t data_off;
        uint8_t *payload = NULL;
        size_t payload_len = 0;
        char joined[PATH_MAX];
        WeizhiBytes in;
        int is_dir;

        if (sig == WEIZHI_ZIP_CENTRAL_SIG || sig == WEIZHI_ZIP_EOCD_SIG) {
            break;
        }
        if (sig != WEIZHI_ZIP_LOCAL_SIG) {
            snprintf(errbuf, errbuf_len, "invalid zip");
            weizhi_bytes_free(&zip);
            return -1;
        }
        if (entries >= WEIZHI_ZIP_MAX_ENTRIES) {
            snprintf(errbuf, errbuf_len, "too many zip entries (limit %d)", WEIZHI_ZIP_MAX_ENTRIES);
            weizhi_bytes_free(&zip);
            return -1;
        }
        flags = read_u16(zip.data + off + 6);
        method = read_u16(zip.data + off + 8);
        crc = read_u32(zip.data + off + 14);
        comp_size = read_u32(zip.data + off + 18);
        uncomp_size = read_u32(zip.data + off + 22);
        name_len = read_u16(zip.data + off + 26);
        extra_len = read_u16(zip.data + off + 28);
        if ((flags & 0x8) != 0) {
            snprintf(errbuf, errbuf_len, "unsupported zip data descriptor");
            weizhi_bytes_free(&zip);
            return -1;
        }
        if (off + 30 + name_len + extra_len > zip.len) {
            snprintf(errbuf, errbuf_len, "invalid zip");
            weizhi_bytes_free(&zip);
            return -1;
        }
        if (name_len >= sizeof(name)) {
            skipped++;
            off += 30 + name_len + extra_len + comp_size;
            continue;
        }
        memcpy(name, zip.data + off + 30, name_len);
        name[name_len] = '\0';
        data_off = off + 30 + name_len + extra_len;
        if (data_off + comp_size > zip.len) {
            snprintf(errbuf, errbuf_len, "invalid zip");
            weizhi_bytes_free(&zip);
            return -1;
        }
        is_dir = (name_len > 0 && name[name_len - 1] == '/');
        if (!entry_name_safe(name)) {
            skipped++;
            off = data_off + comp_size;
            continue;
        }
        if (snprintf(joined, sizeof(joined), "%s/%s", dest_rel, name) >= (int)sizeof(joined) ||
            !weizhi_path_ok(joined)) {
            skipped++;
            off = data_off + comp_size;
            continue;
        }
        if (is_dir) {
            /* Strip trailing slash for mkdir */
            size_t jl = strlen(joined);
            if (jl > 0 && joined[jl - 1] == '/') {
                joined[jl - 1] = '\0';
            }
            if (joined[0] != '\0' && mkdir_p(engine, joined, errbuf, errbuf_len) != 0) {
                weizhi_bytes_free(&zip);
                return -1;
            }
            entries++;
            off = data_off + comp_size;
            continue;
        }
        if (uncomp_size > limit) {
            snprintf(errbuf, errbuf_len, "too large: zip entry");
            weizhi_bytes_free(&zip);
            return -1;
        }
        if (total_uncomp + uncomp_size > max_total) {
            snprintf(errbuf, errbuf_len, "too large: zip total");
            weizhi_bytes_free(&zip);
            return -1;
        }
        if (method == 0) {
            if (comp_size != uncomp_size) {
                snprintf(errbuf, errbuf_len, "invalid zip");
                weizhi_bytes_free(&zip);
                return -1;
            }
            payload = malloc(comp_size == 0 ? 1 : comp_size);
            if (payload == NULL) {
                snprintf(errbuf, errbuf_len, "out of memory");
                weizhi_bytes_free(&zip);
                return -1;
            }
            if (comp_size > 0) {
                memcpy(payload, zip.data + data_off, comp_size);
            }
            payload_len = comp_size;
        } else if (method == 8) {
            int rc = inflate_raw(zip.data + data_off, comp_size, uncomp_size, limit, &payload, &payload_len);
            if (rc == -2) {
                snprintf(errbuf, errbuf_len, "too large: zip entry");
                weizhi_bytes_free(&zip);
                return -1;
            }
            if (rc != 0) {
                snprintf(errbuf, errbuf_len, "zip inflate failed");
                weizhi_bytes_free(&zip);
                return -1;
            }
        } else {
            skipped++;
            off = data_off + comp_size;
            continue;
        }
        if (crc32(0L, payload, (uInt)payload_len) != crc) {
            free(payload);
            snprintf(errbuf, errbuf_len, "zip crc mismatch");
            weizhi_bytes_free(&zip);
            return -1;
        }
        {
            char parent[PATH_MAX];
            char *slash;
            snprintf(parent, sizeof(parent), "%s", joined);
            slash = strrchr(parent, '/');
            if (slash != NULL && slash != parent) {
                *slash = '\0';
                if (mkdir_p(engine, parent, errbuf, errbuf_len) != 0) {
                    free(payload);
                    weizhi_bytes_free(&zip);
                    return -1;
                }
            }
        }
        in.data = payload;
        in.len = payload_len;
        if (call_vfs(engine, WEIZHI_VFS_WRITE, joined, NULL, &in, NULL, errbuf, errbuf_len) != 0) {
            free(payload);
            weizhi_bytes_free(&zip);
            return -1;
        }
        free(payload);
        total_uncomp += payload_len;
        entries++;
        off = data_off + comp_size;
    }
    weizhi_bytes_free(&zip);
    if (out_entries) {
        *out_entries = entries;
    }
    if (out_skipped) {
        *out_skipped = skipped;
    }
    return 0;
}

typedef struct {
    Engine *engine;
    const char *src_root_rel;
    const char *src_abs;
    const char *out_zip_rel;
    size_t src_abs_len;
    ZipBuf *out;
    ZipCentral *centrals;
    int ncentral;
    int cap_central;
    size_t limit;
    char *errbuf;
    size_t errbuf_len;
} ZipCreateWalk;

static int central_push(ZipCreateWalk *w, const ZipCentral *c) {
    ZipCentral *n;
    if (w->ncentral >= WEIZHI_ZIP_MAX_ENTRIES) {
        snprintf(w->errbuf, w->errbuf_len, "too many zip entries (limit %d)", WEIZHI_ZIP_MAX_ENTRIES);
        return -1;
    }
    if (w->ncentral >= w->cap_central) {
        int ncap = w->cap_central == 0 ? 16 : w->cap_central * 2;
        n = realloc(w->centrals, (size_t)ncap * sizeof(ZipCentral));
        if (n == NULL) {
            snprintf(w->errbuf, w->errbuf_len, "out of memory");
            return -1;
        }
        w->centrals = n;
        w->cap_central = ncap;
    }
    w->centrals[w->ncentral] = *c;
    w->ncentral++;
    return 0;
}

static int append_local_file(ZipCreateWalk *w, const char *entry_name, const uint8_t *data, size_t len) {
    uint8_t hdr[30];
    uint8_t *comp = NULL;
    size_t comp_len = 0;
    uint16_t method = 8;
    uint32_t crc;
    ZipCentral c;
    size_t name_len;
    int rc;

    name_len = strlen(entry_name);
    if (name_len > 65535) {
        snprintf(w->errbuf, w->errbuf_len, "path too long");
        return -1;
    }
    crc = (uint32_t)crc32(0L, data, (uInt)len);
    if (len == 0) {
        method = 0;
        comp = NULL;
        comp_len = 0;
    } else {
        rc = deflate_raw(data, len, w->limit, &comp, &comp_len);
        if (rc == -2) {
            snprintf(w->errbuf, w->errbuf_len, "too large: zip");
            return -1;
        }
        if (rc != 0 || comp_len >= len) {
            free(comp);
            comp = NULL;
            method = 0;
            comp_len = len;
        }
    }
    memset(hdr, 0, sizeof(hdr));
    write_u32(hdr, WEIZHI_ZIP_LOCAL_SIG);
    write_u16(hdr + 4, 20);
    write_u16(hdr + 6, 0);
    write_u16(hdr + 8, method);
    write_u32(hdr + 14, crc);
    write_u32(hdr + 18, (uint32_t)comp_len);
    write_u32(hdr + 22, (uint32_t)len);
    write_u16(hdr + 26, (uint16_t)name_len);
    write_u16(hdr + 28, 0);
    c.local_offset = (uint32_t)w->out->len;
    if (zip_buf_append(w->out, hdr, 30, w->limit) != 0 ||
        zip_buf_append(w->out, entry_name, name_len, w->limit) != 0) {
        free(comp);
        snprintf(w->errbuf, w->errbuf_len, "too large: zip");
        return -1;
    }
    if (method == 0) {
        if (len > 0 && zip_buf_append(w->out, data, len, w->limit) != 0) {
            snprintf(w->errbuf, w->errbuf_len, "too large: zip");
            return -1;
        }
    } else {
        if (zip_buf_append(w->out, comp, comp_len, w->limit) != 0) {
            free(comp);
            snprintf(w->errbuf, w->errbuf_len, "too large: zip");
            return -1;
        }
        free(comp);
    }
    c.name = strdup(entry_name);
    if (c.name == NULL) {
        snprintf(w->errbuf, w->errbuf_len, "out of memory");
        return -1;
    }
    c.crc = crc;
    c.comp_size = (uint32_t)comp_len;
    c.uncomp_size = (uint32_t)len;
    c.method = method;
    if (central_push(w, &c) != 0) {
        free(c.name);
        return -1;
    }
    return 0;
}

static int walk_add_file(ZipCreateWalk *w, const char *abs_path) {
    char entry[PATH_MAX];
    const char *rel;
    WeizhiBytes file;
    char vfs_rel[PATH_MAX];
    size_t i;

    if (strncmp(abs_path, w->src_abs, w->src_abs_len) != 0 ||
        (abs_path[w->src_abs_len] != '/' && abs_path[w->src_abs_len] != '\0')) {
        return 0;
    }
    rel = abs_path + w->src_abs_len;
    if (*rel == '/') {
        rel++;
    }
    if (*rel == '\0') {
        return 0;
    }
    if (snprintf(entry, sizeof(entry), "%s", rel) >= (int)sizeof(entry)) {
        snprintf(w->errbuf, w->errbuf_len, "path too long");
        return -1;
    }
    for (i = 0; entry[i] != '\0'; i++) {
        if (entry[i] == '\\') {
            entry[i] = '/';
        }
    }
    if (!entry_name_safe(entry)) {
        return 0;
    }
    if (snprintf(vfs_rel, sizeof(vfs_rel), "%s/%s", w->src_root_rel, entry) >= (int)sizeof(vfs_rel) ||
        !weizhi_path_ok(vfs_rel)) {
        snprintf(w->errbuf, w->errbuf_len, "invalid path");
        return -1;
    }
    if (strcmp(vfs_rel, w->out_zip_rel) == 0) {
        return 0;
    }
    memset(&file, 0, sizeof(file));
    if (call_vfs(w->engine, WEIZHI_VFS_READ, vfs_rel, NULL, NULL, &file, w->errbuf, w->errbuf_len) != 0) {
        return -1;
    }
    if (append_local_file(w, entry, file.data != NULL ? file.data : (const uint8_t *)"", file.len) != 0) {
        weizhi_bytes_free(&file);
        return -1;
    }
    weizhi_bytes_free(&file);
    return 0;
}

static int walk_dir(ZipCreateWalk *w, const char *abs_dir) {
    DIR *dir = opendir(abs_dir);
    struct dirent *ent;
    if (dir == NULL) {
        snprintf(w->errbuf, w->errbuf_len, "source dir not found");
        return -1;
    }
    while ((ent = readdir(dir)) != NULL) {
        char child[PATH_MAX];
        struct stat st;
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
            continue;
        }
        if (snprintf(child, sizeof(child), "%s/%s", abs_dir, ent->d_name) >= (int)sizeof(child)) {
            closedir(dir);
            snprintf(w->errbuf, w->errbuf_len, "path too long");
            return -1;
        }
        if (stat(child, &st) != 0) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            if (walk_dir(w, child) != 0) {
                closedir(dir);
                return -1;
            }
        } else if (S_ISREG(st.st_mode)) {
            if (walk_add_file(w, child) != 0) {
                closedir(dir);
                return -1;
            }
        }
    }
    closedir(dir);
    return 0;
}

static int cmp_central(const void *a, const void *b) {
    const ZipCentral *ca = a;
    const ZipCentral *cb = b;
    return strcmp(ca->name, cb->name);
}

int weizhi_zip_create(Engine *engine, const char *src_rel, const char *zip_rel, int *out_files,
                      char *errbuf, size_t errbuf_len) {
    char src_joined[PATH_MAX];
    char src_real[PATH_MAX];
    char folder_real[PATH_MAX];
    ZipBuf out;
    ZipCreateWalk walk;
    WeizhiBytes written;
    uint32_t cd_offset;
    uint32_t cd_size;
    uint8_t eocd[22];
    int i;

    if (out_files) {
        *out_files = 0;
    }
    memset(&out, 0, sizeof(out));
    memset(&walk, 0, sizeof(walk));
    if (engine == NULL || src_rel == NULL || zip_rel == NULL) {
        snprintf(errbuf, errbuf_len, "bad argument: zip.createSync");
        return -1;
    }
    if (!weizhi_path_ok(src_rel) || !weizhi_path_ok(zip_rel)) {
        snprintf(errbuf, errbuf_len, "invalid path");
        return -1;
    }
    if (engine->fs_root == NULL) {
        snprintf(errbuf, errbuf_len, "workspace not set");
        return -1;
    }
    if (realpath(engine->fs_root, folder_real) == NULL) {
        snprintf(errbuf, errbuf_len, "workspace not found");
        return -1;
    }
    snprintf(src_joined, sizeof(src_joined), "%s/%s", folder_real, src_rel);
    if (realpath(src_joined, src_real) == NULL) {
        snprintf(errbuf, errbuf_len, "source dir not found");
        return -1;
    }
    {
        size_t folder_len = strlen(folder_real);
        if (strncmp(src_real, folder_real, folder_len) != 0 ||
            (src_real[folder_len] != '/' && src_real[folder_len] != '\0')) {
            snprintf(errbuf, errbuf_len, "path escape");
            return -1;
        }
    }
    {
        struct stat st;
        if (stat(src_real, &st) != 0 || !S_ISDIR(st.st_mode)) {
            snprintf(errbuf, errbuf_len, "source dir not found");
            return -1;
        }
    }
    /* Ensure parent of zip exists */
    {
        char parent[PATH_MAX];
        char *slash;
        snprintf(parent, sizeof(parent), "%s", zip_rel);
        slash = strrchr(parent, '/');
        if (slash != NULL && slash != parent) {
            *slash = '\0';
            if (mkdir_p(engine, parent, errbuf, errbuf_len) != 0) {
                return -1;
            }
        }
    }

    walk.engine = engine;
    walk.src_root_rel = src_rel;
    walk.src_abs = src_real;
    walk.src_abs_len = strlen(src_real);
    walk.out_zip_rel = zip_rel;
    walk.out = &out;
    walk.limit = engine->limits.fs_io_bytes;
    walk.errbuf = errbuf;
    walk.errbuf_len = errbuf_len;

    if (walk_dir(&walk, src_real) != 0) {
        for (i = 0; i < walk.ncentral; i++) {
            free(walk.centrals[i].name);
        }
        free(walk.centrals);
        zip_buf_free(&out);
        return -1;
    }

    qsort(walk.centrals, (size_t)walk.ncentral, sizeof(ZipCentral), cmp_central);

    cd_offset = (uint32_t)out.len;
    for (i = 0; i < walk.ncentral; i++) {
        ZipCentral *c = &walk.centrals[i];
        uint8_t ch[46];
        size_t name_len = strlen(c->name);
        memset(ch, 0, sizeof(ch));
        write_u32(ch, WEIZHI_ZIP_CENTRAL_SIG);
        write_u16(ch + 4, 20);
        write_u16(ch + 6, 20);
        write_u16(ch + 8, 0);
        write_u16(ch + 10, c->method);
        write_u32(ch + 16, c->crc);
        write_u32(ch + 20, c->comp_size);
        write_u32(ch + 24, c->uncomp_size);
        write_u16(ch + 28, (uint16_t)name_len);
        write_u32(ch + 42, c->local_offset);
        if (zip_buf_append(&out, ch, 46, walk.limit) != 0 ||
            zip_buf_append(&out, c->name, name_len, walk.limit) != 0) {
            snprintf(errbuf, errbuf_len, "too large: zip");
            for (i = 0; i < walk.ncentral; i++) {
                free(walk.centrals[i].name);
            }
            free(walk.centrals);
            zip_buf_free(&out);
            return -1;
        }
    }
    cd_size = (uint32_t)out.len - cd_offset;
    memset(eocd, 0, sizeof(eocd));
    write_u32(eocd, WEIZHI_ZIP_EOCD_SIG);
    write_u16(eocd + 8, (uint16_t)walk.ncentral);
    write_u16(eocd + 10, (uint16_t)walk.ncentral);
    write_u32(eocd + 12, cd_size);
    write_u32(eocd + 16, cd_offset);
    if (zip_buf_append(&out, eocd, 22, walk.limit) != 0) {
        snprintf(errbuf, errbuf_len, "too large: zip");
        for (i = 0; i < walk.ncentral; i++) {
            free(walk.centrals[i].name);
        }
        free(walk.centrals);
        zip_buf_free(&out);
        return -1;
    }

    written.data = out.data;
    written.len = out.len;
    out.data = NULL; /* ownership transferred temporarily */
    if (call_vfs(engine, WEIZHI_VFS_WRITE, zip_rel, NULL, &written, NULL, errbuf, errbuf_len) != 0) {
        free(written.data);
        for (i = 0; i < walk.ncentral; i++) {
            free(walk.centrals[i].name);
        }
        free(walk.centrals);
        return -1;
    }
    if (out_files) {
        *out_files = walk.ncentral;
    }
    free(written.data);
    for (i = 0; i < walk.ncentral; i++) {
        free(walk.centrals[i].name);
    }
    free(walk.centrals);
    return 0;
}
