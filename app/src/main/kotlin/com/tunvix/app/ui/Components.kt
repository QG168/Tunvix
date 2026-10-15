package com.tunvix.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.tunvix.contract.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun Label(text: String, size: Int = 14, color: Color = Design.Ink, weight: Int = 500, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: androidx.compose.ui.text.style.TextAlign? = null) {
    Text(text, modifier, color, fontSize=size.sp, fontWeight=FontWeight(weight), maxLines=maxLines, overflow=TextOverflow.Ellipsis,
        textAlign=align, style=TextStyle(lineHeight=(size*1.45f).sp, platformStyle=PlatformTextStyle(includeFontPadding=false)))
}
@Composable fun Symbol(@DrawableRes icon: Int, description: String? = null, size: Int = 20, color: Color = Design.Ink, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier.size(size.dp), tint=color)
}
@Composable fun IconButtonExact(icon: Int, description: String, onClick: () -> Unit, background: Color = Design.Surface, color: Color = Design.Ink, size: Int = 42, radius: Int = 15, iconSize: Int = 22) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(background).clickable(role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {Symbol(icon,description,iconSize,color)}
}
@Composable fun RefreshButton(description:String,refreshing:Boolean,onClick:()->Unit,
    background:Color=Design.Surface,color:Color=Design.Ink,size:Int=42,radius:Int=15,iconSize:Int=22,enabled:Boolean=true) {
    val alpha=if(enabled)1f else .4f
    val rotation=if(refreshing) {
        val transition=rememberInfiniteTransition(label="refresh")
        val angle by transition.animateFloat(0f,360f,
            infiniteRepeatable(tween(800,easing=LinearEasing),RepeatMode.Restart),label="refreshRotation")
        angle
    } else 0f
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)).background(background)
        .alpha(alpha)
        .semantics { stateDescription=if(refreshing)"正在刷新" else if(enabled)"可以刷新" else "暂时无法刷新" }
        .clickable(enabled=enabled&&!refreshing,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Symbol(Glyph.REFRESH,description,iconSize,color,Modifier.rotate(rotation))
    }
}
@Composable fun PrimaryButton(text: String, icon: Int? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction=remember {MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState();val scale by animateFloatAsState(if(pressed) .985f else 1f,label="press")
    Row(modifier.scale(scale).heightIn(min=49.dp).clip(RoundedCornerShape(16.dp)).background(if(enabled) Design.Primary else Design.Primary.copy(alpha=.5f)).clickable(interactionSource=interaction,indication=null,enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        icon?.let {Symbol(it,null,19,Design.OnPrimary);Spacer(Modifier.width(9.dp))};Label(text,15,Design.OnPrimary,720,maxLines=2,align=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable fun Header(title: String, back: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(69.dp).padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(48.dp).clickable(role=Role.Button,onClick=back),contentAlignment=Alignment.CenterStart){Symbol(Glyph.CHEVRON_LEFT,"返回",23)}
        Label(title,20,weight=800,modifier=Modifier.weight(1f),maxLines=1);trailing()
    }
}
@Composable fun PageDescription(text: String) { Label(text,13,Design.Muted,modifier=Modifier.padding(start=20.dp,end=20.dp,bottom=18.dp)) }
@Composable fun EmptyState(icon: Int, title: String, description: String, button: String? = null, action: () -> Unit = {}) {
    val illustrationBorder=Design.Border
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=40.dp,vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Box(Modifier.size(130.dp).clip(RoundedCornerShape(37.dp)).background(Design.PrimaryLight),contentAlignment=Alignment.Center){
            Canvas(Modifier.size(104.dp)) { drawRoundRect(illustrationBorder, cornerRadius=androidx.compose.ui.geometry.CornerRadius(29.dp.toPx()), style=androidx.compose.ui.graphics.drawscope.Stroke(width=2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),4.dp.toPx())))) }
            Symbol(icon,null,57,Design.Primary)
        }
        Spacer(Modifier.height(24.dp));Label(title,20,weight=800,align=androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(11.dp));Label(description,13,Design.Muted,align=androidx.compose.ui.text.style.TextAlign.Center)
        button?.let{Spacer(Modifier.height(26.dp));PrimaryButton(it,Glyph.PLUS,onClick=action)}
    }
}
private data class ChannelArtwork(val image: ImageBitmap, val isLogo: Boolean)

@Composable fun ChannelImage(channel: Channel, modifier: Modifier = Modifier, crop: Boolean = true, allowNetwork: Boolean = true, capturing: Boolean = false) {
    val context=LocalContext.current
    androidx.compose.runtime.key(channel.id) {
    val builtInLogo=BuiltInChannelLogos.uriFor(channel)
    val cachedThumbnail=cachedChannelBitmap(channel.thumbnailUri)?.asImageBitmap()
    val cachedLogo=cachedChannelBitmap(builtInLogo ?: channel.logoUrl)?.asImageBitmap()
    val artwork by produceState<ChannelArtwork?>(
        initialValue=cachedThumbnail?.let { ChannelArtwork(it,false) } ?: cachedLogo?.let { ChannelArtwork(it,true) },
        key1=channel.id,key2=channel.thumbnailUri,key3=Triple(builtInLogo,channel.logoUrl,allowNetwork)
    ) {
        value=cachedThumbnail?.let { ChannelArtwork(it,false) } ?: cachedLogo?.let { ChannelArtwork(it,true) }
        val localLogo=if(cachedThumbnail==null)ImageCandidates.firstAvailable(builtInLogo,null) { loadChannelBitmap(context,it,false)?.asImageBitmap() } else null
        if(localLogo!=null)value=ChannelArtwork(localLogo,true)
        val thumbnail=ImageCandidates.firstAvailable(channel.thumbnailUri,null) { loadChannelBitmap(context,it,allowNetwork)?.asImageBitmap() }
        if(thumbnail!=null) value=ChannelArtwork(thumbnail,false)
        else if(localLogo==null)ImageCandidates.firstAvailable(builtInLogo,channel.logoUrl) { loadChannelBitmap(context,it,allowNetwork)?.asImageBitmap() }?.let {
            value=ChannelArtwork(it,true)
        }
    }
    Box(modifier.clipToBounds().background(Brush.linearGradient(listOf(Design.SecondarySurface,Design.Background))),contentAlignment=Alignment.Center) {
        if(artwork?.isLogo == true) Box(Modifier.fillMaxSize().background(Color(0x55343A48)))
        when {
            artwork?.isLogo == true -> Image(
                artwork!!.image,
                channel.name,
                Modifier.fillMaxSize(.58f),
                contentScale=ContentScale.Fit,
            )
            artwork != null -> Image(artwork!!.image,channel.name,Modifier.fillMaxSize(),contentScale=if(crop)ContentScale.Crop else ContentScale.Fit)
            else -> {
                Symbol(Glyph.TV,null,40,Design.Muted)
                if(crop)Label(if(capturing)"正在获取" else "暂无画面",10,Design.Sub,modifier=Modifier.align(Alignment.TopEnd).padding(top=9.dp,end=8.dp).background(Design.Surface.copy(alpha=.6f),RoundedCornerShape(20.dp)).padding(horizontal=8.dp,vertical=5.dp))
            }
        }
    }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChannelCard(channel: Channel, source: String?, onClick: () -> Unit, onLongClick: () -> Unit, allowNetwork: Boolean = true, capturing: Boolean = false, frameTag: String? = null, frameColor: Color = Design.Muted) {
    Column(Modifier.fillMaxWidth().shadow(3.dp,RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).border(.5.dp,Design.Border,RoundedCornerShape(14.dp)).background(Design.Surface).combinedClickable(onClick=onClick,onLongClick=onLongClick,role=Role.Button)) {
        Box {
            ChannelImage(channel,Modifier.fillMaxWidth().aspectRatio(16f/9f),allowNetwork=allowNetwork,capturing=capturing)
            // 首帧耗时标签：半透明深色底、小圆角，仅改文字颜色，不改变卡片尺寸。
            frameTag?.let { tag ->
                Label(tag,10,frameColor,800,Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xFF10182E).copy(alpha=.55f),RoundedCornerShape(7.dp))
                    .padding(horizontal=6.dp,vertical=2.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(start=10.dp,end=10.dp,top=10.dp,bottom=11.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Label(channel.name,14,weight=700,maxLines=1)
            source?.let{Label(it,11,Design.Muted,maxLines=1)}
        }
    }
}
/** 在深色底上提高彩色数字的饱和度与可读性。 */
fun frameTierColor(tier: FrameTier): Color = when(tier) {
    FrameTier.FAST -> Color(0xFF49CF88)
    FrameTier.MEDIUM -> Color(0xFFFFC34D)
    FrameTier.SLOW -> Color(0xFFFF7777)
    FrameTier.TIMEOUT, FrameTier.FAILED -> Color(0xFFFF6868)
    FrameTier.PENDING -> Color(0xFF9AA4B8)
}
@Composable fun GroupChip(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(if(compact)11.dp else 14.dp)).background(if(selected) {if(compact)Design.PrimaryLight else Design.Primary} else if(compact)Design.SecondarySurface else Design.Surface).clickable(role=Role.Tab,onClick=onClick).padding(horizontal=if(compact)12.dp else 14.dp,vertical=if(compact)9.dp else 11.dp)) {
        Label(text,if(compact)12 else 13,if(selected&&!compact)Design.OnPrimary else if(selected)Design.Primary else Design.Sub,if(selected)700 else 600,maxLines=1)
    }
}
@Composable fun Field(label: String, value: String, placeholder: String, required: Boolean = true, multiline: Boolean = false, helper: String? = null, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom=22.dp)) {
        Row { Label(label,13,weight=750); if(required) Label(" *",13,Design.Danger) }
        Spacer(Modifier.height(10.dp))
        PlatformInput(label,value,placeholder,multiline,onChange)
        helper?.let { Spacer(Modifier.height(8.dp)); Label(it,11,Design.Muted) }
    }
}

/** Native EditorInfo/InputConnection for third-party IMEs; Compose still owns the form value. */
@Composable private fun PlatformInput(label:String,value:String,placeholder:String,multiline:Boolean,onChange:(String)->Unit) {
    var focused by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val density=androidx.compose.ui.platform.LocalDensity.current
    val padding=with(density){14.dp.roundToPx()}
    val inputColor=Design.Ink.toArgb()
    val hintColor=Design.Muted.toArgb()
    androidx.compose.ui.viewinterop.AndroidView(
        modifier=Modifier.fillMaxWidth().heightIn(min=if(multiline)100.dp else 56.dp)
            .clip(RoundedCornerShape(14.dp)).background(Design.Surface)
            .border(1.dp,if(focused)Design.Primary else Design.Border,RoundedCornerShape(14.dp)),
        factory={ context -> android.widget.EditText(context).apply {
            background=null
            setPadding(padding,padding,padding,padding)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14f)
            setTextColor(inputColor);setHintTextColor(hintColor)
            hint=placeholder;contentDescription=label
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                if(multiline)(android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
            setSingleLine(!multiline)
            minLines=if(multiline)3 else 1;maxLines=if(multiline)5 else 1
            gravity=android.view.Gravity.TOP or android.view.Gravity.START
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isFocusableInTouchMode=true;showSoftInputOnFocus=true
            setOnFocusChangeListener { _,hasFocus ->
                focused=hasFocus
                if(hasFocus)post { if(hasFocus() && hasWindowFocus()) {
                    (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(this,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } }
            }
            // A repeated tap must reopen an IME dismissed while this editor retained focus.
            setOnClickListener {
                post { if(hasFocus() && hasWindowFocus()) {
                    (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(this,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } }
            }
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                    val text=s?.toString().orEmpty()
                    if(text!=latestValue)latestChange(text)
                }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
        } },
        update={ edit ->
            edit.hint=placeholder;edit.contentDescription=label
            edit.setTextColor(inputColor);edit.setHintTextColor(hintColor)
            // Do not call setText for IME edits: it would reset selection and Chinese composing text.
            if(edit.text.toString()!=value) { edit.setText(value);edit.setSelection(edit.text.length) }
        },
    )
}

@Composable fun Hint(text: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(15.dp)).background(Design.PrimaryLight).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(11.dp)) {Symbol(Glyph.INFO,null,18,Design.Primary);Label(text,12,Design.Sub,modifier=Modifier.weight(1f))}
}
@Composable fun ToggleExact(checked: Boolean, description: String, onChange: (Boolean) -> Unit) {
    val position by animateFloatAsState(if(checked)19f else 0f,label="toggle")
    Box(Modifier.width(46.dp).height(27.dp).clip(CircleShape).background(if(checked)Design.Primary else Design.Border).toggleableCompat(checked,description,onChange)) {
        Box(Modifier.padding(start=(3+position).dp,top=3.dp).size(21.dp).clip(CircleShape).background(Color.White))
    }
}
private fun Modifier.toggleableCompat(checked: Boolean, description: String, onChange: (Boolean)->Unit) = semantics {contentDescription=description;stateDescription=if(checked)"开启" else "关闭"}.clickable(role=Role.Switch){onChange(!checked)}
