import React, { useState } from 'react';

const Editor = ({ data, onSave, schema, title }) => {
  const [items, setItems] = useState(data);
  const [editingIndex, setEditingIndex] = useState(-1);
  const [editForm, setEditForm] = useState({});

  const handleDelete = (index) => {
    if (confirm('Are you sure you want to delete this item?')) {
      const newItems = items.filter((_, i) => i !== index);
      setItems(newItems);
      onSave(newItems);
    }
  };

  const handleEdit = (index) => {
    setEditingIndex(index);
    setEditForm({ ...items[index] });
  };

  const handleAddNew = () => {
    setEditingIndex(items.length);
    const emptyForm = {};
    schema.forEach(field => emptyForm[field.key] = '');
    setEditForm(emptyForm);
  };

  const handleCancel = () => {
    setEditingIndex(-1);
    setEditForm({});
  };

  const handleSaveItem = () => {
    const newItems = [...items];
    if (editingIndex === items.length) {
      newItems.push(editForm);
    } else {
      newItems[editingIndex] = editForm;
    }
    setItems(newItems);
    setEditingIndex(-1);
    onSave(newItems);
  };

  const handleChange = (key, value) => {
    let newValue = value;

    // Auto-resize Google Play icons
    const fieldDef = schema.find(f => f.key === key);
    if (fieldDef?.type === 'image' && typeof newValue === 'string') {
      if (newValue.includes('play-lh.googleusercontent.com')) {
        // Replace w<width>-h<height>[-rw] with w240-h480
        const regex = /=w\d+-h\d+(-rw)?$/;
        if (regex.test(newValue)) {
          newValue = newValue.replace(regex, '=w240-h480');
        }
      }
    }

    setEditForm(prev => ({ ...prev, [key]: newValue }));
  };

  // Helper to find the main label (usually name) and secondary (version/game)
  const getPrimaryLabel = (item) => item[schema[0].key];
  const getSecondaryLabel = (item) => schema[1] ? item[schema[1].key] : '';
  const getImageField = () => schema.find(f => f.type === 'image');

  return (
    <div className="bg-white dark:bg-slate-900 rounded-3xl shadow-sm border border-gray-200 dark:border-slate-800 p-6 md:p-8 transition-all duration-300">

      {/* Header with Add Button */}
      <div className="flex justify-between items-center mb-8">
        <div>
            <h2 className="text-2xl font-bold text-gray-900 dark:text-slate-100 tracking-tight">{title}</h2>
            <p className="text-sm text-gray-500 dark:text-slate-400 mt-1">
                {items.length} {items.length === 1 ? 'item' : 'items'} in total
            </p>
        </div>

        {editingIndex === -1 && (
            <button
                onClick={handleAddNew}
                className="group bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold py-2.5 px-5 rounded-full transition-all shadow-md hover:shadow-xl hover:-translate-y-0.5 flex items-center gap-2"
            >
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-5 h-5 transition-transform group-hover:rotate-90">
                  <path d="M10.75 4.75a.75.75 0 00-1.5 0v4.5h-4.5a.75.75 0 000 1.5h4.5v4.5a.75.75 0 001.5 0v-4.5h4.5a.75.75 0 000-1.5h-4.5v-4.5z" />
                </svg>
                Add New
            </button>
        )}
      </div>

      {editingIndex === -1 ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {items.map((item, idx) => (
                <div key={idx} className="group bg-white dark:bg-slate-800/50 rounded-2xl border border-gray-100 dark:border-slate-700/60 p-5 flex flex-col gap-4 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 relative overflow-hidden">
                    {/* Hover Gradient Overlay */}
                    <div className="absolute inset-0 bg-gradient-to-br from-indigo-50/50 to-purple-50/50 dark:from-indigo-900/10 dark:to-purple-900/10 opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none" />

                    <div className="flex items-start gap-4 relative z-10">
                         {/* Icon / Avatar */}
                         <div className="flex-shrink-0">
                             {getImageField() && item[getImageField().key] ? (
                                 <img
                                     src={item[getImageField().key]}
                                     alt="Icon"
                                     className="w-14 h-14 rounded-2xl object-cover bg-gray-100 dark:bg-slate-700 shadow-sm group-hover:scale-105 transition-transform duration-300"
                                     onError={(e) => {e.target.style.display='none'}}
                                 />
                             ) : (
                                 <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-indigo-100 to-purple-100 dark:from-indigo-900/40 dark:to-purple-900/40 flex items-center justify-center text-indigo-600 dark:text-indigo-400 font-bold text-xl shadow-inner">
                                     {getPrimaryLabel(item)?.charAt(0).toUpperCase() || '?'}
                                 </div>
                             )}
                         </div>

                         <div className="min-w-0 flex-1">
                             <h3 className="font-bold text-gray-900 dark:text-slate-100 truncate text-base mb-1" title={getPrimaryLabel(item)}>
                                 {getPrimaryLabel(item)}
                             </h3>
                             {getSecondaryLabel(item) && (
                                 <div className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-gray-100 dark:bg-slate-700 text-gray-600 dark:text-slate-300 truncate max-w-full">
                                     {getSecondaryLabel(item)}
                                 </div>
                             )}
                         </div>
                    </div>

                    <div className="mt-auto pt-4 border-t border-gray-100 dark:border-slate-700/60 flex justify-end gap-2 relative z-10 opacity-80 group-hover:opacity-100 transition-opacity">
                        <button
                            onClick={() => handleEdit(idx)}
                            className="p-2 rounded-lg text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
                            title="Edit"
                        >
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                              <path d="M5.433 13.917l1.262-3.155A4 4 0 017.58 9.42l6.92-6.918a2.121 2.121 0 013 3l-6.92 6.918c-.383.383-.84.685-1.343.886l-3.154 1.262a.5.5 0 01-.65-.65z" />
                              <path d="M3.5 5.75c0-.69.56-1.25 1.25-1.25H10A.75.75 0 0010 3H4.75A2.75 2.75 0 002 5.75v9.5A2.75 2.75 0 004.75 18h9.5A2.75 2.75 0 0017 15.25V10a.75.75 0 00-1.5 0v5.25c0 .69-.56 1.25-1.25 1.25h-9.5c-.69 0-1.25-.56-1.25-1.25v-9.5z" />
                            </svg>
                        </button>
                        <button
                            onClick={() => handleDelete(idx)}
                            className="p-2 rounded-lg text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors"
                            title="Delete"
                        >
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                              <path fillRule="evenodd" d="M8.75 1A2.75 2.75 0 006 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 10.23 1.482l.149-.022.841 10.518A2.75 2.75 0 007.596 19h4.807a2.75 2.75 0 002.742-2.53l.841-10.52.149.023a.75.75 0 00.23-1.482A41.03 41.03 0 0014 4.193V3.75A2.75 2.75 0 0011.25 1h-2.5zM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4zM8.58 7.72a.75.75 0 00-1.5.06l.3 7.5a.75.75 0 101.5-.06l-.3-7.5zm4.34.06a.75.75 0 10-1.5-.06l-.3 7.5a.75.75 0 101.5.06l.3-7.5z" clipRule="evenodd" />
                            </svg>
                        </button>
                    </div>
                </div>
            ))}

            {items.length === 0 && (
                <div className="col-span-full flex flex-col items-center justify-center py-16 text-gray-500 dark:text-slate-400 bg-gray-50 dark:bg-slate-800/30 rounded-3xl border border-dashed border-gray-200 dark:border-slate-700">
                    <div className="w-16 h-16 bg-gray-100 dark:bg-slate-800 rounded-full flex items-center justify-center mb-4 text-3xl">
                        ✨
                    </div>
                    <p className="text-lg font-medium">Nothing here yet</p>
                    <p className="text-sm mt-1">Click "Add New" to create your first item.</p>
                </div>
            )}
        </div>
      ) : (
        <div className="max-w-3xl mx-auto">
          <div className="flex items-center gap-2 mb-8 text-sm text-gray-500 dark:text-slate-400">
              <button onClick={handleCancel} className="hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors">
                  {title}
              </button>
              <span>/</span>
              <span className="text-gray-900 dark:text-slate-200 font-medium">
                  {editingIndex === items.length ? 'New Item' : `Editing ${getPrimaryLabel(items[editingIndex]) || 'Item'}`}
              </span>
          </div>

          <div className="bg-gray-50 dark:bg-slate-800/50 p-8 rounded-3xl border border-gray-200 dark:border-slate-700">
              <h3 className="text-2xl font-bold mb-8 text-gray-900 dark:text-slate-100">
                  {editingIndex === items.length ? 'Add New Item' : 'Edit Details'}
              </h3>

              <div className="space-y-6">
                {schema.map(field => (
                  <div key={field.key}>
                    <label className="block text-sm font-semibold text-gray-700 dark:text-slate-300 mb-2">{field.label}</label>
                    <input
                      type="text"
                      value={editForm[field.key] || ''}
                      onChange={(e) => handleChange(field.key, e.target.value)}
                      className="w-full rounded-xl border-gray-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-gray-900 dark:text-slate-100 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 text-base py-3 px-4 transition-shadow focus:ring-4 focus:ring-indigo-500/10"
                      placeholder={`Enter ${field.label}`}
                    />
                  </div>
                ))}

                <div className="flex justify-end gap-4 mt-10 pt-6 border-t border-gray-200 dark:border-slate-700/50">
                  <button
                      onClick={handleCancel}
                      className="px-6 py-2.5 rounded-xl border border-gray-300 dark:border-slate-600 text-gray-700 dark:text-slate-300 hover:bg-white dark:hover:bg-slate-700 transition-colors font-medium bg-transparent"
                  >
                      Cancel
                  </button>
                  <button
                      onClick={handleSaveItem}
                      className="px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl font-medium shadow-lg hover:shadow-indigo-500/25 transition-all transform hover:-translate-y-0.5"
                  >
                      Save Changes
                  </button>
                </div>
              </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Editor;
