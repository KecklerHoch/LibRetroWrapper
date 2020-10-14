package com.draco.libretrowrapper

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.swordfish.libretrodroid.GLRetroView
import io.reactivex.disposables.CompositeDisposable
import java.io.File

class GameActivity : AppCompatActivity() {
    private lateinit var parent: FrameLayout
    private lateinit var safeGLRV: SafeGLRV
    private lateinit var privateData: PrivateData

    private lateinit var leftGamePadContainer: FrameLayout
    private lateinit var rightGamePadContainer: FrameLayout
    private lateinit var leftGamePad: GamePad
    private lateinit var rightGamePad: GamePad

    private val compositeDisposable = CompositeDisposable()

    private val validKeyCodes = listOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT
    )

    private val validAssets = listOf(
        "rom",      /* ROM file itself */
        "save",     /* SRAM dump */
        "state"     /* Save state dump */
    )

    private fun initAssets() {
        for (asset in validAssets) {
            try {
                val assetInputStream = assets.open(asset)
                val assetBytes = assetInputStream.readBytes()
                val assetFile = File("${filesDir.absolutePath}/$asset")

                if (!assetFile.exists())
                    assetFile.writeBytes(assetBytes)
            } catch (_: Exception) {}
        }
    }

    private fun isControllerConnected(): Boolean {
        /* Consider non-touch devices to be controller supported only */
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            return true

        for (id in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(id).apply {
                if (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
                    return true
            }
        }

        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        /* Initialize layout variables */
        parent = findViewById(R.id.parent)
        leftGamePadContainer = findViewById(R.id.left_container)
        rightGamePadContainer = findViewById(R.id.right_container)

        /* Setup private data handler */
        privateData = PrivateData(this)

        /* Copy assets */
        initAssets()

        /* Initialize save data */
        val saveBytes = if (privateData.save.exists())
            privateData.save.readBytes()
        else
            byteArrayOf()

        /* Create GLRetroView */
        val retroView = GLRetroView(
            this,
            "${getString(R.string.rom_core)}_libretro_android.so",
            privateData.rom.absolutePath,
            saveRAMState = saveBytes,
            shader = GLRetroView.SHADER_SHARP
        )
        lifecycle.addObserver(retroView)
        parent.addView(retroView)

        /* Initialize safe GLRetroView handler */
        safeGLRV = SafeGLRV(retroView, compositeDisposable)

        /* Initialize GamePads */
        val gamePadConfig = GamePadConfig()
        val gamePadConfigs = when (resources.getInteger(R.integer.rom_gamepad_type)) {
            2 -> listOf(gamePadConfig.Type2Left, gamePadConfig.Type2Right)
            3 -> listOf(gamePadConfig.Type3Left, gamePadConfig.Type3Right)
            else -> listOf(gamePadConfig.Type1Left, gamePadConfig.Type1Right)
        }
        leftGamePad = GamePad(this, gamePadConfigs[0], safeGLRV, privateData)
        rightGamePad = GamePad(this, gamePadConfigs[1], safeGLRV, privateData)

        /* Add GamePads to the activity */
        leftGamePadContainer.addView(leftGamePad.pad)
        rightGamePadContainer.addView(rightGamePad.pad)

        /* Configure GamePad sizes */
        leftGamePad.pad.offsetX = -1f
        rightGamePad.pad.offsetX = 1f

        val density = resources.displayMetrics.density
        val gamePadSize = resources.getDimension(R.dimen.rom_gamepad_size) / density

        leftGamePad.pad.primaryDialMaxSizeDp = gamePadSize
        rightGamePad.pad.primaryDialMaxSizeDp = gamePadSize

        /* Check if we should show or hide controls */
        showOrHideGamePads()

        /* Center view */
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = Gravity.CENTER
        retroView.layoutParams = params

        /* Decide to mute the audio */
        safeGLRV.safe {
            it.audioEnabled = resources.getBoolean(R.bool.rom_audio)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus)
            return

        /* Apply immersive mode */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            with (window.insetsController!!) {
                hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in validKeyCodes)
            return false

        safeGLRV.safe {
            it.sendKeyEvent(event.action, keyCode)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyEvent(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyEvent(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        safeGLRV.safe {
            when (it.id) {
                GLRetroView.MOTION_SOURCE_DPAD -> it.sendMotionEvent(
                    GLRetroView.MOTION_SOURCE_DPAD,
                    event.getAxisValue(MotionEvent.AXIS_HAT_X),
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                )
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT -> it.sendMotionEvent(
                    GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                    event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y)
                )
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT -> it.sendMotionEvent(
                    GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                    event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ)
                )
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun showOrHideGamePads() {
        val visibility = if (isControllerConnected())
            View.GONE
        else
            View.VISIBLE

        leftGamePadContainer.visibility = visibility
        rightGamePadContainer.visibility = visibility
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        showOrHideGamePads()
    }

    override fun onResume() {
        super.onResume()

        leftGamePad.resume()
        rightGamePad.resume()
    }

    override fun onPause() {
        leftGamePad.pause()
        rightGamePad.pause()

        /* Must be unsafe, else activity crashes */
        if (safeGLRV.isSafe)
            privateData.save.writeBytes(safeGLRV.unsafeGLRetroView.serializeSRAM())
        super.onPause()
    }

    override fun onDestroy() {
        compositeDisposable.dispose()
        super.onDestroy()
    }
}