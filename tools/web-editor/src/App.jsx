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
    <div className="min-h-screen bg-gray-50 dark:bg-slate-950 text-gray-900 dark:text-slate-100 p-4 md:p-8 transition-colors duration-200">
      <div className="max-w-7xl mx-auto">
        {/* Header Section */}
        <header className="mb-8 flex flex-col md:flex-row justify-between items-center gap-4">
          <div className="flex items-center gap-4 w-full md:w-auto justify-between">
            <h1 className="text-2xl md:text-3xl font-bold bg-gradient-to-r from-indigo-500 to-purple-600 bg-clip-text text-transparent">
              Kasumi Store
            </h1>
            <button
              onClick={toggleTheme}
              className="p-2 rounded-full bg-gray-200 dark:bg-slate-800 text-gray-800 dark:text-yellow-400 hover:bg-gray-300 dark:hover:bg-slate-700 transition-colors"
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

          <div className="flex flex-col sm:flex-row gap-3 w-full md:w-auto">
             <div className="flex gap-2">
               <input
                 type="text"
                 placeholder="Owner"
                 className="flex-1 bg-white dark:bg-slate-800 border border-gray-300 dark:border-slate-700 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                 value={repoDetails.owner}
                 onChange={e => setRepoDetails({...repoDetails, owner: e.target.value})}
               />
               <input
                 type="text"
                 placeholder="Repo"
                 className="flex-1 bg-white dark:bg-slate-800 border border-gray-300 dark:border-slate-700 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                 value={repoDetails.repo}
                 onChange={e => setRepoDetails({...repoDetails, repo: e.target.value})}
               />
             </div>
             <div className="relative">
               <input
                 type={showToken ? "text" : "password"}
                 placeholder="GitHub Personal Access Token"
                 className="w-full bg-white dark:bg-slate-800 border border-gray-300 dark:border-slate-700 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 pr-10"
                 value={token}
                 onChange={e => handleSaveToken(e.target.value)}
               />
               <button
                  type="button"
                  onClick={() => setShowToken(!showToken)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-500 dark:text-slate-400 hover:text-gray-700 dark:hover:text-slate-200"
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
        </header>

        {/* Tabs */}
        <div className="mb-6">
           <div className="border-b border-gray-200 dark:border-slate-700">
              <nav className="-mb-px flex space-x-8" aria-label="Tabs">
                <button
                  onClick={() => setActiveTab('apps')}
                  className={`${activeTab === 'apps'
                    ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                    : 'border-transparent text-gray-500 dark:text-slate-400 hover:text-gray-700 dark:hover:text-slate-300 hover:border-gray-300'}
                    whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors`}
                >
                  Apps
                </button>
                <button
                  onClick={() => setActiveTab('scripts')}
                  className={`${activeTab === 'scripts'
                    ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                    : 'border-transparent text-gray-500 dark:text-slate-400 hover:text-gray-700 dark:hover:text-slate-300 hover:border-gray-300'}
                    whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm transition-colors`}
                >
                  Scripts
                </button>
              </nav>
            </div>
        </div>

        {/* Alerts */}
        {error && (
          <div className="bg-red-100 dark:bg-red-900/30 border border-red-400 dark:border-red-800 text-red-700 dark:text-red-200 px-4 py-3 rounded relative mb-4 text-sm" role="alert">
            <span className="block sm:inline">{error}</span>
          </div>
        )}

        {successMsg && (
            <div className="bg-green-100 dark:bg-green-900/30 border border-green-400 dark:border-green-800 text-green-700 dark:text-green-200 px-4 py-3 rounded relative mb-4 text-sm" role="alert">
                <span className="block sm:inline">{successMsg}</span>
            </div>
        )}

        {/* Content */}
        {loading ? (
            <div className="flex justify-center items-center py-20">
               <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600 dark:border-indigo-400"></div>
            </div>
        ) : (
            data && (
                <Editor
                  data={data}
                  onSave={handleSaveChanges}
                  schema={activeTab === 'apps' ? APPS_SCHEMA : SCRIPTS_SCHEMA}
                  title={activeTab === 'apps' ? 'Applications' : 'Scripts'}
                />
            )
        )}
      </div>
    </div>
  )
}

export default App
