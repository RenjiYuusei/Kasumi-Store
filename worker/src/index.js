/**
 * Kasumi-Store proxy Worker.
 *
 * Mục đích: app Android chỉ nói chuyện với Worker này, không chứa GitHub
 * PAT. Worker giữ PAT trong env (set bằng `wrangler secret put GITHUB_TOKEN`)
 * và đọc nội dung file từ GitHub Contents API rồi trả về JSON cho app.
 *
 *   App  ──HTTPS──▶  Worker (cf.workers.dev)  ──HTTPS──▶  api.github.com
 *                       │                                    │
 *                       └── env.GITHUB_TOKEN ────────────────┘
 *
 * Cấu hình runtime (env hoặc wrangler.toml [vars]):
 *   - GITHUB_OWNER   (vd: "RenjiYuusei")
 *   - GITHUB_REPO    (vd: "Kasumi-Store")
 *   - GITHUB_REF     (vd: "main", default "main")
 *   - GITHUB_TOKEN   (secret, fine-grained PAT, scope "Contents: Read")
 *   - SHARED_SECRET  (optional, secret) — nếu set, app phải gửi
 *                    `X-Kasumi-Auth: <SHARED_SECRET>` mới được phục vụ.
 *
 * Endpoint:
 *   GET /apps.json     → source/apps.json    của repo
 *   GET /scripts.json  → source/scripts.json của repo
 *   Bất kỳ path nào khác → 404 (whitelist).
 */

const ALLOWED_FILES = new Map([
  ["/apps.json", "source/apps.json"],
  ["/scripts.json", "source/scripts.json"],
]);

const CACHE_SECONDS = 300;

export default {
  /** @param {Request} request @param {Env} env */
  async fetch(request, env, ctx) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405 });
    }

    const url = new URL(request.url);
    const repoPath = ALLOWED_FILES.get(url.pathname);
    if (!repoPath) {
      return new Response("Not found", { status: 404 });
    }

    if (env.SHARED_SECRET) {
      const provided = request.headers.get("X-Kasumi-Auth") || "";
      if (!constantTimeEquals(provided, env.SHARED_SECRET)) {
        return new Response("Unauthorized", { status: 401 });
      }
    }

    if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) {
      return new Response("Worker misconfigured", { status: 500 });
    }

    const ref = env.GITHUB_REF || "main";
    const ghUrl =
      `https://api.github.com/repos/${encodeURIComponent(env.GITHUB_OWNER)}` +
      `/${encodeURIComponent(env.GITHUB_REPO)}/contents/${repoPath}` +
      `?ref=${encodeURIComponent(ref)}`;

    const upstream = await fetch(ghUrl, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github.raw",
        "User-Agent": "kasumi-store-proxy/1.0",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      cf: { cacheTtl: CACHE_SECONDS, cacheEverything: true },
    });

    if (!upstream.ok) {
      // Không leak chi tiết upstream; chỉ trả mã rút gọn.
      return new Response("Upstream error", { status: 502 });
    }

    const headers = new Headers({
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${CACHE_SECONDS}`,
      "X-Content-Type-Options": "nosniff",
    });
    return new Response(upstream.body, { status: 200, headers });
  },
};

/**
 * Constant-time string compare. Luôn duyệt qua đủ `Math.max(len(a), len(b))`
 * ký tự để không leak độ dài chuỗi qua thời gian phản hồi; chênh lệch độ
 * dài cũng được OR vào `diff` để mismatch độ dài bị phát hiện.
 *
 * @param {string} a
 * @param {string} b
 */
function constantTimeEquals(a, b) {
  const len = Math.max(a.length, b.length);
  let diff = a.length ^ b.length;
  for (let i = 0; i < len; i++) {
    const ca = i < a.length ? a.charCodeAt(i) : 0;
    const cb = i < b.length ? b.charCodeAt(i) : 0;
    diff |= ca ^ cb;
  }
  return diff === 0;
}
