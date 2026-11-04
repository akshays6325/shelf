// Serves local-feed/ on port 8080 so the emulator can use it as a test catalog
// (in Shelf settings, set the catalog URL to http://10.0.2.2:8080/index.json).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'local-feed');
const types = { '.json': 'application/json', '.apk': 'application/vnd.android.package-archive', '.png': 'image/png' };
http.createServer((req, res) => {
  const file = path.join(dir, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!file.startsWith(dir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Content-Length': fs.statSync(file).size });
  fs.createReadStream(file).pipe(res);
  console.log(req.method, req.url);
}).listen(8080, () => console.log(`Serving ${dir} on http://localhost:8080 (emulator: http://10.0.2.2:8080)`));
