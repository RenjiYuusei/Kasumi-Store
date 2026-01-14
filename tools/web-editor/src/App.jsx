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
  const [theme, setTheme] = useState(localStorage.getItem('theme') || 'dark');
  const [showToken, setShowToken] = useState(false);
  const [showConfig, setShowConfig] = useState(false);

  // Apply theme
  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
    localStorage.setItem('theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setTheme(prev => prev === 'dark' ? 'light' : 'dark');
  };

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
    }
  }, []);

  const fetchData = async () => {
    if (!token || !repoDetails.owner || !repoDetails.repo) {
      // Don't error immediately on load if auto-detect hasn't happened or config isn't set
      // just wait for user input
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
      setData(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // Check if we have enough info to fetch
    if (token && repoDetails.owner && repoDetails.repo) {
        fetchData();
    } else {
        // If missing info, ensure config is open so user sees they need to enter it
        if (!repoDetails.owner && !repoDetails.repo) {
            setShowConfig(true);
        }
    }
  }, [activeTab, token, repoDetails]); // Added repoDetails to fix the bug where it wouldn't load on initial detection

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
    <div className="min-h-screen bg-gray-100 dark:bg-slate-950 text-gray-900 dark:text-slate-100 transition-colors duration-200 font-sans">

      {/* Navbar */}
      <nav className="bg-white dark:bg-slate-900 border-b border-gray-200 dark:border-slate-800 sticky top-0 z-50 backdrop-blur-sm bg-opacity-80 dark:bg-opacity-80">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
              <div className="flex items-center justify-between h-16">
                  <div className="flex-shrink-0 flex items-center gap-3">
                      <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white font-bold">K</div>
                      <h1 className="text-xl font-bold bg-gradient-to-r from-indigo-600 to-purple-600 dark:from-indigo-400 dark:to-purple-400 bg-clip-text text-transparent">
                        Kasumi Store
                      </h1>
                  </div>
                  <div className="flex items-center gap-3">
                      <button
                        onClick={() => setShowConfig(!showConfig)}
                        className={`p-2 rounded-lg transition-colors ${showConfig ? 'bg-indigo-50 dark:bg-slate-800 text-indigo-600 dark:text-indigo-400' : 'text-gray-500 dark:text-slate-400 hover:bg-gray-100 dark:hover:bg-slate-800'}`}
                        title="Configuration"
                      >
                         <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-5 h-5">
                           <path strokeLinecap="round" strokeLinejoin="round" d="M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.174.918c.2.056.397.126.588.209l.865-.395a.965.965 0 0 1 1.095.145l.773.773a.966.966 0 0 1 .145 1.095l-.395.865c.083.19.153.388.209.588l.918.174c.541.09.94.56.94 1.11v1.093c0 .55-.398 1.02-.94 1.11l-.918.174c-.056.2-.126.397-.209.588l.395.865a.966.966 0 0 1-.145 1.095l-.773.773a.965.965 0 0 1-1.095.145l-.865-.395a7.35 7.35 0 0 1-.588.209l-.174.918c-.09.542-.56.94-1.11.94h-1.093c-.55 0-1.02-.398-1.11-.94l-.174-.918a7.39 7.39 0 0 1-.588-.209l-.865.395a.965.965 0 0 1-1.095-.145l-.773-.773a.965.965 0 0 1-.145-1.095l.395-.865a7.352 7.352 0 0 1-.209-.588l-.918-.174a.962.962 0 0 1-.94-1.11V10.5c0-.55.398-1.02.94-1.11l.918-.174a7.354 7.354 0 0 1 .209-.588l-.395-.865a.965.965 0 0 1 .145-1.095l.773-.773a.965.965 0 0 1 1.095-.145l.865.395c.19-.083.388-.153.588-.209l.174-.918ZM12 6.75a5.25 5.25 0 1 0 0 10.5 5.25 5.25 0 0 0 0-10.5Z" />
                         </svg>
                      </button>
                      <button
                        onClick={toggleTheme}
                        className="p-2 rounded-lg bg-gray-100 dark:bg-slate-800 text-gray-500 dark:text-yellow-400 hover:bg-gray-200 dark:hover:bg-slate-700 transition-colors"
                        title="Toggle Theme"
                      >
                        {theme === 'dark' ? (
                          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-5 h-5">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 3v2.25m6.364.386-1.591 1.591M21 12h-2.25m-.386 6.364-1.591-1.591M12 18.75V21m-4.773-4.227-1.591 1.591M5.25 12H3m4.227-4.773L5.636 5.636M15.75 12a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0Z" />
                          </svg>
                        ) : (
                          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-5 h-5">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M21.752 15.002A9.72 9.72 0 0 1 18 15.75c-5.385 0-9.75-4.365-9.75-9.75 0-1.33.266-2.597.748-3.752A9.753 9.753 0 0 0 3 11.25C3 16.635 7.365 21 12.75 21a9.753 9.753 0 0 0 9.002-5.998Z" />
                          </svg>
                        )}
                      </button>
                  </div>
              </div>
          </div>
      </nav>

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">

        {/* Configuration Panel */}
        {showConfig && (
            <div className="mb-8 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-gray-200 dark:border-slate-800 p-6 animate-in slide-in-from-top-2 duration-200">
                <h2 className="text-lg font-semibold mb-4 text-gray-800 dark:text-slate-100">Repository Settings</h2>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                    <div>
                        <label className="block text-xs font-medium text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Owner</label>
                        <input
                            type="text"
                            placeholder="GitHub Username"
                            className="w-full bg-gray-50 dark:bg-slate-950 border border-gray-300 dark:border-slate-700 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-shadow"
                            value={repoDetails.owner}
                            onChange={e => setRepoDetails({...repoDetails, owner: e.target.value})}
                        />
                    </div>
                    <div>
                        <label className="block text-xs font-medium text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Repo</label>
                        <input
                            type="text"
                            placeholder="Repository Name"
                            className="w-full bg-gray-50 dark:bg-slate-950 border border-gray-300 dark:border-slate-700 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-shadow"
                            value={repoDetails.repo}
                            onChange={e => setRepoDetails({...repoDetails, repo: e.target.value})}
                        />
                    </div>
                    <div className="relative">
                        <label className="block text-xs font-medium text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Access Token</label>
                        <input
                            type={showToken ? "text" : "password"}
                            placeholder="ghp_..."
                            className="w-full bg-gray-50 dark:bg-slate-950 border border-gray-300 dark:border-slate-700 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-shadow pr-10"
                            value={token}
                            onChange={e => handleSaveToken(e.target.value)}
                        />
                        <button
                            type="button"
                            onClick={() => setShowToken(!showToken)}
                            className="absolute right-3 top-[1.85rem] text-gray-500 dark:text-slate-400 hover:text-gray-700 dark:hover:text-slate-200"
                        >
                            {showToken ? (
                                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                                    <path d="M10 12.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5z" />
                                    <path fillRule="evenodd" d="M.664 10.59a1.651 1.651 0 010-1.186A10.004 10.004 0 0110 3c4.257 0 7.893 2.66 9.336 6.41.147.381.146.804 0 1.186A10.004 10.004 0 0110 17c-4.257 0-7.893-2.66-9.336-6.41zM14 10a4 4 0 11-8 0 4 4 0 018 0z" clipRule="evenodd" />
                                </svg>
                            ) : (
                                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                                    <path fillRule="evenodd" d="M3.28 2.22a.75.75 0 00-1.06 1.06l14.5 14.5a.75.75 0 101.06-1.06l-1.745-1.745a10.029 10.029 0 003.3-4.38 1.651 1.651 0 000-1.185A10.004 10.004 0 009.999 3a9.956 9.956 0 00-4.744 1.194L3.28 2.22zM7.752 6.69l1.092 1.092a2.5 2.5 0 013.374 3.373l1.481 1.481A4 4 0 007.75 6.69zM4.22 8.948a5.765 5.765 0 00.684 1.848l3.964 3.964a5.75 5.75 0 008.162-8.162L4.22 8.948z" clipRule="evenodd" />
                                </svg>
                            )}
                        </button>
                    </div>
                </div>
            </div>
        )}

        {/* Tab Navigation */}
        <div className="flex justify-center mb-8">
            <div className="bg-white dark:bg-slate-900 p-1.5 rounded-xl shadow-sm border border-gray-200 dark:border-slate-800 inline-flex gap-1">
                <button
                    onClick={() => setActiveTab('apps')}
                    className={`px-6 py-2.5 rounded-lg text-sm font-medium transition-all duration-200 ${
                        activeTab === 'apps'
                        ? 'bg-indigo-600 text-white shadow-md'
                        : 'text-gray-500 dark:text-slate-400 hover:bg-gray-100 dark:hover:bg-slate-800'
                    }`}
                >
                    Applications
                </button>
                <button
                    onClick={() => setActiveTab('scripts')}
                    className={`px-6 py-2.5 rounded-lg text-sm font-medium transition-all duration-200 ${
                        activeTab === 'scripts'
                        ? 'bg-indigo-600 text-white shadow-md'
                        : 'text-gray-500 dark:text-slate-400 hover:bg-gray-100 dark:hover:bg-slate-800'
                    }`}
                >
                    Scripts
                </button>
            </div>
        </div>

        {/* Alerts */}
        {error && (
            <div className="max-w-4xl mx-auto mb-6 bg-red-50 dark:bg-red-900/20 border-l-4 border-red-500 p-4 rounded-r shadow-sm">
                <div className="flex">
                    <div className="flex-shrink-0">
                        <svg className="h-5 w-5 text-red-400" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor">
                            <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                        </svg>
                    </div>
                    <div className="ml-3">
                        <p className="text-sm text-red-700 dark:text-red-300">{error}</p>
                    </div>
                </div>
            </div>
        )}

        {successMsg && (
            <div className="fixed bottom-4 right-4 z-50 bg-green-500 text-white px-6 py-3 rounded-lg shadow-lg animate-in slide-in-from-bottom-5 duration-300 flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor" className="w-5 h-5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
                </svg>
                {successMsg}
            </div>
        )}

        {/* Main Content Area */}
        <div className="transition-all duration-300 ease-in-out">
            {loading ? (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6 animate-pulse">
                    {[...Array(8)].map((_, i) => (
                        <div key={i} className="bg-white dark:bg-slate-900 h-40 rounded-xl border border-gray-200 dark:border-slate-800 p-4 flex flex-col gap-4">
                            <div className="flex gap-4">
                                <div className="w-12 h-12 bg-gray-200 dark:bg-slate-800 rounded-lg"></div>
                                <div className="flex-1 space-y-2 py-1">
                                    <div className="h-4 bg-gray-200 dark:bg-slate-800 rounded w-3/4"></div>
                                    <div className="h-3 bg-gray-200 dark:bg-slate-800 rounded w-1/2"></div>
                                </div>
                            </div>
                            <div className="mt-auto flex justify-end gap-2">
                                <div className="h-8 w-16 bg-gray-200 dark:bg-slate-800 rounded"></div>
                                <div className="h-8 w-16 bg-gray-200 dark:bg-slate-800 rounded"></div>
                            </div>
                        </div>
                    ))}
                </div>
            ) : (
                data ? (
                    <Editor
                      data={data}
                      onSave={handleSaveChanges}
                      schema={activeTab === 'apps' ? APPS_SCHEMA : SCRIPTS_SCHEMA}
                      title={activeTab === 'apps' ? 'Applications' : 'Scripts'}
                    />
                ) : (
                    <div className="text-center py-20 bg-white dark:bg-slate-900 rounded-3xl border border-dashed border-gray-300 dark:border-slate-700">
                        <div className="mx-auto w-16 h-16 bg-gray-100 dark:bg-slate-800 rounded-full flex items-center justify-center text-gray-400 dark:text-slate-500 mb-4">
                           <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-8 h-8">
                             <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z" />
                           </svg>
                        </div>
                        <h3 className="text-lg font-medium text-gray-900 dark:text-slate-200 mb-1">No Data Loaded</h3>
                        <p className="text-gray-500 dark:text-slate-400 text-sm max-w-md mx-auto">
                            Connect to a GitHub repository to start editing your data.
                            {!token && " You might need to provide a Personal Access Token."}
                        </p>
                        {!token && (
                            <button
                                onClick={() => setShowConfig(true)}
                                className="mt-6 px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors text-sm font-medium"
                            >
                                Configure Connection
                            </button>
                        )}
                    </div>
                )
            )}
        </div>
      </div>
    </div>
  )
}

export default App
