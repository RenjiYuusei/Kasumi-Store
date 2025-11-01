package com.kasumi.tool

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ScriptAdapter(
    private val data: List<ScriptItem>,
    private val onDownload: (ScriptItem) -> Unit,
    private val onCopy: (ScriptItem) -> Unit,
    private val onDelete: (ScriptItem) -> Unit
) : RecyclerView.Adapter<ScriptAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txt_script_name)
        val gameName: TextView = v.findViewById(R.id.txt_game_name)
        val btnDownload: MaterialButton = v.findViewById(R.id.btn_download)
        val btnCopy: MaterialButton = v.findViewById(R.id.btn_copy)
        val btnDelete: MaterialButton = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_script, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.name.text = item.name
        holder.gameName.text = item.gameName
        
        // Luôn hiển thị cả 2 nút
        holder.btnDownload.setOnClickListener { onDownload(item) }
        holder.btnCopy.setOnClickListener { onCopy(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }
}
