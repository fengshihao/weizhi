#include "engine_internal.h"

#include <ctype.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_SCRIPT_FILE_BYTES (16u * 1024u * 1024u)
#define WEIZHI_PKG_FS_PREFIX "@weizhi-package:"
#define WEIZHI_PKG_INDEX "packages-index.json"
#define WEIZHI_PKG_DIR "packages"
#define MAX_PKG_NAME 64
#define MAX_PKG_VERSION 32
#define MAX_PKG_ENTRY 128

int weizhi_valid_package_name(const char *name) {
    size_t i;
    size_t len;
    if (name == NULL || name[0] == '\0') {
        return 0;
    }
    len = strlen(name);
    if (len > MAX_PKG_NAME || !isalnum((unsigned char)name[0])) {
        return 0;
    }
    for (i = 1; i < len; i++) {
        unsigned char c = (unsigned char)name[i];
        if (!(isalnum(c) || c == '.' || c == '_' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

int weizhi_set_package_root(WeizhiEngine *engine, const char *folder) {
    char *copy;
    if (engine == NULL || folder == NULL || folder[0] == '\0') {
        return -1;
    }
    if (atomic_load(&engine->state) != ST_IDLE) {
        return -1;
    }
    copy = strdup(folder);
    if (copy == NULL) {
        return -1;
    }
    free(engine->package_root);
    engine->package_root = copy;
    return 0;
}

static int read_text_file(const char *path, char **out, size_t *out_len) {
    FILE *file;
    long size;
    char *buf;
    if (out == NULL || out_len == NULL) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;
    file = fopen(path, "rb");
    if (file == NULL) {
        return -1;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return -1;
    }
    size = ftell(file);
    if (size < 0 || size > 1024 * 1024) {
        fclose(file);
        return -1;
    }
    rewind(file);
    buf = malloc((size_t)size + 1);
    if (buf == NULL || (size > 0 && fread(buf, 1, (size_t)size, file) != (size_t)size)) {
        free(buf);
        fclose(file);
        return -1;
    }
    fclose(file);
    buf[size] = '\0';
    *out = buf;
    *out_len = (size_t)size;
    return 0;
}

static int json_lookup_string(const char *json, const char *key, char *out, size_t out_len) {
    char pattern[96];
    const char *p;
    const char *q;
    size_t len;
    if (json == NULL || key == NULL || out == NULL || out_len == 0) {
        return -1;
    }
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    p = strstr(json, pattern);
    if (p == NULL) {
        return -1;
    }
    p += strlen(pattern);
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') {
        p++;
    }
    if (*p != ':') {
        return -1;
    }
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') {
        p++;
    }
    if (*p != '"') {
        return -1;
    }
    p++;
    q = strchr(p, '"');
    if (q == NULL) {
        return -1;
    }
    len = (size_t)(q - p);
    if (len + 1 > out_len) {
        return -1;
    }
    memcpy(out, p, len);
    out[len] = '\0';
    return 0;
}

static int package_index_version(Engine *engine, const char *name, char *version, size_t version_len) {
    char path[PATH_MAX];
    char *json = NULL;
    size_t json_len = 0;
    if (engine == NULL || engine->package_root == NULL) {
        return -1;
    }
    snprintf(path, sizeof(path), "%s/%s", engine->package_root, WEIZHI_PKG_INDEX);
    if (read_text_file(path, &json, &json_len) != 0) {
        return -1;
    }
    if (json_lookup_string(json, name, version, version_len) != 0) {
        free(json);
        return -1;
    }
    free(json);
    return 0;
}

int weizhi_package_entry_rel(Engine *engine, const char *install_dir, char *entry, size_t entry_len) {
    char manifest_path[PATH_MAX];
    char *json = NULL;
    size_t json_len = 0;
    (void)engine;
    snprintf(manifest_path, sizeof(manifest_path), "%s/manifest.json", install_dir);
    if (read_text_file(manifest_path, &json, &json_len) != 0) {
        snprintf(entry, entry_len, "index.js");
        return 0;
    }
    if (json_lookup_string(json, "entry", entry, entry_len) != 0) {
        /* nested js.entry in minimal manifests: try after "js" */
        const char *js = strstr(json, "\"js\"");
        if (js != NULL && json_lookup_string(js, "entry", entry, entry_len) != 0) {
            snprintf(entry, entry_len, "index.js");
        }
    }
    free(json);
    return 0;
}

static int resolve_install_dir(Engine *engine, const char *name, char *install_dir, size_t install_len) {
    char version[MAX_PKG_VERSION];
    if (!weizhi_valid_package_name(name)) {
        return -1;
    }
    if (package_index_version(engine, name, version, sizeof(version)) != 0) {
        return -1;
    }
    snprintf(install_dir, install_len, "%s/%s/%s@%s", engine->package_root, WEIZHI_PKG_DIR, name, version);
    return 0;
}

int weizhi_parse_weizhi_module_spec(const char *spec, char *name, size_t name_len, char *rel, size_t rel_len) {
    const char *rest;
    const char *slash;
    size_t nlen;
    if (spec == NULL || strncmp(spec, "weizhi:", 7) != 0) {
        return -1;
    }
    rest = spec + 7;
    if (rest[0] == '\0') {
        return -1;
    }
    slash = strchr(rest, '/');
    if (slash == NULL) {
        nlen = strlen(rest);
        if (nlen >= name_len || !weizhi_valid_package_name(rest)) {
            return -1;
        }
        memcpy(name, rest, nlen + 1);
        snprintf(rel, rel_len, "");
        return 0;
    }
    nlen = (size_t)(slash - rest);
    if (nlen >= name_len || nlen == 0) {
        return -1;
    }
    memcpy(name, rest, nlen);
    name[nlen] = '\0';
    if (!weizhi_valid_package_name(name)) {
        return -1;
    }
    snprintf(rel, rel_len, "%s", slash + 1);
    if (rel[0] == '\0' || strchr(rel, '\\') != NULL) {
        return -1;
    }
    return 0;
}

static int path_under_dir(const char *dir, const char *rel, char *out, size_t out_len) {
    char joined[PATH_MAX];
    char dir_real[PATH_MAX];
    char file_real[PATH_MAX];
    size_t dir_len;
    if (realpath(dir, dir_real) == NULL) {
        return -1;
    }
    snprintf(joined, sizeof(joined), "%s/%s", dir_real, rel);
    if (realpath(joined, file_real) == NULL) {
        return -1;
    }
    dir_len = strlen(dir_real);
    if (strncmp(file_real, dir_real, dir_len) != 0 ||
        (file_real[dir_len] != '/' && file_real[dir_len] != '\0')) {
        return -2;
    }
    snprintf(out, out_len, "%s", file_real);
    return 0;
}

int weizhi_read_package_module(Engine *engine, const char *module_spec, uint8_t **out, size_t *out_len,
                               char *errbuf, size_t errbuf_len) {
    char name[MAX_PKG_NAME];
    char rel[MAX_PKG_ENTRY];
    char install_dir[PATH_MAX];
    char entry[MAX_PKG_ENTRY];
    char path[PATH_MAX];
    const char *error = NULL;
    uint8_t *bytes;
    size_t length = 0;
    const char *file_rel;

    if (out == NULL || out_len == NULL) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;
    if (engine == NULL || module_spec == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "invalid module");
        }
        return -1;
    }
    if (engine->package_root == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "package root not set");
        }
        return -1;
    }
    if (weizhi_parse_weizhi_module_spec(module_spec, name, sizeof(name), rel, sizeof(rel)) != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "invalid weizhi module");
        }
        return -1;
    }
    if (resolve_install_dir(engine, name, install_dir, sizeof(install_dir)) != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "package not installed");
        }
        return -1;
    }
    if (rel[0] == '\0') {
        if (weizhi_package_entry_rel(engine, install_dir, entry, sizeof(entry)) != 0) {
            snprintf(entry, sizeof(entry), "index.js");
        }
        file_rel = entry;
    } else {
        file_rel = rel;
    }
    if (strlen(file_rel) < 4 || strcmp(file_rel + strlen(file_rel) - 3, ".js") != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "not a script path");
        }
        return -1;
    }
    if (path_under_dir(install_dir, file_rel, path, sizeof(path)) != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "package file not found");
        }
        return -1;
    }
    {
        FILE *file = fopen(path, "rb");
        long size;
        if (file == NULL) {
            if (errbuf != NULL && errbuf_len > 0) {
                snprintf(errbuf, errbuf_len, "package file not found");
            }
            return -1;
        }
        fseek(file, 0, SEEK_END);
        size = ftell(file);
        if (size < 0 || (size_t)size > MAX_SCRIPT_FILE_BYTES) {
            fclose(file);
            if (errbuf != NULL && errbuf_len > 0) {
                snprintf(errbuf, errbuf_len, "script file too large");
            }
            return -1;
        }
        rewind(file);
        bytes = malloc((size_t)size + 1);
        if (bytes == NULL || (size > 0 && fread(bytes, 1, (size_t)size, file) != (size_t)size)) {
            free(bytes);
            fclose(file);
            if (errbuf != NULL && errbuf_len > 0) {
                snprintf(errbuf, errbuf_len, "read failed");
            }
            return -1;
        }
        fclose(file);
        bytes[size] = '\0';
        length = (size_t)size;
    }
    *out = bytes;
    *out_len = length;
    (void)error;
    return 0;
}

int weizhi_package_install_dir(Engine *engine, const char *package_name, char *out, size_t out_len) {
    if (engine == NULL || package_name == NULL || out == NULL) {
        return -1;
    }
    return resolve_install_dir(engine, package_name, out, out_len);
}

int weizhi_resolve_package_asset_path(const char *install_dir, const char *rel, char *out, size_t out_len) {
    if (install_dir == NULL || rel == NULL || rel[0] == '\0' || strchr(rel, '\\') != NULL ||
        strstr(rel, "..") != NULL) {
        return -1;
    }
    return path_under_dir(install_dir, rel, out, out_len);
}

int weizhi_resolve_package_fs_path(Engine *engine, const char *path, char *out, size_t out_len,
                                   const char **error) {
    const char *abs;
    if (path == NULL || strncmp(path, WEIZHI_PKG_FS_PREFIX, strlen(WEIZHI_PKG_FS_PREFIX)) != 0) {
        if (error != NULL) {
            *error = "invalid path";
        }
        return -1;
    }
    abs = path + strlen(WEIZHI_PKG_FS_PREFIX);
    if (abs[0] == '\0') {
        if (error != NULL) {
            *error = "invalid path";
        }
        return -1;
    }
    if (engine == NULL || engine->package_root == NULL) {
        if (error != NULL) {
            *error = "package root not set";
        }
        return -1;
    }
    if (realpath(abs, out) == NULL) {
        if (error != NULL) {
            *error = "file not found";
        }
        return -1;
    }
    {
        char packages_real[PATH_MAX];
        snprintf(packages_real, sizeof(packages_real), "%s/%s", engine->package_root, WEIZHI_PKG_DIR);
        if (realpath(packages_real, packages_real) == NULL) {
            if (error != NULL) {
                *error = "path escape";
            }
            return -2;
        }
        {
            size_t plen = strlen(packages_real);
            if (strncmp(out, packages_real, plen) != 0 ||
                (out[plen] != '/' && out[plen] != '\0')) {
                if (error != NULL) {
                    *error = "path escape";
                }
                return -2;
            }
        }
    }
    return 0;
}

int weizhi_is_package_fs_path(const char *path) {
    return path != NULL && strncmp(path, WEIZHI_PKG_FS_PREFIX, strlen(WEIZHI_PKG_FS_PREFIX)) == 0;
}
