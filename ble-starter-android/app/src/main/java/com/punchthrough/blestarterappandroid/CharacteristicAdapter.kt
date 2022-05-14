/*
 * Copyright 2019 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid

import android.bluetooth.BluetoothGattCharacteristic
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.ble.printProperties
import kotlinx.android.synthetic.main.row_characteristic.view.characteristic_properties
import kotlinx.android.synthetic.main.row_characteristic.view.characteristic_uuid
import org.jetbrains.anko.layoutInflater

public val characteristicMap = mutableMapOf<String, String>()

class CharacteristicAdapter(

    private val items: List<BluetoothGattCharacteristic>,
    private val onClickListener: ((characteristic: BluetoothGattCharacteristic) -> Unit)
) : RecyclerView.Adapter<CharacteristicAdapter.ViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        characteristicMap.put("05ed8326-b407-11ec-b909-0242ac120002", "hoja")
        characteristicMap.put("f72e3316-b407-11ec-b909-0242ac120002", "stopnice")
        characteristicMap.put("05f99232-b408-11ec-b909-0242ac120002", "dvigalo")
        characteristicMap.put("f7a9b8d6-b408-11ec-b909-0242ac120002", "idle")
        characteristicMap.put("44f709ee-d2bf-11ec-9d64-0242ac120002", "Uncertain")

        val view = parent.context.layoutInflater.inflate(
            R.layout.row_characteristic,
            parent,
            false
        )
        return ViewHolder(view, onClickListener)
    }
    
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    class ViewHolder(
        private val view: View,
        private val onClickListener: ((characteristic: BluetoothGattCharacteristic) -> Unit)
    ) : RecyclerView.ViewHolder(view) {

        fun bind(characteristic: BluetoothGattCharacteristic) {
            view.characteristic_uuid.text = characteristicMap[characteristic.uuid.toString()] ?: characteristic.uuid.toString()
            view.characteristic_properties.text = characteristic.printProperties()
            view.setOnClickListener { onClickListener.invoke(characteristic) }
        }
    }
}
