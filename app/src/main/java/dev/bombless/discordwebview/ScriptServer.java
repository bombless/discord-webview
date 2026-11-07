package dev.bombless.discordwebview;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ScriptServer implements AutoCloseable {
    public static final int PORT = 8765;
    private static final int MAX_BODY = 1024 * 1024;
    private static final Pattern VERSION_ID = Pattern.compile("[0-9]{8}T[0-9]{6}-[0-9]{3}");
    private final File root;
    private final File versionsDir;
    private final File currentFile;
    private final File activeVersionFile;
    private final Consumer<String> onScript;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;
    private String activeVersion = "default";

    public ScriptServer(Context context, File root, Consumer<String> onScript) {
        this.assetContext = context.getApplicationContext();
        this.root = root;
        this.versionsDir = new File(root, "versions");
        this.currentFile = new File(root, "app.js");
        this.activeVersionFile = new File(root, "active-version.txt");
        this.onScript = onScript;
    }

    public void start() throws Exception {
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Cannot create script directory");
        if (!versionsDir.exists() && !versionsDir.mkdirs()) throw new IllegalStateException("Cannot create versions directory");
        if (!currentFile.exists()) {
            String initial = readAsset();
            Files.writeString(currentFile.toPath(), initial, StandardCharsets.UTF_8);
            saveVersion(initial);
        } else if (activeVersionFile.isFile()) {
            activeVersion = Files.readString(activeVersionFile.toPath(), StandardCharsets.UTF_8).trim();
        } else {
            activeVersion = findActiveVersion();
            persistActiveVersion();
        }
        onScript.accept(readCurrent());
        server = new ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread thread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    clients.execute(() -> handle(socket));
                } catch (Exception e) {
                    if (running) android.util.Log.e("ScriptServer", "Accept failed", e);
                }
            }
        }, "script-server");
        thread.setDaemon(true);
        thread.start();
    }

    private String readAsset() throws Exception {
        try (var in = androidAssetStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private java.io.InputStream androidAssetStream() throws Exception {
        return assetContext.getAssets().open("app.js");
    }

    private Context assetContext;
    public String getCurrentScript() throws Exception {
        return readCurrent();
    }

    private String readCurrent() throws Exception {
        return Files.readString(currentFile.toPath(), StandardCharsets.UTF_8);
    }

    private String findActiveVersion() {
        File[] files = versionsDir.listFiles((dir, name) -> name.endsWith(".js"));
        if (files == null || files.length == 0) return "unversioned";
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0].getName().replace(".js", "");
    }

    private void persistActiveVersion() throws Exception {
        Files.writeString(activeVersionFile.toPath(), activeVersion, StandardCharsets.UTF_8);
    }

    private synchronized String saveVersion(String script) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(new Date());
        int suffix = 1;
        String id;
        do {
            id = String.format(Locale.US, "%s-%03d", stamp, suffix++);
        } while (new File(versionsDir, id + ".js").exists());
        Files.writeString(new File(versionsDir, id + ".js").toPath(), script, StandardCharsets.UTF_8);
        activeVersion = id;
        persistActiveVersion();
        return id;
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10000);
            java.io.InputStream input = socket.getInputStream();
            String requestLine = readHttpLine(input);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1].split("\\?", 2)[0];
            int contentLength = 0;
            String line;
            while ((line = readHttpLine(input)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            if (contentLength > MAX_BODY) {
                respond(socket, 413, "application/json", new JSONObject().put("error", "body too large").toString());
                return;
            }
            byte[] body = readBody(input, contentLength);
            route(socket, method, path, body);
        } catch (Exception e) {
            android.util.Log.e("ScriptServer", "Request failed", e);
            try { respond(socket, 500, "application/json", new JSONObject().put("error", e.getMessage()).toString()); }
            catch (Exception ignored) { }
        }
    }

    private String readHttpLine(java.io.InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\\n') break;
            if (value != '\\r') line.write(value);
            if (line.size() > 8192) throw new IllegalArgumentException("HTTP header too large");
        }
        if (value == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.UTF_8);
    }

    private byte[] readBody(java.io.InputStream input, int length) throws Exception {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = input.read(body, offset, length - offset);
            if (n < 0) throw new java.io.EOFException("Incomplete request body");
            offset += n;
        }
        return body;
    }

    private void route(Socket socket, String method, String path, byte[] body) throws Exception {
        if (method.equals("OPTIONS")) {
            respond(socket, 204, "text/plain", "");
            return;
        }
        if (path.equals("/") && method.equals("GET")) {
            respond(socket, 200, "text/html; charset=utf-8", desktopEditor());
        } else if (path.equals("/api/state") && method.equals("GET")) {
            JSONObject state = new JSONObject().put("port", PORT).put("activeVersion", activeVersion)
                    .put("script", "app.js").put("status", "ready");
            respond(socket, 200, "application/json", state.toString());
        } else if (path.equals("/api/script") && method.equals("GET")) {
            respond(socket, 200, "application/javascript; charset=utf-8", readCurrent());
        } else if (path.equals("/api/script") && (method.equals("PUT") || method.equals("POST"))) {
            String script = new String(body, StandardCharsets.UTF_8);
            validateSyntax(script);
            String version = saveVersion(script);
            Files.writeString(currentFile.toPath(), script, StandardCharsets.UTF_8);
            onScript.accept(script);
            respond(socket, 200, "application/json",
                    new JSONObject().put("ok", true).put("version", version).toString());
        } else if (path.equals("/api/versions") && method.equals("GET")) {
            respond(socket, 200, "application/json", listVersions().toString());
        } else if (path.startsWith("/api/rollback/") && method.equals("POST")) {
            String id = path.substring("/api/rollback/".length());
            if (!VERSION_ID.matcher(id).matches()) {
                respond(socket, 400, "application/json", new JSONObject().put("error", "invalid version id").toString());
                return;
            }
            File versionFile = new File(versionsDir, id + ".js");
            if (!versionFile.isFile()) {
                respond(socket, 404, "application/json", new JSONObject().put("error", "version not found").toString());
                return;
            }
            String script = Files.readString(versionFile.toPath(), StandardCharsets.UTF_8);
            validateSyntax(script);
            Files.writeString(currentFile.toPath(), script, StandardCharsets.UTF_8);
            activeVersion = id;
            persistActiveVersion();
            onScript.accept(script);
            respond(socket, 200, "application/json", new JSONObject().put("ok", true).put("version", id).toString());
        } else {
            respond(socket, 404, "application/json", new JSONObject().put("error", "not found").toString());
        }
    }

    private void validateSyntax(String script) {
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) {
            throw new IllegalArgumentException("Script exceeds 1 MiB");
        }
        // The desktop editor performs a JavaScript parser check with new Function()
        // before sending. The server keeps transport/storage validation independent
        // of a particular JavaScript engine.
    }

    private JSONArray listVersions() throws Exception {
        JSONArray result = new JSONArray();
        File[] files = versionsDir.listFiles((dir, name) -> name.endsWith(".js"));
        if (files == null) return result;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 3);
            result.put(new JSONObject().put("id", id).put("active", id.equals(activeVersion))
                    .put("bytes", file.length()).put("modified", file.lastModified()));
        }
        return result;
    }

    private String desktopEditor() {
        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Discord WebView · Script Studio</title><style>
:root{color-scheme:dark;font:14px ui-sans-serif,system-ui;background:#0b1020;color:#e5e7eb}
*{box-sizing:border-box}body{margin:0}header{padding:18px 24px;border-bottom:1px solid #293247;display:flex;justify-content:space-between;align-items:center}
main{max-width:1200px;margin:24px auto;padding:0 20px}.muted{color:#9ca3af}.pill{padding:5px 10px;border:1px solid #34425c;border-radius:20px}
.toolbar{display:flex;gap:10px;align-items:center;margin:16px 0}button{background:#5865f2;color:white;border:0;border-radius:8px;padding:9px 14px;cursor:pointer}button.secondary{background:#263248}textarea{width:100%;height:58vh;background:#080d18;color:#d1e7ff;border:1px solid #34425c;border-radius:10px;padding:16px;font:13px/1.55 ui-monospace,monospace;tab-size:2;resize:vertical}
.layout{display:grid;grid-template-columns:minmax(0,1fr) 280px;gap:18px}.panel{background:#121a2b;border:1px solid #293247;border-radius:12px;padding:14px}.versions{display:flex;flex-direction:column;gap:8px;max-height:58vh;overflow:auto}.version{padding:10px;border:1px solid #293247;border-radius:8px}.version small{display:block;color:#9ca3af;margin:5px 0}
#status{margin-left:auto;color:#9ca3af}@media(max-width:800px){.layout{grid-template-columns:1fr}}
</style></head><body><header><div><b>◈ Discord WebView · Script Studio</b><div class="muted">Single-file live runtime</div></div><span class="pill" id="active">Loading…</span></header>
<main><div class="toolbar"><span class="muted">Editing <b>app.js</b></span><button id="apply">Apply script</button><button class="secondary" id="reload">Reload</button><span id="status">Connecting…</span></div>
<div class="layout"><section><textarea id="editor" spellcheck="false"></textarea><p class="muted">The editor checks JavaScript syntax before sending. Every accepted update is versioned.</p></section>
<aside class="panel"><b>Version history</b><p class="muted">Select a version to roll back.</p><div class="versions" id="versions"></div></aside></div></main>
<script>
const $=id=>document.getElementById(id);let active='';
async function api(path,opts){const r=await fetch(path,opts);const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||r.statusText);return data}
async function refresh(){const s=await api('/api/state');active=s.activeVersion;$('active').textContent='Active · '+active;const vs=await api('/api/versions');$('versions').innerHTML='';vs.forEach(v=>{const el=document.createElement('div');el.className='version';const title=document.createElement('b');title.textContent=v.id+(v.active?' · active':'');const small=document.createElement('small');small.textContent=(v.bytes/1024).toFixed(1)+' KB · '+new Date(v.modified).toLocaleString();el.append(title,small);if(!v.active){const b=document.createElement('button');b.className='secondary';b.textContent='Rollback';b.onclick=async()=>{if(!confirm('Restore '+v.id+'?'))return;try{await api('/api/rollback/'+v.id,{method:'POST'});await loadScript();await refresh();$('status').textContent='Rolled back'}catch(e){$('status').textContent=e.message}};el.append(b)}$('versions').append(el)})}
async function loadScript(){$('editor').value=await (await fetch('/api/script')).text()}
$('apply').onclick=async()=>{try{$('status').textContent='Checking syntax…';new Function($('editor').value);$('status').textContent='Applying…';const r=await api('/api/script',{method:'PUT',headers:{'Content-Type':'application/javascript'},body:$('editor').value});$('status').textContent='Applied · '+r.version;await refresh()}catch(e){$('status').textContent='Error · '+e.message}};
$('reload').onclick=async()=>{await loadScript();await refresh();$('status').textContent='Reloaded'};
(async()=>{try{await loadScript();await refresh();$('status').textContent='Connected'}catch(e){$('status').textContent=e.message}})();
</script></body></html>""";
    }

    private void respond(Socket socket, int status, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" :
                status == 400 ? "Bad Request" : status == 404 ? "Not Found" :
                status == 413 ? "Payload Too Large" : "Internal Server Error";
        OutputStream out = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    @Override public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        clients.shutdownNow();
    }
}
