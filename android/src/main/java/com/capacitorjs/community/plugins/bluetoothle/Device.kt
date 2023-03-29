package com.capacitorjs.community.plugins.bluetoothle

import CapBleManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.content.Context
import androidx.annotation.RequiresPermission
import com.getcapacitor.Logger
import java.util.*
import java.util.concurrent.*


class CallbackResponse(
    val success: Boolean,
    val value: String,
)

class Device(
    private val context: Context,
    bluetoothAdapter: BluetoothAdapter,
    private val address: String
) {
    companion object {
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2
    }

    private var manager = CapBleManager(context)
    private var connectionState = STATE_DISCONNECTED
    private var device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
    private var bluetoothGatt: BluetoothGatt? = null

    fun getId(): String {
        return address
    }

    fun connect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        this.manager = CapBleManager(context)

        connectionState = STATE_CONNECTING

        Logger.debug("==== CONNECTING START ====")
        try {
            this.manager.connect(device).timeout(timeout).await()
        } catch (e: Exception) {
            Logger.error("Error on connect", e)
            return callback(CallbackResponse(false, "error"))
        }
        Logger.debug("==== CONNECTING END ====")

        connectionState = STATE_CONNECTED

        callback(CallbackResponse(true, "connected"))
    }

    fun isConnected(): Boolean {
        return connectionState == STATE_CONNECTED
    }

    fun createBond(callback: (CallbackResponse) -> Unit) {
        this.manager.ensureBond().done {
            Logger.debug("==== BONDED ====")
            callback(CallbackResponse(true, "bonded"))
        }.fail { _, _ ->
            Logger.error("Error bonding with device")
            callback(CallbackResponse(false, "error"))
        }.enqueue()
    }

    @RequiresPermission(value = "android.permission.BLUETOOTH_CONNECT")
    fun isBonded(): Boolean {
        return device.bondState == BluetoothDevice.BOND_BONDED
    }

    fun disconnect(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== DISCONNECTING ====")
        try {
            this.manager.disconnect().timeout(timeout).await()
        } catch (e: Exception) {
            Logger.error("Error on disconnect", e)
            return callback(CallbackResponse(false, "error"))
        }

        connectionState = STATE_DISCONNECTED

        callback(CallbackResponse(true, "disconnected"))
    }

    fun getServices(): MutableList<BluetoothGattService> {
        return bluetoothGatt?.services ?: mutableListOf()
    }

    fun readRssi(
        timeout: Long, callback: (CallbackResponse) -> Unit
    ) {
        var data: Int? = null

        try {
            timedFn({
                this.manager.readRssi().with { _, rssi -> data = rssi }.await()
            }, timeout)
        } catch (e: Exception) {
            Logger.error("Error on read rssi", e)
            return callback(CallbackResponse(false, "error"))
        }

        callback(CallbackResponse(true, data.toString()))
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

        try {
            timedFn({
                this.manager.readCharacteristic(characteristic).with { _, payload ->
                    Logger.debug("==== READ DATA ====")
                    Logger.debug("$payload")
                    data = payload.value
                }.await()
            }, timeout)
        } catch (e: Exception) {
            Logger.error("Error on read", e)
            return callback(CallbackResponse(false, "error"))
        }

        Logger.debug("==== READ END ====")

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

        try {
            timedFn({
                this.manager.writeCharacteristic(characteristic, bytes, writeType).with { _, payload ->
                    Logger.debug("==== WRITE DATA ====")
                    Logger.debug("$payload")
                    data = payload.value
                }.await()
            }, timeout)
        } catch (e: Exception) {
            Logger.error("Error on write", e)
            return callback(CallbackResponse(false, "error"))
        }

        Logger.debug("==== WRITE END ====")

        callback(CallbackResponse(true, bytesToString(data ?: byteArrayOf())))
    }

    fun setNotifications(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        enable: Boolean,
        notifyCallback: ((CallbackResponse) -> Unit)?,
        callback: (CallbackResponse) -> Unit,
    ) {
        val service = this.manager.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)

        try {
            this.manager.setNotificationCallback(characteristic).with { _, data ->
                if (data.value != null) {
                    val value = String(data.value!!, Charsets.UTF_8)
                    notifyCallback?.invoke(CallbackResponse(true, value))
                }
            }
        } catch (e: Exception) {
            Logger.error("Error on setNotifications", e)
            return callback(CallbackResponse(false, "error"))
        }

        if (enable) {
            try {
                this.manager.enableNotifications(characteristic).await()
            } catch (e: Exception) {
                Logger.error("Error on enable notifications", e)
                return callback(CallbackResponse(false, "error"))
            }
        }

        callback(CallbackResponse(true, "notifications set"))
    }

    fun readDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== READ START ====")

        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        val descriptor = characteristic?.getDescriptor(descriptorUUID)
        var data: ByteArray? = null

        try {
           this.timedFn({
               this.manager.readDescriptor(descriptor).with { _, payload ->
                   Logger.debug("==== READ DATA ====")
                   Logger.debug("$payload")
                   data = payload.value
               }.await()
           }, timeout)
        } catch (e: Exception) {
            Logger.error("Error on read descriptor", e)
            return callback(CallbackResponse(false, "error"))
        }

        Logger.debug("==== READ END ====")

        callback(CallbackResponse(true, bytesToString(data ?: byteArrayOf())))
    }

    fun writeDescriptor(
        serviceUUID: UUID,
        characteristicUUID: UUID,
        descriptorUUID: UUID,
        value: String,
        timeout: Long,
        callback: (CallbackResponse) -> Unit
    ) {
        Logger.debug("==== WRITE START ====")

        val service = bluetoothGatt?.getService(serviceUUID)
        val characteristic = service?.getCharacteristic(characteristicUUID)
        val descriptor = characteristic?.getDescriptor(descriptorUUID)
        val bytes = stringToBytes(value)
        var data: ByteArray? = null

        try {
            timedFn({
                this.manager.writeDescriptor(descriptor, bytes).with { _, payload ->
                    Logger.debug("==== WRITE DATA ====")
                    Logger.debug("$payload")
                    data = payload.value
                }.await()
            }, timeout)
        } catch (e: Exception) {
            Logger.error("Error on write descriptor", e)
            return callback(CallbackResponse(false, "error"))
        }

        Logger.debug("==== WRITE END ====")

        callback(CallbackResponse(true, bytesToString(data ?: byteArrayOf())))
    }

    /**
     * Throwable timeout helper method, be sure to wrap this inside a try/catch block and
     * check for a TimeoutException.
     *
     * @param fn () -> Unit
     * @param timeout Long
     */
    private fun timedFn(fn: () -> Unit, timeout: Long) {
        val executor: ExecutorService = Executors.newCachedThreadPool()
        val task: Callable<Any> = Callable<Any> { fn() }
        val future: Future<Any> = executor.submit(task)

        future.get(timeout, TimeUnit.SECONDS)
    }
}
