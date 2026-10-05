(function () {
  var PROTOCOL = "2026-07-28";

  function copyHeaders(src) {
    var out = {};
    if (!src || typeof src !== "object") {
      return out;
    }
    for (var k in src) {
      if (Object.prototype.hasOwnProperty.call(src, k) && typeof src[k] === "string") {
        out[k] = src[k];
      }
    }
    return out;
  }

  function parseSse(text) {
    var lines = String(text).split("\n");
    var i;
    for (i = 0; i < lines.length; i++) {
      var line = lines[i];
      var payload;
      var obj;
      if (line.indexOf("data:") !== 0) {
        continue;
      }
      payload = line.substring(5).replace(/^\s+/, "");
      if (!payload || payload === "[DONE]") {
        continue;
      }
      try {
        obj = JSON.parse(payload);
      } catch (e) {
        continue;
      }
      if (obj && obj.id != null && (obj.result !== undefined || obj.error !== undefined)) {
        return obj;
      }
    }
    return null;
  }

  function isVersionRejected(resp) {
    var msg;
    if (!resp || !resp.error) {
      return false;
    }
    msg = resp.error.message || "";
    return msg.indexOf("Unsupported protocol version") >= 0
      || msg.indexOf("UnsupportedProtocolVersion") >= 0;
  }

  function rpcError(url, method, resp) {
    var detail = resp && resp.error ? JSON.stringify(resp.error) : String(resp);
    return new Error("mcp rpc error, url=" + url + ", method=" + method + ", error=" + detail);
  }

  function Client(url, headers) {
    this.url = url;
    this.headers = headers;
    this.nextId = 1;
    this.initialized = false;
    this.versionRejected = false;
    this.closed = false;
  }

  Client.prototype.close = function () {
    this.closed = true;
    return Promise.resolve();
  };

  Client.prototype._ensureOpen = function () {
    if (this.closed) {
      return Promise.reject(new Error("mcp client is closed"));
    }
    return Promise.resolve();
  };

  Client.prototype._doRequest = function (method, params) {
    var self = this;
    var rpc = { jsonrpc: "2.0", id: self.nextId++, method: method };
    var hdrs = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream"
    };
    var k;
    if (params != null) {
      rpc.params = params;
    }
    if (!self.versionRejected) {
      hdrs["MCP-Protocol-Version"] = PROTOCOL;
    }
    for (k in self.headers) {
      if (Object.prototype.hasOwnProperty.call(self.headers, k)) {
        hdrs[k] = self.headers[k];
      }
    }
    return fetch(self.url, {
      method: "POST",
      headers: hdrs,
      body: JSON.stringify(rpc)
    }).then(function (res) {
      return res.text().then(function (text) {
        var ct = "";
        var body = null;
        try {
          ct = res.headers.get("content-type") || "";
        } catch (e) {
          ct = "";
        }
        if (ct.indexOf("text/event-stream") >= 0) {
          body = parseSse(text);
        } else {
          try {
            body = text ? JSON.parse(text) : null;
          } catch (e2) {
            body = null;
          }
        }
        if (!res.ok) {
          if (body && body.error) {
            return body;
          }
          return null;
        }
        return body;
      });
    });
  };

  Client.prototype._request = function (method, params) {
    var self = this;
    return self._ensureOpen().then(function () {
      return self._doRequest(method, params);
    }).then(function (resp) {
      if (resp && !resp.error) {
        return resp;
      }
      if (resp && !self.versionRejected && isVersionRejected(resp)) {
        self.versionRejected = true;
        return self._doRequest(method, params);
      }
      return resp;
    }).then(function (resp) {
      if (resp && !resp.error) {
        return resp;
      }
      if (!self.initialized) {
        self.initialized = true;
        return self._doRequest("initialize", {
          protocolVersion: PROTOCOL,
          capabilities: {},
          clientInfo: { name: "weizhi", version: "1.0" }
        }).then(function () {
          return self._doRequest(method, params);
        });
      }
      return resp;
    }).then(function (resp) {
      if (!resp) {
        throw new Error("mcp request failed (no response), url=" + self.url + ", method=" + method);
      }
      if (resp.error) {
        throw rpcError(self.url, method, resp);
      }
      return resp;
    });
  };

  Client.prototype.listTools = function () {
    return this._request("tools/list", null).then(function (resp) {
      var tools = resp.result && resp.result.tools ? resp.result.tools : [];
      var out = [];
      var i;
      for (i = 0; i < tools.length; i++) {
        var t = tools[i];
        if (!t || typeof t.name !== "string" || !t.name) {
          continue;
        }
        out.push({
          name: t.name,
          description: typeof t.description === "string" ? t.description : "",
          inputSchema: t.inputSchema && typeof t.inputSchema === "object" ? t.inputSchema : {}
        });
      }
      return out;
    });
  };

  Client.prototype.callTool = function (name, args) {
    if (typeof name !== "string" || !name) {
      return Promise.reject(new TypeError("bad argument: mcp.callTool: name is required"));
    }
    return this._request("tools/call", {
      name: name,
      arguments: args && typeof args === "object" ? args : {}
    }).then(function (resp) {
      var result = resp.result && typeof resp.result === "object" ? resp.result : {};
      var content = result.content;
      var text = "";
      var i;
      if (Array.isArray(content)) {
        for (i = 0; i < content.length; i++) {
          var c = content[i];
          if (c && c.type === "text" && typeof c.text === "string") {
            if (text) {
              text += "\n";
            }
            text += c.text;
          }
        }
      }
      if (!text) {
        text = Object.keys(result).length === 0 ? "(empty result)" : JSON.stringify(result);
      }
      return { isError: !!result.isError, text: text };
    });
  };

  globalThis.mcp = {
    connect: function (options) {
      var url;
      options = options || {};
      url = options.url;
      if (typeof url !== "string" || (url.indexOf("http://") !== 0 && url.indexOf("https://") !== 0)) {
        return Promise.reject(new TypeError(
          "bad argument: mcp.connect: url must start with http:// or https://"));
      }
      return Promise.resolve(new Client(url, copyHeaders(options.headers)));
    }
  };
})();
