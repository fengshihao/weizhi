// weizhi:video — video editing orchestration (docs/video-architecture.md P3).
// Pure-JS DSL that compiles a timeline spec into native plugin jobs:
//   remux      -> plugin "video"     (trim / concat, no re-encode; P1)
//   re-encode  -> plugin "transcode" (bitrate / fps; P2; filters + scaling run on
//                 the GPU path: decoder -> AImageReader -> OES texture ->
//                 shader -> encoder; P3b)
// Filter shaders are GLES2 fragment bodies WITHOUT the #extension/precision
// lines (the plugin prepends them). Required declarations are added here, so a
// custom body only needs uniforms and main(). One filter per job (native rule).
// NOTE: tests/test_engine.c embeds this file via CMake; keep that in sync.

const PRESETS = ['brightness', 'contrast', 'saturation', 'grayscale'];

const HEADER = 'varying vec2 vUV;\nuniform samplerExternalOES uVideo;\n';

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
};

function num(value, name) {
  if (value === undefined || value === null) return undefined;
  const n = Number(value);
  if (!isFinite(n) || n < 0) {
    throw new Error('bad argument: buildJob: ' + name + ' must be a non-negative number');
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
  const out = { path: clip.path };
  const start = num(clip.startMs, 'clips[' + index + '].startMs');
  const end = num(clip.endMs, 'clips[' + index + '].endMs');
  if (start !== undefined) out.startMs = start;
  if (end !== undefined) out.endMs = end;
  return out;
}

/** Compile one filter entry to {shader, uniforms}. */
function compileFilter(entry, index) {
  const where = 'filters[' + index + ']';
  let type = null;
  let amount = 0.2;
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
      return { shader: HEADER + entry.shader, uniforms };
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
  const uniforms = {};
  if (PRESET_GLSL[type].indexOf('uAmount') >= 0) uniforms.uAmount = amount;
  return { shader: HEADER + PRESET_GLSL[type], uniforms };
}

/**
 * Compile a spec into a plugin job. Spec:
 *   { clips: [{path, startMs?, endMs?}], output,
 *     bitrate?, width?, height?, fps?, reencode?, filters? }
 * Returns { plugin: "video"|"transcode", options, optionsJson }.
 */
export function buildJob(spec) {
  if (!spec || typeof spec !== 'object') {
    throw new Error('bad argument: buildJob: spec must be an object');
  }
  if (!Array.isArray(spec.clips) || spec.clips.length === 0) {
    throw new Error('bad argument: buildJob: clips must be a non-empty array');
  }
  if (typeof spec.output !== 'string' || spec.output === '') {
    throw new Error('bad argument: buildJob: output (string) is required');
  }
  const clips = spec.clips.map(normalizeClip);
  let filters = [];
  if (Array.isArray(spec.filters)) {
    filters = spec.filters.map(compileFilter);
  }
  const bitrate = num(spec.bitrate, 'bitrate');
  const width = num(spec.width, 'width');
  const height = num(spec.height, 'height');
  const fps = num(spec.fps, 'fps');
  const wantsEncode =
      spec.reencode === true || filters.length > 0 || bitrate !== undefined ||
      width !== undefined || height !== undefined || fps !== undefined;
  const optsCount = filters.length + (bitrate !== undefined) + (width !== undefined) +
      (height !== undefined) + (fps !== undefined);
  if (spec.reencode === false && optsCount > 0) {
    throw new Error(
        'bad argument: buildJob: remux cannot apply filters or video options (set reencode: true)');
  }
  const options = { clips, output: spec.output };
  if (wantsEncode) {
    const video = {};
    if (bitrate !== undefined) video.bitrate = bitrate;
    if (width !== undefined) video.width = width;
    if (height !== undefined) video.height = height;
    if (fps !== undefined) video.fps = fps;
    options.video = video;
    if (filters.length > 0) options.filters = filters;
    return { plugin: 'transcode', options, optionsJson: JSON.stringify(options) };
  }
  return { plugin: 'video', options, optionsJson: JSON.stringify(options) };
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
