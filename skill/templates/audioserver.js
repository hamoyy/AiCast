// 一次性演示用：局域网静态音频服务（支持 Range，供电视 <audio> 拉流）
const http = require("http"), fs = require("fs"), path = require("path");
const ROOT = __dirname, PORT = 8770;
http.createServer((req, res) => {
  const f = path.join(ROOT, path.basename(decodeURIComponent(req.url.split("?")[0])));
  fs.stat(f, (err, st) => {
    if (err) { res.writeHead(404); return res.end("not found"); }
    const total = st.size, range = req.headers.range;
    const type = f.endsWith(".mp3") ? "audio/mpeg" : "application/octet-stream";
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      const start = m && m[1] ? +m[1] : 0;
      const end = m && m[2] ? +m[2] : total - 1;
      res.writeHead(206, { "Content-Range": `bytes ${start}-${end}/${total}`,
        "Accept-Ranges": "bytes", "Content-Length": end - start + 1,
        "Content-Type": type, "Access-Control-Allow-Origin": "*" });
      fs.createReadStream(f, { start, end }).pipe(res);
    } else {
      res.writeHead(200, { "Content-Length": total, "Accept-Ranges": "bytes",
        "Content-Type": type, "Access-Control-Allow-Origin": "*" });
      fs.createReadStream(f).pipe(res);
    }
  });
}).listen(PORT, "0.0.0.0", () => console.log("serving on " + PORT));
