package dev.glorioustr.mtzstudio

import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Restores a user-selected Xiaomi Themes BAK through HyperOS' own backup service. */
object SheveryBackupRestorer {
    enum class State { READY, PERMISSION_REQUIRED, SERVICE_NOT_RUNNING, UNSUPPORTED }
    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.SERVICE_NOT_RUNNING
            Shizuku.isPreV11() -> State.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.PERMISSION_REQUIRED
        }
    } catch (_: Throwable) { State.SERVICE_NOT_RUNNING }

    fun restore(file: File): Long {
        require(file.isFile && file.length() > 0L) { "BAK dosyası okunamıyor" }
        check(state() == State.READY) { "Shizuku/Shevery hazır değil veya izin verilmemiş" }
        val raw = SystemServiceHelper.getSystemService("MiuiBackup") ?: error("HyperOS MiuiBackup servisi bulunamadı")
        val binder: IBinder = ShizukuBinderWrapper(raw)
        val token = Binder()
        check(acquire(binder, token)) { "HyperOS yedekleme servisi meşgul" }
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val copied = AtomicLong()
        val writerError = AtomicReference<Throwable?>()
        val writer = thread(name = "mtz-bak-restore") {
            try {
                FileInputStream(file).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied.addAndGet(count.toLong())
                        }
                    }
                }
            } catch (error: Throwable) { writerError.set(error); runCatching { write.close() } }
        }
        try {
            restoreFile(binder, read)
            writer.join()
            writerError.get()?.let { throw it }
            check(copied.get() == file.length()) { "BAK verisi sisteme eksik aktarıldı" }
            return copied.get()
        } finally {
            runCatching { read.close() }; runCatching { write.close() }; runCatching { release(binder) }
        }
    }

    private fun acquire(binder: IBinder, token: IBinder): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("miui.app.backup.IBackupManager")
            data.writeStrongBinder(null); data.writeStrongBinder(token)
            check(binder.transact(4, data, reply, 0)) { "HyperOS yedekleme servisine bağlanılamadı" }
            reply.readException(); reply.readInt() != 0
        } finally { reply.recycle(); data.recycle() }
    }
    private fun restoreFile(binder: IBinder, file: ParcelFileDescriptor) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("miui.app.backup.IBackupManager")
            data.writeInt(1); file.writeToParcel(data, 0); data.writeString("com.android.thememanager"); data.writeInt(1); data.writeStrongBinder(null)
            check(binder.transact(3, data, reply, 0)) { "HyperOS geri yüklemeyi başlatmadı" }
            reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }
    private fun release(binder: IBinder) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("miui.app.backup.IBackupManager")
            data.writeStrongBinder(null)
            if (binder.transact(5, data, reply, 0)) reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }
}
