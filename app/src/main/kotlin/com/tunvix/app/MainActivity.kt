package com.tunvix.app

import android.content.Intent
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.*
import com.tunvix.app.ui.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var enteringSmallWindow = false
    private var pictureInPictureSessionActive = false
    private lateinit var connectivity: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { runOnUiThread { updateNetwork() } }
        override fun onLost(network: Network) { runOnUiThread { updateNetwork() } }
    }
    private fun updateNetwork() { model.mobileNetwork = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
    private val model: TunvixModel by viewModels {object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = TunvixModel(ServiceFactory.create(applicationContext)) as T
    }}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT){false},SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT){false})
        model.requestPictureInPicture = { enterSmallWindow() }
        model.requestPictureInPictureSettings = { guideSmallWindowPermission(alwaysOpen=true) }
        model.requestPictureInPicturePermission = { guideSmallWindowPermission() }
        if (intent.getBooleanExtra("openPlayback",false) && model.currentChannel!=null) model.page=Page.PLAYER
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        connectivity=getSystemService(ConnectivityManager::class.java)
        updateNetwork()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        intent.getStringExtra("preview")?.let{if(model.services.demo)model.preview(it)}
        setContent {
            val dark=UiRules.darkAppearance(model.catalog.settings.themeMode,isSystemInDarkTheme())
            val palette=remember(dark){DesignPalette(dark)}
            val screenState=rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDesign provides palette) {
            val scheme=if(dark)darkColorScheme(primary=Design.Primary,onPrimary=Design.OnPrimary,background=Design.Background,surface=Design.Surface,onSurface=Design.Ink,onBackground=Design.Ink)
                else lightColorScheme(primary=Design.Primary,onPrimary=Design.OnPrimary,background=Design.Background,surface=Design.Surface,onSurface=Design.Ink,onBackground=Design.Ink)
            MaterialTheme(colorScheme=scheme) {
                val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
                    if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};model.fileUri=uri.toString();model.fileName=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null} ?: "频道列表";if(model.formName.isBlank())model.formName=model.fileName.substringBeforeLast('.')}
                }
                BackHandler(enabled=model.page!=Page.HOME||model.overlay!=null||model.showIndex||model.searchOpen||model.fullscreen){model.back()}
                val keepAwake=!model.playback.audioOnly&&!model.pictureInPicture&&UiRules.keepScreenAwake(model.page,model.playback.phase,model.foreground,model.showIndex)
                SideEffect {
                    updateSmallWindowParams()
                    if(isInPictureInPictureMode && model.playback.phase==com.tunvix.contract.PlaybackPhase.IDLE)finish()
                    if(keepAwake)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                DisposableEffect(Unit) { onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
                val bottomColor=if(model.page in listOf(Page.HOME,Page.FAVORITES,Page.RECENT,Page.MANAGEMENT,Page.PLAYER) && !model.showIndex)Design.Surface else Design.Background
                val controller=WindowInsetsControllerCompat(window,window.decorView)
                SideEffect{controller.isAppearanceLightStatusBars=!dark;controller.isAppearanceLightNavigationBars=!dark&&!model.fullscreen;window.isNavigationBarContrastEnforced=false}
                LaunchedEffect(model.fullscreen){
                    requestedOrientation=if(model.fullscreen)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    if(model.fullscreen){controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;controller.hide(WindowInsetsCompat.Type.systemBars())} else controller.show(WindowInsetsCompat.Type.systemBars())
                }
                Box(Modifier.fillMaxSize().background(Design.Background)) {
                    if(model.pictureInPicture) model.currentChannel?.let { VideoArea(model,it,Modifier.fillMaxSize(),pip=true) }
                    else if(model.fullscreen)FullscreenScreen(model)
                    else Column(Modifier.fillMaxSize().background(bottomColor).imePadding().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                        Box(Modifier.weight(1f).fillMaxWidth().background(Design.Background)) {
                            screenState.SaveableStateProvider(model.page.name) {
                            if(!model.catalogReady) Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                                androidx.compose.material3.CircularProgressIndicator(color=Design.Primary)
                                Spacer(Modifier.height(18.dp));Label("正在读取频道",14,Design.Muted)
                            } else if(model.catalogError != null) EmptyState(Glyph.ALERT,"本地数据暂时无法读取",model.catalogError ?: "请重新打开应用后重试","关闭应用"){finish()}
                            else if(model.showIndex)DesignIndex(model) else when(model.page) {
                                Page.HOME -> HomeScreen(model)
                                Page.FAVORITES,Page.RECENT -> CollectionScreen(model)
                                Page.MANAGEMENT -> ManagementScreen(model)
                                Page.PLAYER -> PlayerScreen(model)
                                Page.SOURCES -> SourcesScreen(model)
                                Page.ADD -> AddScreen(model)
                                Page.SUBSCRIBE,Page.LOCAL,Page.MANUAL,Page.EDIT_SOURCE -> FormScreen(model){picker.launch(arrayOf("*/*"))}
                                Page.RESULT -> ResultScreen(model)
                                Page.ABOUT -> AboutScreen(model)
                                Page.EPG -> EpgSettingsScreen(model)
                            }
                            }
                        }
                    }
                    if(!model.pictureInPicture)model.toast?.let{message->
                        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal=20.dp,vertical=100.dp).background(Color(0xFF222E49),RoundedCornerShape(14.dp)).padding(horizontal=17.dp,vertical=12.dp)){Label(message,12,Color.White,align=androidx.compose.ui.text.style.TextAlign.Center)}
                        LaunchedEffect(message){delay(2500);if(model.toast==message)model.toast=null}
                    }
                    if(!model.pictureInPicture)AppOverlay(model)
                }
                // Poll external volume-key changes while visible, without a private broadcast receiver.
                LaunchedEffect(Unit){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(true){model.services.playback.refreshVolume();delay(300)}}}

            }
            }
        }
    }
    override fun onStart(){super.onStart();if(!isInPictureInPictureMode)pictureInPictureSessionActive=false;model.onForeground()}
    override fun onStop(){
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pictureInPictureSessionActive && !isInPictureInPictureMode) {
            pictureInPictureSessionActive = false
            model.closePictureInPicture()
        }
        model.services.playback.setBackgroundPlayback(isInPictureInPictureMode || enteringSmallWindow)
        model.onBackground()
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        enteringSmallWindow=false
        if(!isInPictureInPictureMode)model.services.playback.setBackgroundPlayback(false)
        updateSmallWindowParams()
    }
    private fun supportsSmallWindow() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun allowsSmallWindow(): Boolean = supportsSmallWindow() &&
        getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,Process.myUid(),packageName) == AppOpsManager.MODE_ALLOWED
    private fun smallWindowParams(): PictureInPictureParams {
        val ratio=model.playback.videoAspectRatio.takeIf { it.isFinite() && it>0 }?.coerceIn(1f/2.39f,2.39f) ?: (16f/9f)
        val builder=PictureInPictureParams.Builder().setAspectRatio(Rational((ratio*1000).toInt(),1000))
        model.videoBounds?.let(builder::setSourceRectHint)
        if(Build.VERSION.SDK_INT>=31)builder.setAutoEnterEnabled(
            !model.services.demo && model.overlay==null && !model.showIndex && model.catalog.settings.autoPictureInPicture && allowsSmallWindow() && model.page==Page.PLAYER &&
                !model.playback.audioOnly && model.playback.phase==com.tunvix.contract.PlaybackPhase.PLAYING)
        return builder.build()
    }
    private fun updateSmallWindowParams() { if(supportsSmallWindow())runCatching { setPictureInPictureParams(smallWindowParams()) } }
    private fun guideSmallWindowPermission(alwaysOpen: Boolean=false) {
        if(!supportsSmallWindow()){model.toast="此设备不支持系统画中画";return}
        if(!alwaysOpen && allowsSmallWindow())return
        model.overlay=Overlay.Confirm("允许小窗播放", "请在系统设置中允许 Tunvix 使用画中画，开启后返回应用再点击小窗。", "前往系统设置") {
            model.overlay=null
            val settings=Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:$packageName"))
            if(runCatching { startActivity(settings) }.isFailure)
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
        }
    }
    private fun enterSmallWindow() {
        if(model.currentChannel==null)return
        if(!allowsSmallWindow()){guideSmallWindowPermission();return}
        if(model.playback.audioOnly)model.services.playback.setAudioOnly(false)
        model.services.playback.setBackgroundPlayback(true)
        enteringSmallWindow=true
        val entered=runCatching { enterPictureInPictureMode(smallWindowParams()) }.getOrDefault(false)
        if(!entered){enteringSmallWindow=false;model.services.playback.setBackgroundPlayback(false);model.toast="暂时无法进入系统小窗，请检查画中画权限"}
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(!model.services.demo && model.overlay==null && !model.showIndex && model.catalog.settings.autoPictureInPicture && model.page==Page.PLAYER &&
            !model.playback.audioOnly && model.playback.phase==com.tunvix.contract.PlaybackPhase.PLAYING && allowsSmallWindow()) {
            if(Build.VERSION.SDK_INT<31)enterSmallWindow()
        }
    }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean,newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig)
        model.pictureInPicture=inPictureInPictureMode
        if(inPictureInPictureMode)pictureInPictureSessionActive=true
        enteringSmallWindow=false
        model.services.playback.setBackgroundPlayback(inPictureInPictureMode)
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent?):Boolean {if(keyCode==KeyEvent.KEYCODE_VOLUME_UP||keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)model.services.playback.refreshVolume();return super.onKeyUp(keyCode,event)}
    override fun onDestroy(){
        if(isFinishing) {
            if(pictureInPictureSessionActive || model.pictureInPicture)model.closePictureInPicture()
            else model.services.playback.stop()
        }
        model.requestPictureInPicture=null;model.requestPictureInPictureSettings=null;model.requestPictureInPicturePermission=null
        if(::connectivity.isInitialized)connectivity.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("openPlayback",false)&&model.currentChannel!=null)model.page=Page.PLAYER;intent.getStringExtra("preview")?.let{if(model.services.demo)model.preview(it)}}
}
