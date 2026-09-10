'use strict';
/**
 * SR15 P1:webview_exec 桥脚本(注入 bootstrap.html 占位符)。
 *
 * 协议(native → 页面):evaluateJavascript 下发 window.__agentRun(taskJson),
 * taskJson = {"taskId":..,"code":..,"inputB64":?,"wasmB64":?}(Gson 序列化,嵌套引号已转义)。
 *
 * 协议(页面 → native,NativeBridge @JavascriptInterface,JavaBridge 线程):
 * - 小结果(≤64KB):onResult(taskId, payloadJson, false),payloadJson = {"result":..}
 * - 大结果:strToB64 后按 48KB 分块 onChunk(taskId, seq, total, b64),收尾
 *   onResult(taskId, '{"chunked":true}', false),native 侧 ChunkAssembler 重组
 * - 错误:onResult(taskId, {"result":"错误串"}, true)
 */
(function () {
    // 与 native BridgeCodec 常量一致,改动须两侧同步
    var INLINE_LIMIT = 65536;   // BridgeCodec.INLINE_LIMIT
    var CHUNK_B64_CHARS = 49152; // BridgeCodec.CHUNK_B64_CHARS

    function b64ToBytes(b64) {
        var bin = atob(b64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) {
            bytes[i] = bin.charCodeAt(i);
        }
        return bytes;
    }

    /** UTF-8 字节 → base64(fromCharCode.apply 分片防大数组栈溢出)。 */
    function strToB64(s) {
        var u8 = new TextEncoder().encode(s);
        var bin = '';
        var SLICE = 0x8000;
        for (var i = 0; i < u8.length; i += SLICE) {
            bin += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + SLICE, u8.length)));
        }
        return btoa(bin);
    }

    function errMsg(e) {
        var m = (e && e.message) ? String(e.message) : String(e);
        if (e && e.stack) {
            m += '\n' + String(e.stack).substring(0, 2048);
        }
        return m.substring(0, 4096);
    }

    /** 结果回传:按 payload 长度直传或分块。 */
    function deliver(taskId, payloadJson, isError) {
        try {
            if (isError || payloadJson.length <= INLINE_LIMIT) {
                NativeBridge.onResult(taskId, payloadJson, isError);
                return;
            }
            var b64 = strToB64(payloadJson);
            var total = Math.ceil(b64.length / CHUNK_B64_CHARS);
            for (var seq = 0; seq < total; seq++) {
                NativeBridge.onChunk(taskId, seq, total,
                        b64.substr(seq * CHUNK_B64_CHARS, CHUNK_B64_CHARS));
            }
            NativeBridge.onResult(taskId, '{"chunked":true}', false);
        } catch (e) {
            // 桥本身坏了(理论上不会):尽力把错误带回,否则任务由 native 超时兜底
            try {
                NativeBridge.onResult(taskId,
                        JSON.stringify({ result: 'deliver failed: ' + errMsg(e) }), true);
            } catch (e2) { /* 桥已断,超时强杀兜底 */ }
        }
    }

    function deliverResult(taskId, value) {
        var payload;
        try {
            payload = JSON.stringify({ result: value === undefined ? null : value });
        } catch (e) {
            // 循环引用等不可序列化:降级字符串形态,native 侧加 note 说明
            try {
                payload = JSON.stringify({ result: String(value), unserializable: true });
            } catch (e2) {
                payload = JSON.stringify({ result: 'unserializable' });
            }
        }
        deliver(taskId, payload, false);
    }

    /**
     * 任务入口:native 下发任务 JSON。
     * code 以 new Function('input','loadWasm', ...) 包装——顶层 return 可用,
     * 全局只注入 input(Uint8Array|null)与 loadWasm()(WebAssembly.Module Promise)。
     */
    window.__agentRun = function (task) {
        var taskId = task.taskId;
        try {
            var input = task.inputB64 ? b64ToBytes(task.inputB64) : null;
            var wasmBytes = task.wasmB64 ? b64ToBytes(task.wasmB64) : null;
            var fn;
            try {
                fn = new Function('input', 'loadWasm', '"use strict";\n' + task.code);
            } catch (e) {
                deliver(taskId, JSON.stringify({ result: 'SyntaxError: ' + errMsg(e) }), true);
                return;
            }
            var loadWasm = function () {
                if (!wasmBytes) {
                    return Promise.reject(new Error('未提供 wasm_url,loadWasm() 不可用'));
                }
                return WebAssembly.compile(wasmBytes);
            };
            Promise.resolve()
                .then(function () { return fn(input, loadWasm); })
                .then(function (r) { deliverResult(taskId, r); },
                        function (e) { deliver(taskId, JSON.stringify({ result: errMsg(e) }), true); });
        } catch (e) {
            deliver(taskId, JSON.stringify({ result: errMsg(e) }), true);
        }
    };
})();
