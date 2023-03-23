package com.capacitorjs.community.plugins.bluetoothle

import CapBleManager
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.getcapacitor.Logger
import java.util.*
import kotlin.collections.HashMap

class CallbackResponse(
    val success: Boolean,
    val value: String,
)

class Device(
    private val context: Context,
    bluetoothAdapter: BluetoothAdapter,
    private val address: String,
    private val onDisconnect: () -> Unit
) {
    companion object {
        private val TAG = Device::class.java.simpleName
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private var manager = CapBleManager(context)
    private var connectionState = STATE_DISCONNECTED
    private var device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
    private var bluetoothGatt: BluetoothGatt? = null
    private var callbackMap = HashMap<String, ((CallbackResponse) -> Unit)>()
    private var timeoutMap = HashMap<String, Handler>()
    private var bondStateReceiver: BroadcastReceiver? = null

    fun getId(): String {
        return address
    }

    /**
     * Actions that will be executed (see gattCallback)
     * - connect to gatt server
     * - discover services
     * - request MTU
     */
    fun connect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        connectionState = STATE_CONNECTING

        Logger.debug("==== CONNECTING START ====")
        this.manager.connect(device).timeout(timeout).await()
        Logger.debug("==== CONNECTING END ====")

        connectionState = STATE_CONNECTED

        callback(CallbackResponse(true, "connected"))
    }

    private fun connectCallOngoing(): Boolean {
        return callbackMap.containsKey("connect")
    }

    fun isConnected(): Boolean {
        return connectionState == STATE_CONNECTED
    }

    private fun requestMtu(mtu: Int) {
        Logger.debug(TAG, "requestMtu $mtu")
        val result = bluetoothGatt?.requestMtu(mtu)
        if (result != true) {
            reject("connect", "Starting requestMtu failed.")
        }
    }

    fun createBond(callback: (CallbackResponse) -> Unit) {
        val key = "createBond"
        callbackMap[key] = callback
        try {
            createBondStateReceiver()
        } catch (e: Error) {
            Logger.error(TAG, "Error while registering bondStateReceiver: ${e.localizedMessage}", e)
            reject(key, "Creating bond failed.")
            return
        }
        val result = device.createBond()
        if (!result) {
            reject(key, "Creating bond failed.")
            return
        }
        // if already bonded, resolve immediately
        if (isBonded()) {
            resolve(key, "Creating bond succeeded.")
            return
        }
        // otherwise, wait for bond state change
    }

    private fun createBondStateReceiver() {
        if (bondStateReceiver == null) {
            bondStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        val key = "createBond"
                        val updatedDevice =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        // BroadcastReceiver receives bond state updates from all devices, need to filter by device
                        if (device.address == updatedDevice?.address) {
                            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                            val previousBondState =
                                intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                            Logger.debug(
                                TAG, "Bond state transition $previousBondState -> $bondState"
                            )
                            if (bondState == BluetoothDevice.BOND_BONDED) {
                                resolve(key, "Creating bond succeeded.")
                            } else if (previousBondState == BluetoothDevice.BOND_BONDING && bondState == BluetoothDevice.BOND_NONE) {
                                reject(key, "Creating bond failed.")
                            } else if (bondState == -1) {
                                reject(key, "Creating bond failed.")
                            }
                        }
                    }
                }
            }
            val intentFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondStateReceiver, intentFilter)
        }
    }

    fun isBonded(): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    fun disconnect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== DISCONNECTING ====")
        this.manager.disconnect().timeout(timeout).await()

        connectionState = STATE_DISCONNECTED

        callback(CallbackResponse(true, "disconnected"))
    }

    fun getServices(): MutableList<BluetoothGattService> {
        return bluetoothGatt?.services ?: mutableListOf()
    }

    fun discoverServices(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "discoverServices"
        callbackMap[key] = callback
        refreshDeviceCache()
        val result = bluetoothGatt?.discoverServices()
        if (result != true) {
            reject(key, "Service discovery failed.")
            return
        }
        setTimeout(key, "Service discovery timeout.", timeout)
    }

    private fun refreshDeviceCache(): Boolean {
        var result = false

        try {
            if (bluetoothGatt != null) {
                val refresh = bluetoothGatt!!.javaClass.getMethod("refresh")
                result = (refresh.invoke(bluetoothGatt) as Boolean)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error while refreshing device cache: ${e.localizedMessage}", e)
        }

        Logger.debug(TAG, "Device cache refresh $result")
        return result
    }

    fun readRssi(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        val key = "readRssi"
        callbackMap[key] = callback
        val result = bluetoothGatt?.readRemoteRssi()
        if (result != true) {
            reject(key, "Reading RSSI failed.")
            return
        }
        setTimeout(key, "Reading RSSI timeout.", timeout)
    }

    fun read(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== READ START ====")

        val service = this.manager.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        var data: ByteArray? = null

        this.manager.readCharacteristic(characteristic).with { _, payload ->
          Logger.debug("==== READ DATA ====")
          Logger.debug("$payload")
          data = payload.value
        }.await()

        Logger.debug("==== READ END ====")

        // TODO: Handle timeout on read ...

        callback(CallbackResponse(true, bytesToString(data ?: byteArrayOf())))
    }

    fun write(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        value: String,
        writeType: Int,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== WRITE START ====")

        val service = this.manager.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        val bytes = stringToBytes(value)
        var data: ByteArray? = null

        this.manager.writeCharacteristic(characteristic, bytes, writeType).with { _, payload ->
          Logger.debug("==== WRITE DATA ====")
          Logger.debug("$payload")
          data = payload.value
        }.await()

        Logger.debug("==== WRITE END ====")

        // TODO: Handle timeout on write ...

        callback(CallbackResponse(true, bytesToString(data ?: byteArrayOf())))
    }

    fun setNotifications(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        enable: Boolean,
        notifyCallback: ((CallbackResponse) -> Unit)?,
        callback: (CallbackResponse) -> Unit,
    ) {

        val key = "writeDescriptor|$serviceUUID|$characteristicUUID|$CLIENT_CHARACTERISTIC_CONFIG"
        val service = this.manager.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        this.manager.setNotificationCallback(characteristic).with { _, data ->
            if (data.value != null) {
                val value = String(data.value!!, Charsets.UTF_8)
                notifyCallback?.invoke(CallbackResponse(true, value));
            }
        }
        if(enable) {
            this.manager.enableNotifications(characteristic).await();
        }
        callback(CallbackResponse(true, ""));
    }

    fun readDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "readDescriptor|$serviceUUID|$characteristicUUID|$descriptorUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        if (descriptor == null) {
            reject(key, "Descriptor not found.")
            return
        }
        val result = bluetoothGatt?.readDescriptor(descriptor)
        if (result != true) {
            reject(key, "Reading descriptor failed.")
            return
        }
        setTimeout(key, "Read descriptor timeout.", timeout)
    }

    fun writeDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        value: String,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        val key = "writeDescriptor|$serviceUUID|$characteristicUUID|$descriptorUUID"
        callbackMap[key] = callback
        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            reject(key, "Characteristic not found.")
            return
        }
        val descriptor = characteristic.getDescriptor(descriptorUUID)
        if (descriptor == null) {
            reject(key, "Descriptor not found.")
            return
        }
        val bytes = stringToBytes(value)
        descriptor.value = bytes
        val result = bluetoothGatt?.writeDescriptor(descriptor)
        if (result != true) {
            reject(key, "Writing characteristic failed.")
            return
        }
        setTimeout(key, "Write timeout.", timeout)
    }

    private fun resolve(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Logger.debug(TAG, "resolve: $key $value")
            callbackMap[key]?.invoke(CallbackResponse(true, value))
            callbackMap.remove(key)
            timeoutMap[key]?.removeCallbacksAndMessages(null)
            timeoutMap.remove(key)
        }
    }

    private fun reject(key: String, value: String) {
        if (callbackMap.containsKey(key)) {
            Logger.debug(TAG, "reject: $key $value")
            callbackMap[key]?.invoke(CallbackResponse(false, value))
            callbackMap.remove(key)
            timeoutMap[key]?.removeCallbacksAndMessages(null)
            timeoutMap.remove(key)
        }
    }

    private fun setTimeout(
        key: String, message: String, timeout: Long
    ) {
        val handler = Handler(Looper.getMainLooper())
        timeoutMap[key] = handler
        handler.postDelayed({
            reject(key, message)
        }, timeout)
    }
}
