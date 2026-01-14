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
    setEditForm(prev => ({ ...prev, [key]: value }));
  };

  return (
    <div className="bg-white dark:bg-slate-900 rounded-lg shadow-sm border border-gray-200 dark:border-slate-800 p-4 md:p-6 transition-colors duration-200">
      <div className="flex justify-between items-center mb-6">
        <h2 className="text-xl font-bold text-gray-800 dark:text-slate-100">{title}</h2>
        {editingIndex === -1 && (
            <button
                onClick={handleAddNew}
                className="bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium py-2 px-4 rounded-md transition-colors shadow-sm flex items-center gap-2"
            >
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-4 h-4">
                  <path d="M10.75 4.75a.75.75 0 00-1.5 0v4.5h-4.5a.75.75 0 000 1.5h4.5v4.5a.75.75 0 001.5 0v-4.5h4.5a.75.75 0 000-1.5h-4.5v-4.5z" />
                </svg>
                Add New
            </button>
        )}
      </div>

      {editingIndex === -1 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {items.map((item, idx) => (
                <div key={idx} className="bg-gray-50 dark:bg-slate-800 rounded-lg border border-gray-200 dark:border-slate-700 p-4 flex flex-col gap-3 hover:shadow-md transition-shadow">
                    <div className="flex items-start justify-between">
                         <div className="flex items-center gap-3 overflow-hidden">
                             {/* Attempt to find an image field first */}
                             {schema.find(f => f.type === 'image') && item[schema.find(f => f.type === 'image').key] ? (
                                 <img
                                     src={item[schema.find(f => f.type === 'image').key]}
                                     alt="Icon"
                                     className="w-10 h-10 rounded-lg object-cover bg-gray-200 dark:bg-slate-700 flex-shrink-0"
                                     onError={(e) => {e.target.style.display='none'}}
                                 />
                             ) : (
                                 <div className="w-10 h-10 rounded-lg bg-indigo-100 dark:bg-indigo-900/50 flex items-center justify-center text-indigo-600 dark:text-indigo-400 font-bold text-lg flex-shrink-0">
                                     {item[schema[0].key]?.charAt(0).toUpperCase() || '?'}
                                 </div>
                             )}
                             <div className="min-w-0">
                                 <h3 className="font-semibold text-gray-900 dark:text-slate-100 truncate text-sm sm:text-base">
                                     {item[schema[0].key]}
                                 </h3>
                                 {/* Second field usually version or subtitle */}
                                 {schema[1] && (
                                     <p className="text-xs text-gray-500 dark:text-slate-400 truncate">
                                         {item[schema[1].key]}
                                     </p>
                                 )}
                             </div>
                         </div>
                    </div>

                    <div className="mt-auto pt-3 border-t border-gray-200 dark:border-slate-700 flex justify-end gap-2">
                        <button
                            onClick={() => handleEdit(idx)}
                            className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 px-3 py-1.5 rounded transition-colors"
                        >
                            Edit
                        </button>
                        <button
                            onClick={() => handleDelete(idx)}
                            className="text-xs font-medium text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30 px-3 py-1.5 rounded transition-colors"
                        >
                            Delete
                        </button>
                    </div>
                </div>
            ))}

            {items.length === 0 && (
                <div className="col-span-full py-10 text-center text-gray-500 dark:text-slate-400">
                    No items found. Click "Add New" to create one.
                </div>
            )}
        </div>
      ) : (
        <div className="max-w-2xl mx-auto bg-gray-50 dark:bg-slate-800 p-6 rounded-lg border border-gray-200 dark:border-slate-700">
          <h3 className="text-lg font-medium mb-6 text-gray-900 dark:text-slate-100 border-b border-gray-200 dark:border-slate-700 pb-2">
              {editingIndex === items.length ? 'Add New Item' : 'Edit Item'}
          </h3>
          <div className="space-y-5">
            {schema.map(field => (
              <div key={field.key}>
                <label className="block text-sm font-medium text-gray-700 dark:text-slate-300 mb-1">{field.label}</label>
                <input
                  type="text"
                  value={editForm[field.key] || ''}
                  onChange={(e) => handleChange(field.key, e.target.value)}
                  className="w-full rounded-md border-gray-300 dark:border-slate-600 bg-white dark:bg-slate-900 text-gray-900 dark:text-slate-100 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm p-2.5 border"
                  placeholder={`Enter ${field.label}`}
                />
              </div>
            ))}
            <div className="flex justify-end gap-3 mt-8">
              <button
                  onClick={handleCancel}
                  className="px-4 py-2 border border-gray-300 dark:border-slate-600 rounded-md text-sm font-medium text-gray-700 dark:text-slate-300 hover:bg-gray-50 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800"
              >
                  Cancel
              </button>
              <button
                  onClick={handleSaveItem}
                  className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-md text-sm font-medium shadow-sm transition-colors"
              >
                  Save Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Editor;
