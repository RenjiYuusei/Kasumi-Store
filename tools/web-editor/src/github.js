// Minimal GitHub Contents API client.
//
// This used to pull in the whole @octokit/rest package for exactly two calls,
// which dominated the bundle of an otherwise tiny static page. Two fetches do
// the same job with no dependency.

const API_ROOT = 'https://api.github.com';

/** Thrown when the file changed on GitHub since it was loaded here. */
export class ConflictError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConflictError';
  }
}

function encodeUtf8Base64(str) {
  const bytes = new TextEncoder().encode(str);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

function decodeUtf8Base64(b64) {
  // The API wraps base64 at 60 characters; atob rejects the newlines.
  const binary = atob(b64.replace(/\s/g, ''));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function headers(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
}

async function describeError(response) {
  let detail = '';
  try {
    const body = await response.json();
    detail = body && body.message ? body.message : '';
  } catch {
    // Non-JSON error body; the status alone will have to do.
  }
  const rateLimited =
    response.status === 403 && response.headers.get('x-ratelimit-remaining') === '0';
  if (rateLimited) return 'GitHub API rate limit reached. Try again later.';
  if (response.status === 401) return 'Invalid or expired token.';
  if (response.status === 404) return 'File or repository not found — check owner, repo and token scope.';
  return detail ? `${detail} (HTTP ${response.status})` : `HTTP ${response.status}`;
}

function contentsUrl(owner, repo, path) {
  const segments = path.split('/').map(encodeURIComponent).join('/');
  return `${API_ROOT}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/contents/${segments}`;
}

export const getFileContent = async (token, owner, repo, path) => {
  const response = await fetch(`${contentsUrl(owner, repo, path)}?ref=HEAD`, {
    headers: headers(token),
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(await describeError(response));

  const data = await response.json();
  return { content: JSON.parse(decodeUtf8Base64(data.content)), sha: data.sha };
};

export const updateFileContent = async (token, owner, repo, path, content, sha, message) => {
  const response = await fetch(contentsUrl(owner, repo, path), {
    method: 'PUT',
    headers: { ...headers(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message,
      content: encodeUtf8Base64(`${JSON.stringify(content, null, 2)}\n`),
      sha,
    }),
  });

  // The three scheduled workflows rewrite apps.json every 12 hours, so an
  // editor tab left open easily holds a stale sha. Surface that as its own
  // error type so the UI can tell the user to reload instead of showing a raw
  // "409".
  if (response.status === 409 || response.status === 422) {
    throw new ConflictError(
      'apps.json changed on GitHub since it was loaded here. Reload before saving, or your edit would overwrite that change.',
    );
  }
  if (!response.ok) throw new Error(await describeError(response));
  return response.json();
};
