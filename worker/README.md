# Kasumi-Store proxy worker

Cloudflare Worker đứng giữa app Android và GitHub. Worker giữ PAT, app
chỉ biết URL công khai của Worker. Mục tiêu: kể cả khi người dùng bật
HTTP Toolkit / Charles để bắt request, họ chỉ thấy URL Worker, không
thấy token GitHub và không tự dùng token đó để clone source app được.

```
App (APK)  ──HTTPS──▶  *.workers.dev  ──HTTPS──▶  api.github.com
                          │                          │
                          └── env.GITHUB_TOKEN ──────┘
```

## Whitelist

Worker chỉ phục vụ đúng 2 path:

| Path             | Maps to                |
| ---------------- | ---------------------- |
| `/apps.json`     | `source/apps.json`     |
| `/scripts.json`  | `source/scripts.json`  |

Các path khác trả 404. Không cho phép tham số `path` từ client để tránh
bị lợi dụng làm proxy đọc file tuỳ ý trong repo.

## Deploy lần đầu

> Cần Node 18+ và `wrangler` CLI (`npm i -g wrangler` hoặc dùng `npx`).

1. **Tạo fine-grained PAT mới** — KHÔNG dùng lại token cũ đã lộ:
   - https://github.com/settings/personal-access-tokens/new
   - Resource owner: chính bạn (hoặc tổ chức sở hữu repo).
   - Repository access: **Only select repositories** → `Kasumi-Store`.
   - Repository permissions: **Contents → Read-only**. (Không cần thêm gì.)
   - Expiration: 90 ngày (hoặc theo policy của bạn). Đặt nhắc rotate.
   - Copy token, KHÔNG paste vào chat / commit / log.

2. Login wrangler một lần:
   ```bash
   cd worker
   npm install
   npx wrangler login
   ```

3. Set secret (token sẽ được wrangler đẩy thẳng lên Cloudflare, không nằm
   trong repo):
   ```bash
   npx wrangler secret put GITHUB_TOKEN
   # paste token mới khi được hỏi
   ```

   Optional — set shared secret để chặn người ngoài hit worker URL công
   khai (app sẽ gửi kèm `X-Kasumi-Auth`):
   ```bash
   npx wrangler secret put SHARED_SECRET
   # nhập một chuỗi random đủ dài, ví dụ `openssl rand -hex 32`
   ```
   Nhớ thêm chuỗi đó vào `app/secrets.properties` (xem mục **App side**).

4. Deploy:
   ```bash
   npx wrangler deploy
   ```

   Output sẽ in URL dạng `https://kasumi-store-proxy.<account>.workers.dev`.
   Bạn có thể gắn custom domain trong Cloudflare dashboard nếu muốn.

5. Smoke test:
   ```bash
   curl -i https://kasumi-store-proxy.<account>.workers.dev/apps.json
   # 200 + JSON content nếu đúng
   curl -i https://kasumi-store-proxy.<account>.workers.dev/foo
   # 404
   ```

## Cấu hình

`wrangler.toml [vars]` (commit được, không phải secret):

| Var             | Default        | Ý nghĩa                                |
| --------------- | -------------- | -------------------------------------- |
| `GITHUB_OWNER`  | `RenjiYuusei`  | owner của repo nguồn                   |
| `GITHUB_REPO`   | `Kasumi-Store` | tên repo                               |
| `GITHUB_REF`    | `main`         | branch / tag để đọc                    |

Secrets (set bằng `wrangler secret put ...`, KHÔNG commit):

| Secret           | Bắt buộc | Ý nghĩa                                                  |
| ---------------- | -------- | -------------------------------------------------------- |
| `GITHUB_TOKEN`   | Có       | fine-grained PAT, Contents:Read trên repo whitelist      |
| `SHARED_SECRET`  | Không    | nếu set, app phải gửi header `X-Kasumi-Auth` đúng giá trị |

## App side

Sau khi deploy thành công, mở `app/secrets.properties` (gitignored
nếu bạn không muốn commit) và đổi:

```properties
appsUrl=https://kasumi-store-proxy.<your-account>.workers.dev/apps.json
scriptsUrl=https://kasumi-store-proxy.<your-account>.workers.dev/scripts.json
```

Build lại app:

```bash
./gradlew assembleRelease
```

Gradle task `:app:generateSecureStrings` sẽ tự encrypt URL Worker với
AES-256 key ngẫu nhiên (xoay mỗi build), nhúng vào APK dưới dạng
`SecureStrings`. Trong `MainActivity` không có URL plaintext, không có
token GitHub.

> Nếu có bật `SHARED_SECRET`, mở `OkHttpClient` đang dùng và thêm
> Interceptor gắn header `X-Kasumi-Auth` với giá trị decrypt từ một
> entry mới trong `secrets.properties` (`sharedSecret=...`). Mở issue
> nếu cần mình làm bước đó.

## Rotate token

```bash
cd worker
npx wrangler secret put GITHUB_TOKEN
# paste PAT mới
```

Không cần build lại app — app vẫn dùng URL Worker cũ.

## Threat model

- ✅ Static analysis APK: không thấy URL gốc GitHub, không thấy token.
- ✅ HTTP sniff (HTTP Toolkit / Charles / mitmproxy): chỉ thấy URL
  Worker, không thấy `Authorization: Bearer github_pat_…`.
- ✅ Người tò mò copy URL Worker chạy `curl`: nếu bật
  `SHARED_SECRET`, không qua được; nếu không, họ sẽ tải được JSON
  (giống như khi repo public). Token vẫn không lộ.
- ⚠️ Người reverse-engineer + đủ kiên nhẫn vẫn có thể trace runtime,
  hook OkHttp bằng Frida để đọc/sửa request. Worker không bảo vệ được
  khỏi tấn công ở mức này — không có client-side defense nào làm được.
- ⚠️ Worker tốn quota GitHub API: 5000 req/h cho fine-grained PAT.
  Cache 5 phút ở edge giúp giảm gọi đáng kể với nhiều client.
