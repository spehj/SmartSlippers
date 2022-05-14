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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.readCharacteristic
import com.punchthrough.blestarterappandroid.ble.isIndicatable
import com.punchthrough.blestarterappandroid.ble.isNotifiable
import com.punchthrough.blestarterappandroid.ble.isReadable
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import com.punchthrough.blestarterappandroid.ble.toHexString
import kotlinx.android.synthetic.main.activity_ble_operations.tvStopnice

import org.jetbrains.anko.alert
import org.jetbrains.anko.find
import org.jetbrains.anko.noButton
import org.jetbrains.anko.runOnUiThread
import org.jetbrains.anko.selector
import org.jetbrains.anko.yesButton
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleOperationsActivity : AppCompatActivity() {

    private lateinit var device: BluetoothDevice
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    lateinit var notificationChannel: NotificationChannel
    lateinit var notificationManager: NotificationManager
    lateinit var builder: Notification.Builder
    private val channelId = "12345"
    private val description = "Test Notification"

    // Initialize variables for predictions
    private var noHoja = 0;
    private var noStopnice = 0;
    private var noDvigalo = 0;
    private var noIdle = 0;
    private var noUncertain = 0;

    // Insert activity values
    private lateinit var tvHojaValue: TextView
    private lateinit var tvIdleValue: TextView
    private lateinit var tvStopniceValue: TextView
    private lateinit var tvDvigaloValue: TextView
    private lateinit var tvUncertainValue: TextView
    private lateinit var tvCurrActValue: TextView
    private lateinit var lastCurrActValue: TextView
    private lateinit var tvLastActValue: TextView
    private lateinit var tvActTimeValue: TextView

    val hojaActivity = UserActivity("Hoja")

    private var lastActivityTime: Long = System.currentTimeMillis()


    private val characteristicMap = mutableMapOf<String, String>()

    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }
    private val characteristicProperties by lazy {
        characteristics.map { characteristic ->
            characteristic to mutableListOf<CharacteristicProperty>().apply {
                if (characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                if (characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                if (characteristic.isReadable()) add(CharacteristicProperty.Readable)
                if (characteristic.isWritable()) add(CharacteristicProperty.Writable)
                if (characteristic.isWritableWithoutResponse()) {
                    add(CharacteristicProperty.WritableWithoutResponse)
                }
            }.toList()
        }.toMap()
    }
    private val characteristicAdapter: CharacteristicAdapter by lazy {
        CharacteristicAdapter(characteristics) {}
        //characteristic ->
        //showCharacteristicOptions(characteristic)
    }
    private var notifyingCharacteristics = mutableListOf<UUID>()


    override fun onCreate(savedInstanceState: Bundle?) {
        ConnectionManager.registerListener(connectionEventListener)
        super.onCreate(savedInstanceState)

        characteristicMap.put("05ed8326-b407-11ec-b909-0242ac120002", "Hoja")
        characteristicMap.put("f72e3316-b407-11ec-b909-0242ac120002", "Stopnice")
        characteristicMap.put("05f99232-b408-11ec-b909-0242ac120002", "Dvigalo")
        characteristicMap.put("f7a9b8d6-b408-11ec-b909-0242ac120002", "Idle")
        characteristicMap.put("44f709ee-d2bf-11ec-9d64-0242ac120002", "Uncertain")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")

        // Set correct activity
        setContentView(R.layout.activity_ble_operations)

        tvHojaValue = findViewById(R.id.tvHojaValue)
        tvIdleValue = findViewById(R.id.tvIdleValue)
        tvStopniceValue = findViewById(R.id.tvStopniceValue)
        tvDvigaloValue = findViewById(R.id.tvDvigaloValue)
        tvUncertainValue = findViewById(R.id.tvUncertainValue)
        tvCurrActValue = findViewById(R.id.tvCurrActValue)
        tvLastActValue = findViewById(R.id.tvLastActValue)
        tvActTimeValue = findViewById((R.id.tvActTimeValue))


        // Initial read of characteristics
        readCharacteristic(device, characteristics[3])
        readCharacteristic(device, characteristics[4])
        readCharacteristic(device, characteristics[5])
        readCharacteristic(device, characteristics[6])
        readCharacteristic(device, characteristics[7])

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            //setLogo(R.mipmap.ic_launcher_foreground)
            //setDisplayUseLogoEnabled(true)
            title = getString(R.string.ble_playground)
        }

        // Here we define all characteristics where we want notifications
        ConnectionManager.enableNotifications(device, characteristics[3])
        ConnectionManager.enableNotifications(device, characteristics[4])
        ConnectionManager.enableNotifications(device, characteristics[5])
        ConnectionManager.enableNotifications(device, characteristics[6])
        ConnectionManager.enableNotifications(device, characteristics[7])


        // Declaring Main Thread
        Thread(Runnable {
            while (true) {
                // Updating Text View at current
                // iteration
                runOnUiThread {
                    if (tvCurrActValue.text == "Uncertain") {
                        tvLastActValue.text = timeFromActivity(lastActivityTime)

                    }
                    tvActTimeValue.text = hojaActivity.current()
                }

                // Thread sleep for 1 sec
                Thread.sleep(1000)
                // Updating Text View at current
                // iteration
                //runOnUiThread{ tv.text = msg2 }
                // Thread sleep for 1 sec
                //Thread.sleep(1000)
            }
        }).start()


    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.i("TAG", "Option selected $item")
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }


    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)

    }


    private fun showCharacteristicOptions(characteristic: BluetoothGattCharacteristic) {
        characteristicProperties[characteristic]?.let { properties ->
            selector("Select an action to perform", properties.map { it.action }) { _, i ->
                when (properties[i]) {
                    CharacteristicProperty.Readable -> {
                        log("Reading from ${characteristicMap[characteristic.uuid.toString()]}")
                        readCharacteristic(device, characteristic)
                    }
                    CharacteristicProperty.Writable, CharacteristicProperty.WritableWithoutResponse -> {
                        showWritePayloadDialog(characteristic)
                    }
                    CharacteristicProperty.Notifiable, CharacteristicProperty.Indicatable -> {
                        if (notifyingCharacteristics.contains(characteristic.uuid)) {
                            log("Disabling notifications on ${characteristic.uuid}")
                            ConnectionManager.disableNotifications(device, characteristic)
                        } else {
                            log("Enabling notifications on ${characteristic.uuid}")
                            ConnectionManager.enableNotifications(device, characteristic)
                        }
                    }
                }
            }
        }


    }


    @SuppressLint("InflateParams")
    private fun showWritePayloadDialog(characteristic: BluetoothGattCharacteristic) {
        val hexField = layoutInflater.inflate(R.layout.edittext_hex_payload, null) as EditText
        alert {
            customView = hexField
            isCancelable = false
            yesButton {
                with(hexField.text.toString()) {
                    if (isNotBlank() && isNotEmpty()) {
                        val bytes = hexToBytes()
                        log("Writing to ${characteristic.uuid}: ${bytes.toHexString()}")
                        ConnectionManager.writeCharacteristic(device, characteristic, bytes)
                    } else {
                        log("Please enter a hex payload to write to ${characteristic.uuid}")
                    }
                }
            }
            noButton {}
        }.show()
        hexField.showKeyboard()
    }

    private fun timeFromActivity(timeOfLastActivity: Long): String {
        val timeNow: Long = System.currentTimeMillis()
        val timeDifference: Long = timeNow - timeOfLastActivity
        // timeDifference is in milliseconds
        var seconds = timeDifference / 1000
        var minutes = seconds / 60
        var hours = minutes / 60
        var secondsLeft = seconds - (60 * minutes)
        val minutesLeft = minutes - (60 * hours)

        var result = if (hours >= 24) {
            ">1 day"
        } else {
            "${hours}h ${minutesLeft}min ${secondsLeft}s"
        }
        return result
    }


    private fun updateUiDecks(charName: String?) {
        tvCurrActValue.text = charName.toString()
        if (charName.toString() != "Uncertain") {
            tvLastActValue.text = "Live"
        }
    }

    // Function that catch new values from Arduino Nano 33 BLE
    private fun getNewValues(charName: String?, charValue: Int) {
        when (charName) {
            "Hoja" -> {
                this.tvHojaValue.text = charValue.toString()
                if (charValue == 1) {
                    updateUiDecks(charName)
                    hojaActivity.start() // added1


                }
            }
            "Idle" -> {
                this.tvIdleValue.text = charValue.toString()
                if (charValue == 1) {
                    updateUiDecks(charName)
                }
            }
            "Stopnice" -> {
                this.tvStopniceValue.text = charValue.toString()
                if (charValue == 1) {
                    updateUiDecks(charName)
                }
            }
            "Dvigalo" -> {
                this.tvDvigaloValue.text = charValue.toString()
                if (charValue == 1) {
                    updateUiDecks(charName)
                }
            }
            "Uncertain" -> {
                this.tvUncertainValue.text = charValue.toString()
                if (charValue == 1) {
                    updateUiDecks(charName)
                    lastActivityTime = System.currentTimeMillis()

                    hojaActivity.stop() // added1
                }
            }
        }

        /*
        if (tvCurrActValue.toString() != charValue.toString()){
            this.tvCurrActValue.text = charName.toString()
        }
        */


    }

    private fun readChar(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        /* Function reads characteristics and return a string */
        var charValue = characteristics[3].value.toHexString()
        //var charStr = charValue.toString()
        Log.i("READVALUE", "Char = $charValue")
    }


    @OptIn(ExperimentalStdlibApi::class)
    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected from device."
                        positiveButton("OK") { onBackPressed() }
                    }.show()
                }
            }
            onCharacteristicRead = { _, characteristic ->
                // Get the name and value of changed characteristic
                var characteristicName = characteristicMap[characteristic.uuid.toString()]
                var strReceived = characteristic.value.toHexString();
                // Parse string to get HEX value
                strReceived = strReceived.substring(2, 4)
                // Convert HEX to Int
                var intReceived = strReceived.toInt(16)


                Log.i("OPERATIONS", "Value read on ${characteristicName}: ${intReceived}")
                // Pass new values to activity
                runOnUiThread {
                    getNewValues(characteristicName, intReceived)
                }
            }

            onCharacteristicChanged = { _, characteristic ->

                // Get the name and value of changed characteristic
                var characteristicName = characteristicMap[characteristic.uuid.toString()]
                var strReceived = characteristic.value.toHexString();
                // Parse string to get HEX value
                strReceived = strReceived.substring(2, 4)
                // Convert HEX to Int
                var intReceived = strReceived.toInt(16)


                Log.i("OPERATIONS", "Value changed on ${characteristicName}: ${intReceived}")
                // Pass new values to activity
                runOnUiThread {
                    getNewValues(characteristicName, intReceived)
                }


            }

            onNotificationsEnabled = { _, characteristic ->
                Log.i(
                    "OPERATIONS",
                    "Enabled notifications on ${characteristicMap[characteristic.uuid.toString()]}"
                )
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                Log.i(
                    "OPERATIONS",
                    "Disabled notifications on ${characteristicMap[characteristic.uuid.toString()]}"
                )
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }

    private enum class CharacteristicProperty {
        Readable,
        Writable,
        WritableWithoutResponse,
        Notifiable,
        Indicatable;

        val action
            get() = when (this) {
                Readable -> "Read"
                Writable -> "Write"
                WritableWithoutResponse -> "Write Without Response"
                Notifiable -> "Toggle Notifications"
                Indicatable -> "Toggle Indications"
            }
    }

    private fun Activity.hideKeyboard() {
        hideKeyboard(currentFocus ?: View(this))
    }

    private fun Context.hideKeyboard(view: View) {
        val inputMethodManager =
            getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun EditText.showKeyboard() {
        val inputMethodManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.toUpperCase(Locale.US).toInt(16).toByte() }.toByteArray()
}
