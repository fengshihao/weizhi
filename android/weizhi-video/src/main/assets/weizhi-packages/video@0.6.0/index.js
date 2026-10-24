// weizhi:video — video editing orchestration (docs/video-architecture.md P3).
// Pure-JS DSL that compiles a timeline spec into native plugin jobs:
//   remux      -> plugin "video"     (trim / concat, no re-encode; P1)
//   re-encode  -> plugin "transcode" (bitrate / fps; P2; filters + scaling run on
//                 the GPU path: decoder -> AImageReader -> OES texture ->
//                 shader -> encoder; P3b; LUT + fade transitions: P3c)
// Filter shaders are GLES2 fragment bodies WITHOUT the #extension/precision
// lines (the plugin prepends them). Required declarations are added here, so a
// custom body only needs uniforms and main(). One filter per job (native rule).
// The lut preset points at an Adobe/IRIDAS .cube file that the plugin resolves
// against the engine fs root and uploads to texture unit 1 (the uLut sampler
// and uLutSize uniforms are bound natively). Transitions compile to per-clip
// fadeInMs/fadeOutMs and fade through black on the GPU; they compose with any
// filter and are video-only (audio passes through untouched).
// API surface: buildJob(spec), exportVideo(spec, opts?), probe(path) — probe
// returns the input's metadata ({tracks: [{codec, width, height, ...}], durationMs})
// so callers can validate trim points before exporting.
// Slides (P3d): a spec may carry slides: [{image, durationMs}] instead of clips
// (photo slideshow; the two cannot be mixed). Images decode via AImageDecoder
// (Android 11+) and draw letterboxed; output defaults to 1280x720@30 unless
// width/height/fps are set; transitions fade between slides; audio: '<path>'
// muxes a passthrough soundtrack from t=0. Slide shaders sample a plain 2D
// texture, so uVideo is declared sampler2D here (video jobs use OES).
// Timeline v2 (P5, docs/video-timeline-v2.md): a spec may carry tracks:
// [{type: 'video'|'image'|'text'|'audio', clips: [...]}] instead of clips/
// slides — multi-track compositing with per-property keyframes on transform
// (x, y, scale, rotation, opacity) and audio gain. Compilation + validation
// land in P5a; native execution phases in P5b+ (exportVideo reports
// "unsupported: v2 tracks export" until then).
// NOTE: tests/test_engine.c embeds this file via CMake; keep that in sync.

const PRESETS = ['brightness', 'contrast', 'saturation', 'grayscale', 'lut'];

const HEADER = 'varying vec2 vUV;\nuniform samplerExternalOES uVideo;\n';

const HEADER_2D = 'varying vec2 vUV;\nuniform sampler2D uVideo;\n';

const PRESET_GLSL = {
  brightness:
      'uniform float uAmount;\n' +
      'void main() { vec4 c = texture2D(uVideo, vUV);' +
      ' gl_FragColor = vec4(c.rgb * (1.0 + uAmount), c.a); }',
  contrast:
      'uniform float uAmount;\n' +
      'void main() { vec4 c = texture2D(uVideo, vUV);' +
      ' gl_FragColor = vec4((c.rgb - 0.5) * (1.0 + uAmount) + 0.5, c.a); }',
  saturation:
      'uniform float uAmount;\n' +
      'void main() { vec4 c = texture2D(uVideo, vUV);' +
      ' float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));' +
      ' gl_FragColor = vec4(mix(vec3(l), c.rgb, 1.0 + uAmount), c.a); }',
  grayscale:
      'void main() { vec4 c = texture2D(uVideo, vUV);' +
      ' float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));' +
      ' gl_FragColor = vec4(vec3(l), c.a); }',
  /* Trilinear 3D LUT sampled from the plugin's N x (N*N) 2D texture: blue
     picks the slice, red/green address inside it, with one interpolation
     across the two neighbouring slices. */
  lut:
      'uniform sampler2D uLut;\n' +
      'uniform float uLutSize;\n' +
      'uniform float uAmount;\n' +
      'void main() { vec3 c = texture2D(uVideo, vUV).rgb;' +
      ' float n = uLutSize;' +
      ' float b = clamp(c.b, 0.0, 1.0) * (n - 1.0);' +
      ' float b0 = floor(b);' +
      ' float b1 = min(b0 + 1.0, n - 1.0);' +
      ' vec2 rg = clamp(vec2(c.r, c.g), 0.0, 1.0) * (n - 1.0) + 0.5;' +
      ' vec2 uv0 = vec2(rg.x, b0 * n + rg.y) / vec2(n, n * n);' +
      ' vec2 uv1 = vec2(rg.x, b1 * n + rg.y) / vec2(n, n * n);' +
      ' vec3 m = mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, b - b0);' +
      ' gl_FragColor = vec4(mix(c, m, uAmount), 1.0); }',
};

function num(value, name) {
  if (value === undefined || value === null) return undefined;
  const n = Number(value);
  if (!isFinite(n) || n < 0) {
    throw new Error('bad argument: buildJob: ' + name + ' must be a non-negative number');
  }
  return n;
}

/* Transform coordinates / rotation are signed (x, y span [-1, 1]). */
function signedNum(value, name) {
  if (value === undefined || value === null) return undefined;
  const n = Number(value);
  if (!isFinite(n)) {
    throw new Error('bad argument: buildJob: ' + name + ' must be a number');
  }
  return n;
}

function normalizeClip(clip, index) {
  if (!clip || typeof clip !== 'object') {
    throw new Error('bad argument: buildJob: clips[' + index + '] must be an object');
  }
  if (typeof clip.path !== 'string' || clip.path === '') {
    throw new Error('bad argument: buildJob: clips[' + index + '].path (string) is required');
  }
  if (clip.fadeInMs !== undefined || clip.fadeOutMs !== undefined) {
    throw new Error('bad argument: buildJob: clips[' + index +
        '] fadeInMs/fadeOutMs are compiled from transitions, not settable');
  }
  const out = {path: clip.path};
  const start = num(clip.startMs, 'clips[' + index + '].startMs');
  const end = num(clip.endMs, 'clips[' + index + '].endMs');
  if (start !== undefined) out.startMs = start;
  if (end !== undefined) out.endMs = end;
  return out;
}

function normalizeSlide(slide, index) {
  if (!slide || typeof slide !== 'object') {
    throw new Error('bad argument: buildJob: slides[' + index + '] must be an object');
  }
  if (typeof slide.image !== 'string' || slide.image === '') {
    throw new Error('bad argument: buildJob: slides[' + index + '].image (string) is required');
  }
  if (slide.fadeInMs !== undefined || slide.fadeOutMs !== undefined) {
    throw new Error('bad argument: buildJob: slides[' + index +
        '] fadeInMs/fadeOutMs are compiled from transitions, not settable');
  }
  const duration = num(slide.durationMs, 'slides[' + index + '].durationMs');
  if (duration === undefined || duration <= 0) {
    throw new Error('bad argument: buildJob: slides[' + index +
        '].durationMs must be a positive number');
  }
  return {path: slide.image, durationMs: duration};
}

/** Compile one filter entry to {shader, uniforms, lut?}. */
function compileFilter(entry, index, header) {
  const where = 'filters[' + index + ']';
  let type = null;
  let amount;
  if (typeof entry === 'string') {
    type = entry;
  } else if (entry && typeof entry === 'object') {
    if (typeof entry.type === 'string') {
      type = entry.type;
      const a = num(entry.params && entry.params.amount, where + '.params.amount');
      if (a !== undefined) amount = a;
    } else if (typeof entry.shader === 'string' && entry.shader) {
      const uniforms = {};
      const src = entry.uniforms || {};
      for (const k of Object.keys(src)) {
        const v = num(src[k], where + '.uniforms.' + k);
        uniforms[k] = v === undefined ? 0 : v;
      }
      return {shader: header + entry.shader, uniforms};
    }
  }
  if (type === null) {
    throw new Error(
        'bad argument: buildJob: ' + where +
        ' must be a preset name/object or {shader, uniforms}');
  }
  if (PRESETS.indexOf(type) < 0) {
    throw new Error('bad argument: buildJob: ' + where + ' unknown preset "' + type + '"');
  }
  if (amount === undefined) amount = type === 'lut' ? 1 : 0.2;
  if (type === 'lut') {
    const path = entry.params && entry.params.path;
    if (typeof path !== 'string' || path === '') {
      throw new Error(
          'bad argument: buildJob: ' + where + ' lut requires params.path (string)');
    }
    return {shader: header + PRESET_GLSL.lut, uniforms: {uAmount: amount}, lut: path};
  }
  const uniforms = {};
  if (PRESET_GLSL[type].indexOf('uAmount') >= 0) uniforms.uAmount = amount;
  return {shader: header + PRESET_GLSL[type], uniforms};
}

/**
 * Compile spec.transitions into per-item fadeInMs/fadeOutMs fields; returns
 * true when any fade is active. A single object broadcasts to every boundary;
 * an array must have exactly items.length - 1 entries (one per boundary).
 */
function compileTransitions(spec, items, kind) {
  if (spec.transitions === undefined || spec.transitions === null) return false;
  if (items.length < 2) {
    throw new Error('bad argument: buildJob: transitions need at least two ' + kind);
  }
  const list = Array.isArray(spec.transitions)
      ? spec.transitions
      : new Array(items.length - 1).fill(spec.transitions);
  if (list.length === 0) return false;
  if (list.length !== items.length - 1) {
    throw new Error(
        'bad argument: buildJob: transitions must have ' + kind + '.length - 1 entries (got ' +
        list.length + ')');
  }
  for (let i = 0; i < list.length; i++) {
    const t = list[i];
    if (!t || typeof t !== 'object' || typeof t.type !== 'string') {
      throw new Error(
          'bad argument: buildJob: transitions[' + i + '] must be {type, durationMs}');
    }
    if (t.type !== 'fade') {
      throw new Error('unsupported: transition type "' + t.type + '" (only fade)');
    }
    const d = num(t.durationMs, 'transitions[' + i + '].durationMs');
    if (d === undefined || d <= 0) {
      throw new Error(
          'bad argument: buildJob: transitions[' + i +
          '].durationMs must be a positive number');
    }
    items[i].fadeOutMs = d;
    items[i + 1].fadeInMs = d;
  }
  return true;
}

/**
 * Compile a spec into a plugin job. Spec:
 *   { clips: [{path, startMs?, endMs?}], output,
 *     bitrate?, width?, height?, fps?, reencode?, filters?,
 *     transitions?: {type:'fade', durationMs} | [{type:'fade', durationMs}] }
 * or a photo slideshow (clips and slides are mutually exclusive):
 *   { slides: [{image, durationMs}], output,
 *     width?, height? (default 1280x720), fps? (default 30), bitrate?,
 *     filters?, transitions?, audio? }
 * Returns { plugin: "video"|"transcode", options, optionsJson }.
 */
export function buildJob(spec) {
  if (!spec || typeof spec !== 'object') {
    throw new Error('bad argument: buildJob: spec must be an object');
  }
  const hasTracks = Array.isArray(spec.tracks);
  const hasClips = Array.isArray(spec.clips) && spec.clips.length > 0;
  const hasSlides = Array.isArray(spec.slides) && spec.slides.length > 0;
  if (hasTracks && (hasClips || hasSlides)) {
    throw new Error('bad argument: buildJob: tracks cannot be mixed with clips/slides (v1 vs v2)');
  }
  if (hasTracks) {
    if (spec.tracks.length === 0) {
      throw new Error('bad argument: buildJob: tracks must be a non-empty array');
    }
    if (typeof spec.output !== 'string' || spec.output === '') {
      throw new Error('bad argument: buildJob: output (string) is required');
    }
    return buildV2Job(spec);
  }
  if (!hasClips && !hasSlides) {
    throw new Error('bad argument: buildJob: clips, slides or tracks must be a non-empty array');
  }
  if (hasClips && hasSlides) {
    throw new Error('bad argument: buildJob: clips and slides cannot be mixed in one job');
  }
  if (typeof spec.output !== 'string' || spec.output === '') {
    throw new Error('bad argument: buildJob: output (string) is required');
  }
  if (hasSlides) {
    return buildSlideJob(spec);
  }
  const clips = spec.clips.map(normalizeClip);
  const hasTransitions = compileTransitions(spec, clips, 'clips');
  let filters = [];
  if (Array.isArray(spec.filters)) {
    filters = spec.filters.map((entry, i) => compileFilter(entry, i, HEADER));
  }
  const bitrate = num(spec.bitrate, 'bitrate');
  const width = num(spec.width, 'width');
  const height = num(spec.height, 'height');
  const fps = num(spec.fps, 'fps');
  const wantsEncode =
      spec.reencode === true || filters.length > 0 || hasTransitions ||
      bitrate !== undefined || width !== undefined || height !== undefined || fps !== undefined;
  const optsCount = filters.length + (hasTransitions ? 1 : 0) + (bitrate !== undefined) +
      (width !== undefined) + (height !== undefined) + (fps !== undefined);
  if (spec.reencode === false && optsCount > 0) {
    throw new Error(
        'bad argument: buildJob: remux cannot apply filters, transitions or video options' +
        ' (set reencode: true)');
  }
  const options = {clips, output: spec.output};
  if (wantsEncode) {
    const video = {};
    if (bitrate !== undefined) video.bitrate = bitrate;
    if (width !== undefined) video.width = width;
    if (height !== undefined) video.height = height;
    if (fps !== undefined) video.fps = fps;
    options.video = video;
    if (filters.length > 0) options.filters = filters;
    return {plugin: 'transcode', options, optionsJson: JSON.stringify(options)};
  }
  return {plugin: 'video', options, optionsJson: JSON.stringify(options)};
}

/** Slideshow jobs always re-encode (plugin "transcode", 2D-texture GL path). */
function buildSlideJob(spec) {
  const slides = spec.slides.map(normalizeSlide);
  compileTransitions(spec, slides, 'slides');
  let filters = [];
  if (Array.isArray(spec.filters)) {
    filters = spec.filters.map((entry, i) => compileFilter(entry, i, HEADER_2D));
  }
  const width = num(spec.width, 'width');
  const height = num(spec.height, 'height');
  if ((width === undefined) !== (height === undefined)) {
    throw new Error('bad argument: buildJob: width and height must be set together');
  }
  const fps = num(spec.fps, 'fps');
  const bitrate = num(spec.bitrate, 'bitrate');
  if (spec.reencode === false) {
    throw new Error('bad argument: buildJob: slides always re-encode (reencode cannot be false)');
  }
  const video = {width: width === undefined ? 1280 : width, height: height === undefined ? 720 : height};
  if (fps !== undefined) video.fps = fps;
  if (bitrate !== undefined) video.bitrate = bitrate;
  const options = {slides, output: spec.output, video};
  if (filters.length > 0) options.filters = filters;
  if (spec.audio !== undefined && spec.audio !== null) {
    if (typeof spec.audio !== 'string' || spec.audio === '') {
      throw new Error('bad argument: buildJob: audio must be a path (string)');
    }
    options.audio = spec.audio;
  }
  return {plugin: 'transcode', options, optionsJson: JSON.stringify(options)};
}

/* ---- timeline v2 (P5, docs/video-timeline-v2.md): tracks + keyframes ----
 * User DSL compiles to an internal job whose shape is frozen here:
 *   {"v2":1,"output":...,"video":{width,height,fps?,bitrate?},
 *    "tracks":[{"type":"video|image|text|audio","clips":[
 *       {"path"|"text", inMs/outMs or durationMs, startMs,
 *        "transform":{x,y,scale,rotation,opacity} (constants, defaults filled),
 *        "kf":{prop:[{t,v,ip?}]},          // keyframes override the constant
 *        "filters":[...]}]}]}
 * v1 specs (clips/slides) keep the v1 job shape unchanged. */

const TRANSFORM_PROPS = ['x', 'y', 'scale', 'rotation', 'opacity'];
const TRANSFORM_DEFAULTS = {x: 0, y: 0, scale: 1, rotation: 0, opacity: 1};
const INTERP_KINDS = ['linear', 'ease'];

function normalizeKeyframes(list, where) {
  if (!Array.isArray(list) || list.length === 0) {
    throw new Error('bad argument: buildJob: ' + where + ' must be a non-empty keyframe array');
  }
  let lastT = -1;
  return list.map((kf, i) => {
    const at = where + '[' + i + ']';
    if (!kf || typeof kf !== 'object') {
      throw new Error('bad argument: buildJob: ' + at + ' must be {t, v, interp?}');
    }
    const t = num(kf.t, at + '.t');
    if (t === undefined) {
      throw new Error('bad argument: buildJob: ' + at + '.t (ms) is required');
    }
    const v = signedNum(kf.v, at + '.v');
    if (v === undefined) {
      throw new Error('bad argument: buildJob: ' + at + '.v is required');
    }
    let ip = 'linear';
    if (kf.interp !== undefined) {
      if (typeof kf.interp !== 'string' || INTERP_KINDS.indexOf(kf.interp) < 0) {
        throw new Error('bad argument: buildJob: ' + at + '.interp must be linear|ease');
      }
      ip = kf.interp;
    }
    if (t <= lastT) {
      throw new Error('bad argument: buildJob: ' + where + ' must be sorted by t (ascending)');
    }
    lastT = t;
    const out = {t, v};
    if (ip !== 'linear') out.ip = ip;
    return out;
  });
}

function normalizeTransform(raw, where) {
  const out = {x: TRANSFORM_DEFAULTS.x, y: TRANSFORM_DEFAULTS.y,
    scale: TRANSFORM_DEFAULTS.scale, rotation: TRANSFORM_DEFAULTS.rotation,
    opacity: TRANSFORM_DEFAULTS.opacity};
  const kf = {};
  if (raw === undefined || raw === null) return {transform: out, kf};
  if (typeof raw !== 'object') {
    throw new Error('bad argument: buildJob: ' + where + ' must be an object');
  }
  for (const prop of TRANSFORM_PROPS) {
    const val = raw[prop];
    if (val === undefined) continue;
    if (Array.isArray(val)) {
      kf[prop] = normalizeKeyframes(val, where + '.' + prop);
    } else {
      const n = signedNum(val, where + '.' + prop);
      if (n === undefined) {
        throw new Error('bad argument: buildJob: ' + where + '.' + prop + ' must be a number');
      }
      out[prop] = n;
    }
  }
  if ((out.opacity < 0 || out.opacity > 1) && kf.opacity === undefined) {
    throw new Error('bad argument: buildJob: ' + where + '.opacity must be within [0, 1]');
  }
  if (kf.opacity) {
    for (const k of kf.opacity) {
      if (k.v < 0 || k.v > 1) {
        throw new Error('bad argument: buildJob: ' + where + '.opacity keyframes must be within [0, 1]');
      }
    }
  }
  if (out.scale <= 0 && kf.scale === undefined) {
    throw new Error('bad argument: buildJob: ' + where + '.scale must be positive');
  }
  return {transform: out, kf};
}

const TRACK_TYPES = ['video', 'image', 'text', 'audio'];

function normalizeV2Clip(clip, type, index) {
  const at = 'clips[' + index + ']';
  if (!clip || typeof clip !== 'object') {
    throw new Error('bad argument: buildJob: ' + at + ' must be an object');
  }
  const out = {startMs: 0};
  if (type === 'text') {
    if (typeof clip.text !== 'string' || clip.text === '') {
      throw new Error('bad argument: buildJob: ' + at + '.text (string) is required');
    }
    out.text = clip.text;
    if (clip.style !== undefined && clip.style !== null) {
      if (typeof clip.style !== 'object') {
        throw new Error('bad argument: buildJob: ' + at + '.style must be an object');
      }
      out.style = clip.style;
    }
  } else {
    if (typeof clip.src !== 'string' || clip.src === '') {
      throw new Error('bad argument: buildJob: ' + at + '.src (string) is required');
    }
    out.path = clip.src;
  }
  const start = num(clip.startMs, at + '.startMs');
  if (start !== undefined) out.startMs = start;
  if (type === 'video' || type === 'audio') {
    const inMs = num(clip.inMs, at + '.inMs');
    const outMs = num(clip.outMs, at + '.outMs');
    if (inMs !== undefined) out.inMs = inMs;
    if (outMs !== undefined) out.outMs = outMs;
  } else {
    const dur = num(clip.durationMs, at + '.durationMs');
    if (dur === undefined || dur <= 0) {
      throw new Error('bad argument: buildJob: ' + at + '.durationMs must be a positive number');
    }
    out.durationMs = dur;
  }
  if (type !== 'audio') {
    const t = normalizeTransform(clip.transform, at + '.transform');
    out.transform = t.transform;
    if (Object.keys(t.kf).length > 0) out.kf = t.kf;
    if (Array.isArray(clip.effects)) {
      const header = type === 'video' ? HEADER : HEADER_2D;
      out.filters = clip.effects.map((entry, i) => compileFilter(entry, i, header));
    }
  } else if (clip.gain !== undefined && clip.gain !== null && !Array.isArray(clip.gain)) {
    const g = num(clip.gain, at + '.gain');
    if (g === undefined || g < 0) {
      throw new Error('bad argument: buildJob: ' + at + '.gain must be a non-negative number');
    }
    out.gain = g;
  } else if (Array.isArray(clip.gain)) {
    out.kf = {gain: normalizeKeyframes(clip.gain, at + '.gain')};
  }
  return out;
}

function normalizeTrack(track, index) {
  const at = 'tracks[' + index + ']';
  if (!track || typeof track !== 'object') {
    throw new Error('bad argument: buildJob: ' + at + ' must be an object');
  }
  if (TRACK_TYPES.indexOf(track.type) < 0) {
    throw new Error('bad argument: buildJob: ' + at + '.type must be one of ' + TRACK_TYPES.join('|'));
  }
  if (!Array.isArray(track.clips) || track.clips.length === 0) {
    throw new Error('bad argument: buildJob: ' + at + '.clips must be a non-empty array');
  }
  const clips = track.clips.map((clip, i) => normalizeV2Clip(clip, track.type, i));
  clips.sort((a, b) => a.startMs - b.startMs);
  for (let i = 1; i < clips.length; i++) {
    const prevEnd = clipEndMs(clips[i - 1], track.type);
    if (clips[i].startMs < prevEnd) {
      throw new Error('bad argument: buildJob: overlapping clips in ' + at +
          ' (use separate tracks to composite)');
    }
  }
  return {type: track.type, clips};
}

/** End of a clip on the timeline (Infinity for open-ended video/audio). */
function clipEndMs(clip, type) {
  if (type === 'video' || type === 'audio') {
    return clip.outMs !== undefined ? clip.startMs + (clip.outMs - (clip.inMs || 0))
                                    : Number.POSITIVE_INFINITY;
  }
  return clip.startMs + clip.durationMs;
}

function buildV2Job(spec) {
  const tracks = spec.tracks.map(normalizeTrack);
  const width = num(spec.width, 'width');
  const height = num(spec.height, 'height');
  if ((width === undefined) !== (height === undefined)) {
    throw new Error('bad argument: buildJob: width and height must be set together');
  }
  const video = {width: width === undefined ? 1280 : width,
    height: height === undefined ? 720 : height};
  const fps = num(spec.fps, 'fps');
  const bitrate = num(spec.bitrate, 'bitrate');
  if (fps !== undefined) video.fps = fps;
  if (bitrate !== undefined) video.bitrate = bitrate;
  const options = {v2: 1, tracks, output: spec.output, video};
  return {plugin: 'transcode', v2: true, options, optionsJson: JSON.stringify(options)};
}

/**
 * Build and run the job end-to-end. onProgress(event) receives
 * {clip, clipPercent, percent} during the run. Resolves with the job result
 * ({ok, output, clips, durationMs, width, height}); throws on any failure.
 */
export async function exportVideo(spec) {
  const job = buildJob(spec);
  const p = await host.ensureNative(job.plugin);
  const id = p.open_job(job.optionsJson);
  if (!id) {
    throw new Error('video: open_job failed (' + job.plugin + ')');
  }
  let parsed;
  try {
    const onProgress = typeof spec.onProgress === 'function' ? spec.onProgress : function () {};
    parsed = JSON.parse(p.run_job(id, onProgress));
  } finally {
    p.close_job(id);
  }
  if (parsed.error) {
    throw new Error('video: ' + parsed.error);
  }
  return parsed;
}

/**
 * Probe an input file's metadata via the remux plugin: resolves with
 * {tracks: [{...codec, width, height}], durationMs}. Use it to validate
 * trim points (startMs/endMs) before exportVideo.
 */
export async function probe(path) {
  if (typeof path !== 'string' || path.length === 0) {
    throw new Error('bad argument: probe: path (string) is required');
  }
  const v = await host.ensureNative('video');
  const parsed = JSON.parse(v.probe(path));
  if (parsed.error) {
    throw new Error('video: ' + parsed.error);
  }
  return parsed;
}

/* ---- Host-bridge surface (agent1 $tools; added in video@0.4.0) ----
   Frame grabs and audio-track extraction run on the agent1 host's native
   media tools (MediaMetadataRetriever / MediaExtractor+MediaMuxer) through
   the $tools bridge — not through the remux plugin and never through
   webview_exec. They need an agent1 host that exposes
   $tools.media_extract_frame / $tools.media_extract_audio. */

function hostTool(name) {
  const tools = typeof globalThis !== 'undefined' ? globalThis.$tools : undefined;
  if (!tools || typeof tools[name] !== 'function') {
    throw new Error('video: ' + name + ' requires the agent1 host $tools bridge');
  }
  return tools[name];
}

/** Extract one frame as JPEG. ss: seconds ("3.5") or "HH:MM:SS.mmm". */
export async function extractFrame(spec) {
  if (!spec || typeof spec !== 'object') {
    throw new Error('bad argument: extractFrame: spec must be an object');
  }
  if (typeof spec.path !== 'string' || spec.path === '') {
    throw new Error('bad argument: extractFrame: path (string) is required');
  }
  if (typeof spec.output !== 'string' || spec.output === '') {
    throw new Error('bad argument: extractFrame: output (string) is required');
  }
  const call = hostTool('media_extract_frame');
  return await call({
    path: spec.path,
    output: spec.output,
    ss: spec.ss === undefined || spec.ss === null ? '0' : String(spec.ss),
  });
}

function formatSs(seconds) {
  const t = Math.max(0, Math.floor(Number(seconds) || 0));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n;
  };
  return pad(h) + ':' + pad(m) + ':' + pad(s) + '.000';
}

/** Extract one JPEG every everySec seconds into outputDir. */
export async function extractFrames(spec) {
  if (!spec || typeof spec !== 'object') {
    throw new Error('bad argument: extractFrames: spec must be an object');
  }
  if (typeof spec.path !== 'string' || spec.path === '') {
    throw new Error('bad argument: extractFrames: path (string) is required');
  }
  const everySec = Number(spec.everySec);
  if (!isFinite(everySec) || everySec < 1) {
    throw new Error('bad argument: extractFrames: everySec must be >= 1');
  }
  const outputDir = (spec.outputDir || 'out/frames').replace(/\/+$/, '');
  const namePattern = spec.namePattern || 'frame_{t}s.jpg';
  const metaCall = hostTool('media_probe');
  const meta = await metaCall({path: spec.path});
  const durationMs = meta && meta.durationMs !== undefined ? Number(meta.durationMs) : 0;
  const maxSec = durationMs > 0 ? Math.floor(durationMs / 1000) : 0;
  const frames = [];
  for (let t = 0; t <= maxSec; t += everySec) {
    const output = outputDir + '/' + namePattern.replace('{t}', String(t));
    const r = await extractFrame({path: spec.path, output: output, ss: formatSs(t)});
    frames.push({t: t, output: output, result: r});
  }
  return {path: spec.path, everySec: everySec, count: frames.length, frames: frames};
}

/** Extract the audio track to an m4a file (no re-encode). */
export async function extractAudio(spec) {
  if (!spec || typeof spec !== 'object') {
    throw new Error('bad argument: extractAudio: spec must be an object');
  }
  if (typeof spec.path !== 'string' || spec.path === '') {
    throw new Error('bad argument: extractAudio: path (string) is required');
  }
  if (typeof spec.output !== 'string' || spec.output === '') {
    throw new Error('bad argument: extractAudio: output (string) is required');
  }
  const call = hostTool('media_extract_audio');
  return await call({
    path: spec.path,
    output: spec.output,
    format: spec.format === undefined || spec.format === null ? 'm4a' : String(spec.format),
  });
}
