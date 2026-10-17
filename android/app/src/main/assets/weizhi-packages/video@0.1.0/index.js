// weizhi:video — video editing orchestration (docs/video-architecture.md P3a).
// Pure-JS DSL that compiles a timeline spec into native plugin jobs:
//   remux      -> plugin "video"     (trim / concat, no re-encode; P1)
//   re-encode  -> plugin "transcode" (bitrate / fps; P2. filters execute on the
//                 GPU path that lands in P3b — the plugin reports
//                 "unsupported: filter" until then)
// NOTE: tests/test_engine.c embeds a copy of this file; keep them in sync.

const PRESETS = ['brightness', 'contrast', 'saturation', 'grayscale'];

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

function normalizeFilters(filters) {
  const out = [];
  for (let i = 0; i < filters.length; i++) {
    const f = filters[i];
    if (typeof f === 'string') {
      if (PRESETS.indexOf(f) < 0) {
        throw new Error('bad argument: buildJob: filters[' + i + '] unknown preset "' + f + '"');
      }
      out.push({ type: f });
    } else if (f && typeof f === 'object' && typeof f.shader === 'string' && f.shader) {
      // JS-delivered shader (P3b executes it on the GPU path).
      out.push({ shader: f.shader, uniforms: f.uniforms || {} });
    } else {
      throw new Error(
          'bad argument: buildJob: filters[' + i + '] must be a preset name or {shader, uniforms}');
    }
  }
  return out;
}

/**
 * Compile a spec into a plugin job. Spec:
 *   { clips: [{path, startMs?, endMs?}], output,
 *     bitrate?, width?, height?, fps?, reencode?, filters?, }
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
  const filters = Array.isArray(spec.filters) ? normalizeFilters(spec.filters) : [];
  const bitrate = num(spec.bitrate, 'bitrate');
  const width = num(spec.width, 'width');
  const height = num(spec.height, 'height');
  const fps = num(spec.fps, 'fps');
  const wantsEncode =
      spec.reencode === true || filters.length > 0 || bitrate !== undefined ||
      width !== undefined || height !== undefined || fps !== undefined;
  if (spec.reencode === false && wantsEncode && filters.length + (bitrate !== undefined) +
          (width !== undefined) + (height !== undefined) + (fps !== undefined) > 0) {
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
 * ({ok, output, clips, durationMs, ...}); throws on any failure.
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
