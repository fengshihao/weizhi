#include "wtjson.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *skip_ws(const char *p) {
    while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t') {
        p++;
    }
    return p;
}

static const char *parse_str(const char *p, char *out, size_t out_len) {
    size_t i = 0;
    if (*p != '"') {
        return NULL;
    }
    p++;
    while (*p != '"') {
        char c;
        if (*p == '\0') {
            return NULL;
        }
        if (*p == '\\') {
            p++;
            switch (*p) {
                case '"': c = '"'; break;
                case '\\': c = '\\'; break;
                case '/': c = '/'; break;
                case 'b': c = '\b'; break;
                case 'f': c = '\f'; break;
                case 'n': c = '\n'; break;
                case 'r': c = '\r'; break;
                case 't': c = '\t'; break;
                case 'u': {
                    int v = 0;
                    int k;
                    p++;
                    for (k = 0; k < 4 && isxdigit((unsigned char)p[k]); k++) {
                        v = v * 16 + (isdigit((unsigned char)p[k]) ? p[k] - '0'
                                                                   : (tolower(p[k]) - 'a' + 10));
                    }
                    p += k - 1;
                    c = (k == 4 && v > 0 && v < 128) ? (char)v : '?';
                    break;
                }
                default:
                    return NULL;
            }
        } else {
            c = *p;
        }
        if (out != NULL && i + 1 < out_len) {
            out[i++] = c;
        }
        p++;
    }
    if (out != NULL) {
        out[i] = '\0';
    }
    return p + 1;
}

static const char *parse_i64(const char *p, int64_t *out) {
    char *end;
    long long v = strtoll(p, &end, 10);
    if (end == p) {
        return NULL;
    }
    *out = (int64_t)v;
    return end;
}

static const char *skip_value(const char *p) {
    int depth = 0;
    p = skip_ws(p);
    if (*p == '"') {
        return parse_str(p, NULL, 0);
    }
    while (*p != '\0') {
        if (*p == '{' || *p == '[') {
            depth++;
        } else if (*p == '}' || *p == ']') {
            if (depth == 0) {
                return p;
            }
            depth--;
            if (depth == 0) {
                return p + 1;
            }
        } else if (depth == 0 && (*p == ',' || *p == '}')) {
            return p;
        }
        p++;
    }
    return NULL;
}

/* Parse one i64 member value; used inside the "video" object. */
static const char *parse_num_member(const char *p, const char *key, int64_t *out, char *err,
                                    size_t err_len) {
    p = skip_ws(p);
    p = parse_i64(p, out);
    if (p == NULL) {
        snprintf(err, err_len, "bad argument: job json (bad number for %s)", key);
    }
    return p;
}

int wt_parse_job(const char *json, WtJobSpec *spec, char *err, size_t err_len) {
    const char *p;
    char key[64];
    memset(spec, 0, sizeof(*spec));
    if (json == NULL) {
        snprintf(err, err_len, "bad argument: job json (empty)");
        return -1;
    }
    p = skip_ws(json);
    if (*p != '{') {
        snprintf(err, err_len, "bad argument: job json (expected object)");
        return -1;
    }
    p++;
    for (;;) {
        p = skip_ws(p);
        if (*p == '}') {
            break;
        }
        if (*p == ',') {
            p++;
            continue;
        }
        p = parse_str(p, key, sizeof(key));
        if (p == NULL) {
            snprintf(err, err_len, "bad argument: job json (bad key)");
            return -1;
        }
        p = skip_ws(p);
        if (*p != ':') {
            snprintf(err, err_len, "bad argument: job json (expected ':')");
            return -1;
        }
        p = skip_ws(p + 1);
        if (strcmp(key, "output") == 0) {
            p = parse_str(p, spec->output, sizeof(spec->output));
        } else if (strcmp(key, "video") == 0) {
            if (*p != '{') {
                snprintf(err, err_len, "bad argument: job json (video must be an object)");
                return -1;
            }
            p = skip_ws(p + 1);
            while (*p != '}') {
                if (*p == ',') {
                    p = skip_ws(p + 1);
                    continue;
                }
                p = parse_str(p, key, sizeof(key));
                if (p == NULL) {
                    snprintf(err, err_len, "bad argument: job json (bad video key)");
                    return -1;
                }
                p = skip_ws(p);
                if (*p != ':') {
                    snprintf(err, err_len, "bad argument: job json (expected ':')");
                    return -1;
                }
                p = skip_ws(p + 1);
                if (strcmp(key, "width") == 0) {
                    p = parse_num_member(p, key, &spec->video.width, err, err_len);
                } else if (strcmp(key, "height") == 0) {
                    p = parse_num_member(p, key, &spec->video.height, err, err_len);
                } else if (strcmp(key, "bitrate") == 0) {
                    p = parse_num_member(p, key, &spec->video.bitrate, err, err_len);
                } else if (strcmp(key, "fps") == 0) {
                    p = parse_num_member(p, key, &spec->video.fps, err, err_len);
                } else {
                    p = skip_value(p);
                }
                if (p == NULL) {
                    return -1;
                }
                p = skip_ws(p);
            }
            p = skip_ws(p + 1);
        } else if (strcmp(key, "clips") == 0) {
            if (*p != '[') {
                snprintf(err, err_len, "bad argument: job json (clips must be an array)");
                return -1;
            }
            p = skip_ws(p + 1);
            while (*p != ']') {
                WtClip *clip;
                if (*p == ',') {
                    p = skip_ws(p + 1);
                    continue;
                }
                if (*p != '{' || spec->nclips >= WT_MAX_CLIPS) {
                    snprintf(err, err_len, "bad argument: job json (bad clip or too many clips)");
                    return -1;
                }
                clip = &spec->clips[spec->nclips];
                memset(clip, 0, sizeof(*clip));
                p = skip_ws(p + 1);
                while (*p != '}') {
                    if (*p == ',') {
                        p = skip_ws(p + 1);
                        continue;
                    }
                    p = parse_str(p, key, sizeof(key));
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad clip key)");
                        return -1;
                    }
                    p = skip_ws(p);
                    if (*p != ':') {
                        snprintf(err, err_len, "bad argument: job json (expected ':')");
                        return -1;
                    }
                    p = skip_ws(p + 1);
                    if (strcmp(key, "path") == 0) {
                        p = parse_str(p, clip->path, sizeof(clip->path));
                    } else if (strcmp(key, "startMs") == 0 || strcmp(key, "endMs") == 0) {
                        int64_t v = 0;
                        p = parse_num_member(p, key, &v, err, err_len);
                        if (p == NULL) {
                            return -1;
                        }
                        if (strcmp(key, "startMs") == 0) {
                            clip->start_ms = v;
                        } else {
                            clip->end_ms = v;
                        }
                    } else {
                        p = skip_value(p);
                    }
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad clip value)");
                        return -1;
                    }
                    p = skip_ws(p);
                }
                p = skip_ws(p + 1);
                spec->nclips++;
            }
            p = skip_ws(p + 1);
        } else {
            p = skip_value(p);
        }
        if (p == NULL) {
            snprintf(err, err_len, "bad argument: job json (bad value)");
            return -1;
        }
    }
    if (spec->nclips == 0 || spec->output[0] == '\0') {
        snprintf(err, err_len, "bad argument: job json (needs clips[] and output)");
        return -1;
    }
    return 0;
}
