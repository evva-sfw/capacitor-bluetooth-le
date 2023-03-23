import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.getcapacitor.Logger
import no.nordicsemi.android.ble.*
import java.util.*

internal class CapBleManager(context: Context) : BleManager(context) {

    private var gatt: BluetoothGatt? = null

    companion object {
        private const val TAG = "CapBleManager"
    }

    override fun getMinLogPriority(): Int {
        return Log.VERBOSE
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        Logger.debug("==== SERVICE DISCOVERY ====")

        this.gatt = gatt

        return true
    }

    fun getGatt(): BluetoothGatt? {
      return this.gatt
    }

    fun getService(uuid: UUID): BluetoothGattService? {
      return this.gatt?.getService(uuid)
    }

    override fun initialize() {
        requestMtu(512).enqueue()
    }

    override fun onServerReady(server: BluetoothGattServer) {
        Logger.debug("==== SERVER READY ====")
    }

    override fun onServicesInvalidated() {
        Logger.debug("==== DISCONNECTED ====")
    }

    public override fun refreshDeviceCache(): Request {
        return super.refreshDeviceCache()
    }

    public override fun readCharacteristic(characteristic: BluetoothGattCharacteristic?): ReadRequest {
        return super.readCharacteristic(characteristic)
    }

    public override fun writeCharacteristic(
      characteristic: BluetoothGattCharacteristic?,
      data: ByteArray?,
      writeType: Int
    ): WriteRequest {
        return super.writeCharacteristic(characteristic, data, writeType).split()
    }

    public override fun ensureBond(): Request {
      return super.ensureBond()
    }

    public override fun createBondInsecure(): Request {
      return super.createBondInsecure()
    }

    public override fun removeBond(): Request {
      return super.removeBond()
    }

    public override fun readDescriptor(descriptor: BluetoothGattDescriptor?): ReadRequest {
      return super.readDescriptor(descriptor)
    }

    public override fun writeDescriptor(
      descriptor: BluetoothGattDescriptor?,
      data: ByteArray?
    ): WriteRequest {
      return super.writeDescriptor(descriptor, data)
    }

    public override fun setNotificationCallback(characteristic: BluetoothGattCharacteristic?): ValueChangedCallback {
      return super.setNotificationCallback(characteristic)
    }

    public override fun removeNotificationCallback(characteristic: BluetoothGattCharacteristic?) {
      super.removeNotificationCallback(characteristic)
    }

    public override fun enableNotifications(characteristic: BluetoothGattCharacteristic?): WriteRequest {
        return super.enableNotifications(characteristic)
    }

}
