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

  function safeUrl(url) {
    return String(url).replace(/([?&#](?:key|api_key|token|access_token)=)[^&#]*/gi, "$1***");
  }

  function rpcError(url, method, resp) {
    var detail = resp && resp.error ? JSON.stringify(resp.error) : String(resp);
    return new Error("mcp rpc error, method=" + method + ", error=" + detail + ", url=" + safeUrl(url));
  }

  function Client(url, headers) {
    this.url = url;
    this.headers = headers;
    this.nextId = 1;
    this.sessionReady = false;
    this.opening = null;
    this.sessionId = "";
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

  Client.prototype._post = function (rpc) {
    var self = this;
    var hdrs = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream"
    };
    var k;
    if (!self.versionRejected) {
      hdrs["MCP-Protocol-Version"] = PROTOCOL;
    }
    if (self.sessionId) {
      hdrs["Mcp-Session-Id"] = self.sessionId;
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
      var sid = "";
      try {
        sid = res.headers.get("mcp-session-id") || "";
      } catch (e) {
        sid = "";
      }
      if (sid) {
        self.sessionId = sid;
      }
      return res.text().then(function (text) {
        var ct = "";
        var body = null;
        try {
          ct = res.headers.get("content-type") || "";
        } catch (e2) {
          ct = "";
        }
        if (ct.indexOf("text/event-stream") >= 0) {
          body = parseSse(text);
        } else {
          try {
            body = text ? JSON.parse(text) : null;
          } catch (e3) {
            body = null;
          }
        }
        if (!res.ok && !(body && body.error)) {
          return null;
        }
        return body;
      });
    });
  };

  Client.prototype._rpc = function (method, params) {
    var rpc = { jsonrpc: "2.0", id: this.nextId++, method: method };
    if (params != null) {
      rpc.params = params;
    }
    return this._post(rpc);
  };

  Client.prototype._notifyInitialized = function () {
    return this._post({ jsonrpc: "2.0", method: "notifications/initialized" }).then(function (resp) {
      if (resp && resp.error) {
        throw rpcError(this.url, "notifications/initialized", resp);
      }
    }.bind(this));
  };

  /** 第一次 list/call 之前握手。connect 本身不联网，App 启动也不握手。 */
  Client.prototype._ensureSession = function () {
    var self = this;
    var initParams;
    if (self.sessionReady) {
      return Promise.resolve();
    }
    if (self.opening) {
      return self.opening;
    }
    initParams = {
      protocolVersion: PROTOCOL,
      capabilities: {},
      clientInfo: { name: "weizhi", version: "1.0" }
    };
    self.opening = self._rpc("initialize", initParams).then(function (resp) {
      if (resp && resp.error && !self.versionRejected && isVersionRejected(resp)) {
        self.versionRejected = true;
        return self._rpc("initialize", initParams);
      }
      return resp;
    }).then(function (resp) {
      if (!resp || resp.error) {
        throw rpcError(self.url, "initialize", resp);
      }
      return self._notifyInitialized();
    }).then(function () {
      self.sessionReady = true;
      self.opening = null;
    }, function (err) {
      self.opening = null;
      self.sessionReady = false;
      throw err;
    });
    return self.opening;
  };

  Client.prototype._request = function (method, params) {
    var self = this;
    return self._ensureOpen().then(function () {
      return self._ensureSession();
    }).then(function () {
      return self._rpc(method, params);
    }).then(function (resp) {
      if (!resp) {
        throw new Error("mcp request failed (no response), method=" + method + ", url=" + safeUrl(self.url));
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
