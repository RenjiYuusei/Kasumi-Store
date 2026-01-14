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
    <div className="p-4 bg-white rounded shadow">
      <h2 className="text-xl font-bold mb-4">{title}</h2>

      {editingIndex === -1 ? (
        <>
          <button
            onClick={handleAddNew}
            className="mb-4 bg-green-500 text-white px-4 py-2 rounded hover:bg-green-600"
          >
            Add New Item
          </button>
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {schema.map(field => (
                    <th key={field.key} className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      {field.label}
                    </th>
                  ))}
                  <th className="px-6 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {items.map((item, idx) => (
                  <tr key={idx}>
                    {schema.map(field => (
                      <td key={field.key} className="px-6 py-4 whitespace-nowrap text-sm text-gray-900 max-w-xs truncate">
                        {field.type === 'image' ? (
                          <img src={item[field.key]} alt="icon" className="h-8 w-8 object-cover rounded" />
                        ) : (
                          item[field.key]
                        )}
                      </td>
                    ))}
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                      <button onClick={() => handleEdit(idx)} className="text-indigo-600 hover:text-indigo-900 mr-4">Edit</button>
                      <button onClick={() => handleDelete(idx)} className="text-red-600 hover:text-red-900">Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : (
        <div className="max-w-lg">
          <h3 className="text-lg font-medium mb-4">{editingIndex === items.length ? 'Add New' : 'Edit Item'}</h3>
          <div className="space-y-4">
            {schema.map(field => (
              <div key={field.key}>
                <label className="block text-sm font-medium text-gray-700">{field.label}</label>
                <input
                  type="text"
                  value={editForm[field.key] || ''}
                  onChange={(e) => handleChange(field.key, e.target.value)}
                  className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-300 focus:ring focus:ring-indigo-200 focus:ring-opacity-50 border p-2"
                />
              </div>
            ))}
            <div className="flex justify-end space-x-3 mt-4">
              <button onClick={handleCancel} className="px-4 py-2 border border-gray-300 rounded-md text-sm font-medium text-gray-700 hover:bg-gray-50">Cancel</button>
              <button onClick={handleSaveItem} className="px-4 py-2 bg-indigo-600 border border-transparent rounded-md text-sm font-medium text-white hover:bg-indigo-700">Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Editor;
