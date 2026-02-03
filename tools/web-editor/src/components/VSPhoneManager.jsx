import React, { useState } from 'react';
import SparkMD5 from 'spark-md5';

const SERVICES = {
  vsphone: {
    base: 'https://api.vsphone.com/vsphone/api',
    origin: 'https://cloud.vsphone.com',
    appversion: '2001701'
  }
};

const VSPhoneManager = () => {
  const [username, setUsername] = useState(localStorage.getItem('vs_user') || '');
  const [password, setPassword] = useState('');
  const [token, setToken] = useState(localStorage.getItem('vs_token') || '');
  const [userId, setUserId] = useState(localStorage.getItem('vs_uid') || '');
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState({ msg: '', type: '' });

  const updateStatus = (msg, type = 'info') => {
    setStatus({ msg, type });
    if (type === 'success') setTimeout(() => setStatus({ msg: '', type: '' }), 5000);
  };

  const handleLogin = async () => {
    if (!username || !password) return updateStatus('Please enter username and password', 'error');
    setLoading(true);
    updateStatus('Logging in...', 'info');

    try {
      const passHash = SparkMD5.hash(password);
      const res = await fetch(`${SERVICES.vsphone.base}/user/login`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'appversion': SERVICES.vsphone.appversion,
          'requestsource': 'wechat-miniapp'
        },
        body: JSON.stringify({
          mobilePhone: username,
          loginType: 1,
          channel: 'web',
          password: passHash
        })
      });
      const data = await res.json();
      if (data.code === 200 && data.data) {
        setToken(data.data.token);
        setUserId(data.data.userId);
        localStorage.setItem('vs_user', username);
        localStorage.setItem('vs_token', data.data.token);
        localStorage.setItem('vs_uid', data.data.userId);
        updateStatus('Login successful!', 'success');
        fetchFiles(data.data.token, data.data.userId);
      } else {
        updateStatus(data.msg || 'Login failed', 'error');
      }
    } catch (e) {
      updateStatus('Login error: ' + e.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  const fetchFiles = async (currentToken = token, currentUid = userId) => {
    if (!currentToken) return;
    setLoading(true);
    try {
      const res = await fetch(`${SERVICES.vsphone.base}/cloudFile/selectFilesByUserId?operType=2`, {
        method: 'POST',
        headers: {
          'accept': 'application/json, text/plain, */*',
          'appversion': SERVICES.vsphone.appversion,
          'clienttype': 'web',
          'content-type': 'application/json',
          'origin': SERVICES.vsphone.origin,
          'referer': SERVICES.vsphone.origin + '/',
          'requestsource': 'wechat-miniapp',
          'suppliertype': '0',
          'token': currentToken,
          'userid': String(currentUid)
        },
        body: '{}'
      });
      const data = await res.json();
      if (data.code === 200 && data.data) {
        setFiles(data.data);
      } else {
        updateStatus('Failed to load files', 'error');
      }
    } catch (e) {
      updateStatus('Fetch error: ' + e.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text);
    updateStatus('Copied to clipboard!', 'success');
  };

  return (
    <div className="max-w-4xl mx-auto bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-gray-200 dark:border-slate-800 p-6">
      <h2 className="text-xl font-bold mb-6 text-gray-800 dark:text-slate-100 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6 text-indigo-500">
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 0 0 8.716-6.747M12 21a9.004 9.004 0 0 1-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 0 1 7.843 4.582M12 3a8.997 8.997 0 0 0-7.843 4.582m15.686 0A11.953 11.953 0 0 1 12 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0 1 21 12c0 .778-.099 1.533-.284 2.253m0 0A17.919 17.919 0 0 1 12 16.5c-3.162 0-6.133-.815-8.716-2.247m0 0A9.015 9.015 0 0 1 3 12c0-1.605.42-3.113 1.157-4.418" />
        </svg>
        VSPhone Cloud Manager
      </h2>

      {/* Login Section */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-8">
        <div>
          <label className="block text-xs font-medium text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Account</label>
          <input
            type="text"
            className="w-full bg-gray-50 dark:bg-slate-950 border border-gray-300 dark:border-slate-700 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            placeholder="Email/Phone"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="relative">
            <label className="block text-xs font-medium text-gray-500 dark:text-slate-400 uppercase tracking-wider mb-1">Password</label>
            <div className="flex gap-2">
                <input
                    type="password"
                    className="w-full bg-gray-50 dark:bg-slate-950 border border-gray-300 dark:border-slate-700 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    placeholder="Password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                />
                <button
                    onClick={handleLogin}
                    disabled={loading}
                    className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors text-sm font-medium whitespace-nowrap disabled:opacity-50"
                >
                    {loading ? '...' : 'Login'}
                </button>
            </div>
        </div>
      </div>

      {status.msg && (
        <div className={`mb-6 p-3 rounded-lg text-sm ${status.type === 'error' ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300' : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'} ${status.type === 'success' ? '!bg-green-100 !text-green-700 dark:!bg-green-900/30 dark:!text-green-300' : ''}`}>
            {status.msg}
        </div>
      )}

      {/* File List */}
      {token && (
        <div className="animate-in fade-in slide-in-from-bottom-4 duration-300">
            <div className="flex justify-between items-center mb-4">
                <h3 className="text-lg font-semibold text-gray-700 dark:text-slate-200">Cloud Files</h3>
                <button
                    onClick={() => fetchFiles()}
                    className="text-sm text-indigo-600 dark:text-indigo-400 hover:underline flex items-center gap-1"
                >
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-4 h-4">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 4.992 9.19-9.19M2.985 19.644v-4.992m0 0h4.992m-4.993 4.992 9.19-9.19M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
                    </svg>
                    Refresh
                </button>
            </div>

            <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-slate-800">
                <table className="w-full text-sm text-left">
                    <thead className="text-xs text-gray-500 dark:text-slate-400 uppercase bg-gray-50 dark:bg-slate-800">
                        <tr>
                            <th className="px-4 py-3">File Name</th>
                            <th className="px-4 py-3">Size</th>
                            <th className="px-4 py-3 text-right">Action</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200 dark:divide-slate-800">
                        {files.map((file) => (
                            <tr key={file.fileId} className="bg-white dark:bg-slate-900 hover:bg-gray-50 dark:hover:bg-slate-800/50 transition-colors">
                                <td className="px-4 py-3 font-medium text-gray-900 dark:text-slate-200 max-w-xs truncate" title={file.fileName}>
                                    {file.fileName}
                                </td>
                                <td className="px-4 py-3 text-gray-500 dark:text-slate-400">
                                    {(file.fileSize / 1024 / 1024).toFixed(2)} MB
                                </td>
                                <td className="px-4 py-3 text-right">
                                    <button
                                        onClick={() => copyToClipboard(file.downloadUrl)}
                                        className="text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 font-medium"
                                    >
                                        Copy Link
                                    </button>
                                </td>
                            </tr>
                        ))}
                        {files.length === 0 && !loading && (
                            <tr>
                                <td colSpan="3" className="px-4 py-8 text-center text-gray-500 dark:text-slate-500">
                                    No files found.
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
      )}
    </div>
  );
};

export default VSPhoneManager;
