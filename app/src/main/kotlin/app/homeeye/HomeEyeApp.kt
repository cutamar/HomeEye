package app.homeeye

import android.app.Application
import android.content.Context
import app.homeeye.data.CameraRepository

class HomeEyeApp : Application() {
    lateinit var cameras: CameraRepository
        private set

    override fun onCreate() {
        super.onCreate()
        cameras = CameraRepository(this)
    }
}

val Context.cameraRepository: CameraRepository
    get() = (applicationContext as HomeEyeApp).cameras
