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

static const char *parse_f64(const char *p, double *out) {
    char *end;
    double v = strtod(p, &end);
    if (end == p) {
        return NULL;
    }
    *out = v;
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

/* Parse one f64 member value; used for v2 transform properties. */
static const char *parse_f64_member(const char *p, const char *key, double *out, char *err,
                                    size_t err_len) {
    p = skip_ws(p);
    p = parse_f64(p, out);
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
        } else if (strcmp(key, "filters") == 0) {
            if (*p != '[') {
                snprintf(err, err_len, "bad argument: job json (filters must be an array)");
                return -1;
            }
            p = skip_ws(p + 1);
            while (*p != ']') {
                WtFilter *filter;
                if (*p == ',') {
                    p = skip_ws(p + 1);
                    continue;
                }
                if (*p != '{' || spec->nfilters >= WT_MAX_FILTERS) {
                    snprintf(err, err_len, "bad argument: job json (bad filter or too many filters)");
                    return -1;
                }
                filter = &spec->filters[spec->nfilters];
                memset(filter, 0, sizeof(*filter));
                p = skip_ws(p + 1);
                while (*p != '}') {
                    if (*p == ',') {
                        p = skip_ws(p + 1);
                        continue;
                    }
                    p = parse_str(p, key, sizeof(key));
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad filter key)");
                        return -1;
                    }
                    p = skip_ws(p);
                    if (*p != ':') {
                        snprintf(err, err_len, "bad argument: job json (expected ':')");
                        return -1;
                    }
                    p = skip_ws(p + 1);
                    if (strcmp(key, "shader") == 0) {
                        const char *before = p;
                        p = parse_str(p, filter->shader, sizeof(filter->shader));
                        if (p == NULL) {
                            snprintf(err, err_len, "bad argument: job json (bad shader)");
                            return -1;
                        }
                        if (filter->shader[0] == '\0') {
                            snprintf(err, err_len, "bad argument: job json (empty shader)");
                            return -1;
                        }
                        if (strlen(filter->shader) >= sizeof(filter->shader) - 1) {
                            /* truncated: only safe if the copy clearly ended early */
                            (void)before;
                            snprintf(err, err_len, "bad argument: job json (shader too long)");
                            return -1;
                        }
                    } else if (strcmp(key, "uniforms") == 0) {
                        if (*p != '{') {
                            snprintf(err, err_len, "bad argument: job json (uniforms must be an object)");
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
                                snprintf(err, err_len, "bad argument: job json (bad uniform name)");
                                return -1;
                            }
                            p = skip_ws(p);
                            if (*p != ':') {
                                snprintf(err, err_len, "bad argument: job json (expected ':')");
                                return -1;
                            }
                            p = skip_ws(p + 1);
                            {
                                double vval = 0;
                                const char *num_start = p;
                                p = parse_f64(p, &vval);
                                if (p == num_start) {
                                    snprintf(err, err_len,
                                             "bad argument: job json (uniform %s must be a number)", key);
                                    return -1;
                                }
                                if (filter->nuniforms < WT_MAX_UNIFORMS) {
                                    snprintf(filter->uniforms[filter->nuniforms].name,
                                             WT_MAX_UNIFORM_NAME, "%s", key);
                                    filter->uniforms[filter->nuniforms].value = vval;
                                    filter->nuniforms++;
                                }
                            }
                            p = skip_ws(p);
                        }
                        p = skip_ws(p + 1);
                    } else if (strcmp(key, "lut") == 0) {
                        p = parse_str(p, filter->lut_path, sizeof(filter->lut_path));
                        if (p != NULL && filter->lut_path[0] == '\0') {
                            snprintf(err, err_len, "bad argument: job json (empty lut path)");
                            return -1;
                        }
                    } else {
                        p = skip_value(p);
                    }
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad filter value)");
                        return -1;
                    }
                    p = skip_ws(p);
                }
                p = skip_ws(p + 1);
                spec->nfilters++;
            }
            p = skip_ws(p + 1);
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
        } else if (strcmp(key, "audio") == 0) {
            p = parse_str(p, spec->audio, sizeof(spec->audio));
        } else if (strcmp(key, "v2") == 0) {
            int64_t v = 0;
            p = parse_num_member(p, key, &v, err, err_len);
            if (p != NULL && v == 1) {
                spec->v2 = 1;
            }
        } else if (strcmp(key, "tracks") == 0) {
            if (*p != '[') {
                snprintf(err, err_len, "bad argument: job json (tracks must be an array)");
                return -1;
            }
            p = skip_ws(p + 1);
            while (*p != ']') {
                WtTrack *track;
                if (*p == ',') {
                    p = skip_ws(p + 1);
                    continue;
                }
                if (*p != '{' || spec->ntracks >= WT_MAX_TRACKS) {
                    snprintf(err, err_len, "bad argument: job json (bad track or too many tracks)");
                    return -1;
                }
                track = &spec->tracks[spec->ntracks];
                memset(track, 0, sizeof(*track));
                track->type = -1;
                p = skip_ws(p + 1);
                while (*p != '}') {
                    if (*p == ',') {
                        p = skip_ws(p + 1);
                        continue;
                    }
                    p = parse_str(p, key, sizeof(key));
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad track key)");
                        return -1;
                    }
                    p = skip_ws(p);
                    if (*p != ':') {
                        snprintf(err, err_len, "bad argument: job json (expected ':')");
                        return -1;
                    }
                    p = skip_ws(p + 1);
                    if (strcmp(key, "type") == 0) {
                        char tname[16];
                        p = parse_str(p, tname, sizeof(tname));
                        if (p == NULL) {
                            snprintf(err, err_len, "bad argument: job json (bad track type)");
                            return -1;
                        }
                        if (strcmp(tname, "video") == 0) {
                            track->type = WT_TRACK_VIDEO;
                        } else if (strcmp(tname, "image") == 0) {
                            track->type = WT_TRACK_IMAGE;
                        } else if (strcmp(tname, "text") == 0) {
                            track->type = WT_TRACK_TEXT;
                        } else if (strcmp(tname, "audio") == 0) {
                            track->type = WT_TRACK_AUDIO;
                        } else {
                            snprintf(err, err_len,
                                     "bad argument: job json (unknown track type %s)", tname);
                            return -1;
                        }
                    } else if (strcmp(key, "clips") == 0) {
                        if (*p != '[') {
                            snprintf(err, err_len,
                                     "bad argument: job json (track clips must be an array)");
                            return -1;
                        }
                        p = skip_ws(p + 1);
                        while (*p != ']') {
                            WtV2Clip *clip;
                            if (*p == ',') {
                                p = skip_ws(p + 1);
                                continue;
                            }
                            if (*p != '{' || track->nclips >= WT_MAX_TRACK_CLIPS) {
                                snprintf(err, err_len,
                                         "bad argument: job json (bad clip or too many track clips)");
                                return -1;
                            }
                            clip = &track->clips[track->nclips];
                            memset(clip, 0, sizeof(*clip));
                            clip->transform.scale = 1.0;
                            clip->transform.opacity = 1.0;
                            p = skip_ws(p + 1);
                            while (*p != '}') {
                                if (*p == ',') {
                                    p = skip_ws(p + 1);
                                    continue;
                                }
                                p = parse_str(p, key, sizeof(key));
                                if (p == NULL) {
                                    snprintf(err, err_len,
                                             "bad argument: job json (bad v2 clip key)");
                                    return -1;
                                }
                                p = skip_ws(p);
                                if (*p != ':') {
                                    snprintf(err, err_len,
                                             "bad argument: job json (expected ':')");
                                    return -1;
                                }
                                p = skip_ws(p + 1);
                                if (strcmp(key, "path") == 0) {
                                    p = parse_str(p, clip->path, sizeof(clip->path));
                                } else if (strcmp(key, "text") == 0) {
                                    p = parse_str(p, clip->text, sizeof(clip->text));
                                } else if (strcmp(key, "startMs") == 0 ||
                                           strcmp(key, "inMs") == 0 || strcmp(key, "outMs") == 0 ||
                                           strcmp(key, "durationMs") == 0) {
                                    int64_t v = 0;
                                    p = parse_num_member(p, key, &v, err, err_len);
                                    if (p == NULL) {
                                        return -1;
                                    }
                                    if (strcmp(key, "startMs") == 0) {
                                        clip->start_ms = v;
                                    } else if (strcmp(key, "inMs") == 0) {
                                        clip->in_ms = v;
                                    } else if (strcmp(key, "outMs") == 0) {
                                        clip->out_ms = v;
                                        clip->has_out = 1;
                                    } else {
                                        clip->duration_ms = v;
                                        clip->has_duration = 1;
                                    }
                                } else if (strcmp(key, "transform") == 0) {
                                    if (*p != '{') {
                                        snprintf(err, err_len,
                                                 "bad argument: job json (transform must be an object)");
                                        return -1;
                                    }
                                    p = skip_ws(p + 1);
                                    while (*p != '}') {
                                        double tv;
                                        if (*p == ',') {
                                            p = skip_ws(p + 1);
                                            continue;
                                        }
                                        p = parse_str(p, key, sizeof(key));
                                        if (p == NULL) {
                                            snprintf(err, err_len,
                                                     "bad argument: job json (bad transform key)");
                                            return -1;
                                        }
                                        p = skip_ws(p);
                                        if (*p != ':') {
                                            snprintf(err, err_len,
                                                     "bad argument: job json (expected ':')");
                                            return -1;
                                        }
                                        p = skip_ws(p + 1);
                                        p = parse_f64_member(p, key, &tv, err, err_len);
                                        if (p == NULL) {
                                            return -1;
                                        }
                                        if (strcmp(key, "x") == 0) {
                                            clip->transform.x = tv;
                                        } else if (strcmp(key, "y") == 0) {
                                            clip->transform.y = tv;
                                        } else if (strcmp(key, "scale") == 0) {
                                            clip->transform.scale = tv;
                                        } else if (strcmp(key, "rotation") == 0) {
                                            clip->transform.rotation = tv;
                                        } else if (strcmp(key, "opacity") == 0) {
                                            clip->transform.opacity = tv;
                                        }
                                        p = skip_ws(p);
                                    }
                                    p = skip_ws(p + 1);
                                } else {
                                    if (strcmp(key, "kf") == 0) {
                                        clip->has_kf = 1;
                                    } else if (strcmp(key, "filters") == 0 ||
                                               strcmp(key, "effects") == 0) {
                                        clip->has_filters = 1;
                                    }
                                    p = skip_value(p);
                                }
                                if (p == NULL) {
                                    snprintf(err, err_len,
                                             "bad argument: job json (bad v2 clip value)");
                                    return -1;
                                }
                                p = skip_ws(p);
                            }
                            p = skip_ws(p + 1);
                            track->nclips++;
                        }
                        p = skip_ws(p + 1);
                    } else {
                        p = skip_value(p);
                    }
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad track value)");
                        return -1;
                    }
                    p = skip_ws(p);
                }
                p = skip_ws(p + 1);
                if (track->type < 0) {
                    snprintf(err, err_len, "bad argument: job json (track type is required)");
                    return -1;
                }
                spec->ntracks++;
            }
            p = skip_ws(p + 1);
        } else if (strcmp(key, "slides") == 0) {
            if (*p != '[') {
                snprintf(err, err_len, "bad argument: job json (slides must be an array)");
                return -1;
            }
            p = skip_ws(p + 1);
            while (*p != ']') {
                WtSlide *slide;
                if (*p == ',') {
                    p = skip_ws(p + 1);
                    continue;
                }
                if (*p != '{' || spec->nslides >= WT_MAX_SLIDES) {
                    snprintf(err, err_len, "bad argument: job json (bad slide or too many slides)");
                    return -1;
                }
                slide = &spec->slides[spec->nslides];
                memset(slide, 0, sizeof(*slide));
                p = skip_ws(p + 1);
                while (*p != '}') {
                    if (*p == ',') {
                        p = skip_ws(p + 1);
                        continue;
                    }
                    p = parse_str(p, key, sizeof(key));
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad slide key)");
                        return -1;
                    }
                    p = skip_ws(p);
                    if (*p != ':') {
                        snprintf(err, err_len, "bad argument: job json (expected ':')");
                        return -1;
                    }
                    p = skip_ws(p + 1);
                    if (strcmp(key, "path") == 0) {
                        p = parse_str(p, slide->path, sizeof(slide->path));
                    } else if (strcmp(key, "durationMs") == 0 || strcmp(key, "fadeInMs") == 0 ||
                               strcmp(key, "fadeOutMs") == 0) {
                        int64_t v = 0;
                        p = parse_num_member(p, key, &v, err, err_len);
                        if (p == NULL) {
                            return -1;
                        }
                        if (strcmp(key, "durationMs") == 0) {
                            slide->duration_ms = v;
                        } else if (strcmp(key, "fadeInMs") == 0) {
                            slide->fade_in_ms = v;
                        } else {
                            slide->fade_out_ms = v;
                        }
                    } else {
                        p = skip_value(p);
                    }
                    if (p == NULL) {
                        snprintf(err, err_len, "bad argument: job json (bad slide value)");
                        return -1;
                    }
                    p = skip_ws(p);
                }
                p = skip_ws(p + 1);
                spec->nslides++;
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
                    } else if (strcmp(key, "startMs") == 0 || strcmp(key, "endMs") == 0 ||
                               strcmp(key, "fadeInMs") == 0 || strcmp(key, "fadeOutMs") == 0) {
                        int64_t v = 0;
                        p = parse_num_member(p, key, &v, err, err_len);
                        if (p == NULL) {
                            return -1;
                        }
                        if (strcmp(key, "startMs") == 0) {
                            clip->start_ms = v;
                        } else if (strcmp(key, "endMs") == 0) {
                            clip->end_ms = v;
                        } else if (strcmp(key, "fadeInMs") == 0) {
                            clip->fade_in_ms = v;
                        } else {
                            clip->fade_out_ms = v;
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
    if (spec->v2) {
        if (spec->ntracks == 0 || spec->output[0] == '\0') {
            snprintf(err, err_len, "bad argument: job json (needs tracks[] and output)");
            return -1;
        }
        return 0;
    }
    if ((spec->nclips == 0 && spec->nslides == 0) || spec->output[0] == '\0') {
        snprintf(err, err_len, "bad argument: job json (needs clips[] or slides[] and output)");
        return -1;
    }
    if (spec->nclips > 0 && spec->nslides > 0) {
        snprintf(err, err_len, "bad argument: job json (clips[] and slides[] cannot be mixed)");
        return -1;
    }
    return 0;
}
