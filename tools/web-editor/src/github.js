import { Octokit } from "@octokit/rest";

// Helper to handle UTF-8 strings in Base64
function toBase64(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function fromBase64(str) {
  return decodeURIComponent(escape(atob(str)));
}

export const getFileContent = async (token, owner, repo, path) => {
  const octokit = new Octokit({ auth: token });
  try {
    const response = await octokit.repos.getContent({
      owner,
      repo,
      path,
    });
    // Decode base64 content with UTF-8 support
    const content = fromBase64(response.data.content);
    return { content: JSON.parse(content), sha: response.data.sha };
  } catch (error) {
    console.error("Error fetching file:", error);
    throw error;
  }
};

export const updateFileContent = async (token, owner, repo, path, content, sha, message) => {
  const octokit = new Octokit({ auth: token });
  try {
    const contentString = JSON.stringify(content, null, 2);
    const contentBase64 = toBase64(contentString);
    await octokit.repos.createOrUpdateFileContents({
      owner,
      repo,
      path,
      message,
      content: contentBase64,
      sha,
    });
  } catch (error) {
    console.error("Error updating file:", error);
    throw error;
  }
};
