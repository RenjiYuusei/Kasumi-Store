import React, { useState, useEffect } from 'react';
import { getFileContent, updateFileContent } from './github';
import Editor from './components/Editor';

const APPS_SCHEMA = [
  { key: 'name', label: 'Name' },
  { key: 'versionName', label: 'Version' },
  { key: 'url', label: 'URL' },
  { key: 'iconUrl', label: 'Icon', type: 'image' },
];

const SCRIPTS_SCHEMA = [
  { key: 'name', label: 'Name' },
  { key: 'gameName', label: 'Game Name' },
  { key: 'url', label: 'URL' },
];

function App() {
  const [token, setToken] = useState(localStorage.getItem('gh_token') || '');
  const [repoDetails, setRepoDetails] = useState({ owner: '', repo: '' });
  const [activeTab, setActiveTab] = useState('apps');
  const [data, setData] = useState(null);
  const [sha, setSha] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState('');

  // Auto-detect repo details from current URL if hosted on GitHub Pages
  useEffect(() => {
    const hostname = window.location.hostname;
    if (hostname.includes('github.io')) {
      const parts = hostname.split('.');
      const owner = parts[0];
      const pathParts = window.location.pathname.split('/').filter(p => p);
      const repo = pathParts[0];
      if (owner && repo) {
        setRepoDetails({ owner, repo });
      }
    } else {
        // Default or Localhost fallback - Prompt user or try to guess?
        // Let's allow manual input if we can't guess.
    }
  }, []);

  const fetchData = async () => {
    if (!token || !repoDetails.owner || !repoDetails.repo) {
      setError("Please provide Token, Owner, and Repo name.");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const path = activeTab === 'apps' ? 'source/apps.json' : 'source/scripts.json';
      const result = await getFileContent(token, repoDetails.owner, repoDetails.repo, path);
      setData(result.content);
      setSha(result.sha);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (token && repoDetails.owner && repoDetails.repo) {
      fetchData();
    }
  }, [activeTab, token]); // Refetch when tab changes or token is set

  const handleSaveToken = (val) => {
    setToken(val);
    localStorage.setItem('gh_token', val);
  };

  const handleSaveChanges = async (newData) => {
    setLoading(true);
    setSuccessMsg('');
    try {
      const path = activeTab === 'apps' ? 'source/apps.json' : 'source/scripts.json';
      await updateFileContent(
        token,
        repoDetails.owner,
        repoDetails.repo,
        path,
        newData,
        sha,
        `Update ${activeTab === 'apps' ? 'apps.json' : 'scripts.json'} via Web Editor`
      );
      setSuccessMsg('Saved successfully!');
      // Refetch to get new SHA
      await fetchData();
    } catch (err) {
      setError("Failed to save: " + err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-100 p-8">
      <div className="max-w-6xl mx-auto">
        <header className="mb-8 flex justify-between items-center">
          <h1 className="text-3xl font-bold text-gray-800">Kasumi Store Editor</h1>
          <div className="flex gap-4">
             <input
               type="text"
               placeholder="Owner"
               className="border p-2 rounded"
               value={repoDetails.owner}
               onChange={e => setRepoDetails({...repoDetails, owner: e.target.value})}
             />
             <input
               type="text"
               placeholder="Repo"
               className="border p-2 rounded"
               value={repoDetails.repo}
               onChange={e => setRepoDetails({...repoDetails, repo: e.target.value})}
             />
             <input
               type="password"
               placeholder="GitHub Personal Access Token"
               className="border p-2 rounded w-64"
               value={token}
               onChange={e => handleSaveToken(e.target.value)}
             />
          </div>
        </header>

        <div className="mb-6">
           <div className="border-b border-gray-200">
              <nav className="-mb-px flex space-x-8">
                <button
                  onClick={() => setActiveTab('apps')}
                  className={`${activeTab === 'apps' ? 'border-indigo-500 text-indigo-600' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'} whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm`}
                >
                  Apps
                </button>
                <button
                  onClick={() => setActiveTab('scripts')}
                  className={`${activeTab === 'scripts' ? 'border-indigo-500 text-indigo-600' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'} whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm`}
                >
                  Scripts
                </button>
              </nav>
            </div>
        </div>

        {error && (
          <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded relative mb-4" role="alert">
            <span className="block sm:inline">{error}</span>
          </div>
        )}

        {successMsg && (
            <div className="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded relative mb-4" role="alert">
                <span className="block sm:inline">{successMsg}</span>
            </div>
        )}

        {loading ? (
            <div className="text-center py-10">Loading...</div>
        ) : (
            data && (
                <Editor
                  data={data}
                  onSave={handleSaveChanges}
                  schema={activeTab === 'apps' ? APPS_SCHEMA : SCRIPTS_SCHEMA}
                  title={activeTab === 'apps' ? 'Applications List' : 'Scripts List'}
                />
            )
        )}
      </div>
    </div>
  )
}

export default App
