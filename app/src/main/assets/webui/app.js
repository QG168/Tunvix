'use strict';
let channels=[],channelIndex=new Map(),lineIndex=new Map();
const state={route:'home',history:[],group:'全部',playerGroup:'全部',source:'',sourceId:null,current:null,
 search:'',favorites:[],recent:[],playing:false,phase:'IDLE',audioOn:false,volume:0,theme:'system',version:'',
 sources:[],groups:['全部'],playerGroups:['全部'],customChannels:[],cGroups:[],epgSources:[],epgStations:[],
 epgMatched:'未匹配',epgDate:null,programmes:[],editSource:null,editChannel:null,epgEditId:null,
 pictureMode:'fit',playerPanel:'channels',fileName:'',onlineName:'',onlineUrl:'',cache:0,settings:{},busy:false,ready:false,controlsVisible:true};
let modal=null,toastTimeout=null,scrolls={},drafts={},groupScrolls={},geometryPending=false,visiblePending=false;
let lastNativeOverlay=null,lastNativeToast=null;
let pendingProgrammeLocation=false,pendingPlayerLocation=false,lastVisibleKey=null;
let dimTimer=null,dimEligible=false;const dimPointers=new Set();
let pendingFavoriteId=null,refreshAnimation=null,refreshIcon=null,refreshAnimated=false;
let lastChipKey=null,lastRenderedRoute=null,lastFrameKey=null,lastMaskKey=null;
const $=id=>document.getElementById(id);
const knownRoutes=new Set(['home','favorites','recent','search','player','fullscreen','epg','epg-match','audio',
 'pip-info','permissions','sources','source-detail','source-add','source-online','source-file','import-result',
 'source-manual','channel-edit','group-edit','epg-sources','epg-edit','settings','appearance','about','playback-error']);
window.receiveSafeArea=function(area){
 for(const side of ['top','bottom','left','right'])document.documentElement.style.setProperty(`--safe-${side}`,`${Math.max(0,Number(area[side])||0)}px`);
 requestGeometry();requestVisible();
};
function post(action,data={}){if(window.Tunvix)window.Tunvix.postMessage(JSON.stringify({action,data}));}
function chById(id){return channelIndex.get(id)||null;}
function channelImage(c){return c?.preview||'';}
function channelLogo(c){return c?.logo||'';}
function currentChannels(){return channels.filter(c=>c.sourceId===state.sourceId);}
function lineKey(c){return JSON.stringify([c.sourceId,c.group,c.nameKey]);}
function sameStation(a,b){return !!a&&!!b&&(a.id===b.id||state.settings.mergeChannels&&lineKey(a)===lineKey(b));}
function displayChannels(list){
 if(!state.settings.mergeChannels)return list;
 const groups=new Map();for(const c of list){const key=lineKey(c);const previous=groups.get(key);if(!previous||Number.isFinite(c.lineMs)&&(!Number.isFinite(previous.lineMs)||c.lineMs<previous.lineMs))groups.set(key,c);}
 return [...groups.values()];
}
function currentLines(){const c=chById(state.current);return c?(lineIndex.get(lineKey(c))||[]):[];}
function showLines(){
 const lines=currentLines();if(!lines.length)return;
 modal=`<div class="overlay"><div class="sheet"><div class="sheet-handle"></div><h3 class="sheet-title">切换线路</h3>${lines.map((c,i)=>`<button class="sheet-action" data-act="select-line" data-id="${esc(c.id)}">${I(c.id===state.current?'check':'radio')}<span>线路 ${i+1}</span><span class="line-time">${Number.isFinite(c.lineMs)?`${c.lineMs}ms`:esc(c.msLabel)}</span>${c.id===state.current?'<span class="tiny-pill">当前</span>':''}</button>`).join('')}<button class="btn secondary block" data-act="close-lines">关闭</button></div></div>`;
 syncMarkup($('popups'),modal);
}
function locatePlayer(){
 if(!pendingPlayerLocation||state.route!=='player'||state.playerPanel!=='channels')return;
 const container=$('player-stations'),row=container?.querySelector('.station-row.playing');if(!row)return;
 container.scrollTop+=row.getBoundingClientRect().top-container.getBoundingClientRect().top-(container.clientHeight-row.clientHeight)/2;
 pendingPlayerLocation=false;
}
function locateProgramme(){
 if(!pendingProgrammeLocation||state.playerPanel!=='programmes')return;
 const container=$('player-programmes'),row=container?.querySelector('.program-row.now');
 if(!row)return;
 container.scrollTop+=row.getBoundingClientRect().top-container.getBoundingClientRect().top-(container.clientHeight-row.clientHeight)/2;
 pendingProgrammeLocation=false;
}
function setPlayerPanel(panel,fromSwipe=false){
 if(!['channels','programmes','remote'].includes(panel))return;
 state.playerPanel=panel;
 document.querySelectorAll('[data-act="player-panel"]').forEach(e=>{e.classList.toggle('active',e.dataset.val===panel);e.setAttribute('aria-selected',String(e.dataset.val===panel));});
 const deck=$('player-deck');if(deck&&!fromSwipe)deck.scrollTo({left:deck.clientWidth*['channels','programmes','remote'].indexOf(panel),behavior:'smooth'});
 if(panel==='programmes'){pendingProgrammeLocation=true;const programmes=$('player-programmes');if(programmes&&!programmes.firstChild)syncMarkup(programmes,epg(true));post('open-player-epg');requestAnimationFrame(locateProgramme);}
 if(panel==='channels')requestAnimationFrame(locatePlayer);
 post('draft',{draft:savedState()});
}
function playerChannels(){const c=chById(state.current);return c?channels.filter(x=>x.sourceId===c.sourceId):[];}
function scrollKey(){return `${state.route}:${state.sourceId||''}:${state.route==='player'?state.playerGroup:state.group}:${state.search}`;}
function formKey(){return `${state.route}:${state.editSource||''}:${state.editChannel||''}:${state.epgEditId||''}`;}
function rememberDraft(){
 const root=$('viewport');if(!root)return;
 scrolls[scrollKey()]=root.scrollTop;
 const form={};root.querySelectorAll('input[id]:not([type=file]):not([type=range]),textarea[id],select[id]').forEach(e=>form[e.id]=e.value);
 if(Object.keys(form).length)drafts[formKey()]=form;
 root.querySelectorAll('.player-panel-scroll').forEach(e=>scrolls[`panel:${e.id}`]=e.scrollTop);
 root.querySelectorAll('.chips,.player-group,.day-strip').forEach((e,i)=>groupScrolls[`${scrollKey()}:${i}`]=e.scrollLeft);
}
function savedState(){rememberDraft();return {history:state.history,group:state.group,playerGroup:state.playerGroup,search:state.search,
 playerPanel:state.playerPanel,editSource:state.editSource,editChannel:state.editChannel,epgEditId:state.epgEditId,epgDate:state.epgDate,scrolls,drafts,groupScrolls};}
function changedRoute(){post('route',{route:state.route,draft:savedState()});}
function go(route){
 if(!knownRoutes.has(route))return;
 rememberDraft();if(route!==state.route)state.history.push(state.route);
 if(route==='epg-edit'&&state.route!=='epg-sources')state.epgEditId=null;
 state.route=route;modal=null;render();changedRoute();
}
function back(){rememberDraft();state.route=state.history.pop()||'home';modal=null;render();changedRoute();}
window.nativeBack=()=>{if(modal){post('dismiss-overlay');modal=null;render(true);}else back();};
function notify(text){clearTimeout(toastTimeout);$('toastbox').innerHTML=`<div class="toast">${esc(text)}</div>`;toastTimeout=setTimeout(()=>$('toastbox').innerHTML='',2400);}
function card(c){
 const tag=state.settings.latency?`<span class="latency" style="color:${c.msColor}">${esc(c.msLabel)}</span>`:'';
 return `<button class="channel-card" data-act="play" data-id="${esc(c.id)}"><div class="preview-wrap ${c.hasPreview?'':'fallback'}">${c.preview?`<img class="channel-preview" loading="lazy" decoding="async" src="${esc(c.preview)}" alt="${esc(c.name)}频道预览"/>`:`<span class="setting-icon">${I('tv')}</span>`}${tag}</div><div class="channel-label">${c.logo?`<img class="channel-logo ${esc(c.logoClass||'')}" loading="lazy" decoding="async" src="${esc(c.logo)}" alt="${esc(c.name)}台标"/>`:`<span class="setting-icon">${I('tv')}</span>`}<span class="channel-name">${esc(c.name)}</span><span class="channel-arrow">${I('chevron-right')}</span></div></button>`;
}
function grid(list){return list.length?`<div class="channel-grid">${list.map(card).join('')}</div>`:empty('tv','这里还没有频道','添加直播源之后，就能在这里浏览电视台。','添加直播源','go-source-add');}
function nav(){const selected=state.route==='favorites'?'favorites':state.route==='recent'?'recent':
 ['settings','sources','source-detail','source-add','source-online','source-file','import-result','source-manual','channel-edit','group-edit','epg-sources','epg-edit','appearance','about','permissions','pip-info'].includes(state.route)?'settings':'home';
 return `<nav class="nav" aria-label="底部导航">${[['home','tv','频道'],['favorites','heart','收藏'],['recent','clock','最近'],['settings','settings','设置']].map(([route,icon,label])=>`<button class="nav-item ${selected===route?'active':''}" data-go="${route}">${I(selected===route?`${['tv','settings'].includes(icon)?`nav-${icon}`:icon}-solid`:['tv','settings'].includes(icon)?`nav-${icon}`:icon)}<span>${label}</span></button>`).join('')}</nav>`;
}
function formatDate(time){return new Date(time).toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'});}
function dayKey(time){const d=new Date(time);return `${d.getFullYear()}-${d.getMonth()+1}-${d.getDate()}`;}
function clockText(time){return new Date(time).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});}
function epg(embedded=false){const c=chById(state.current);if(!c)return missingPlayer();
 const today=dayKey(Date.now()),dates=[...new Set([today,...state.programmes.map(p=>dayKey(p.start))])];
 if(!dates.includes(state.epgDate))state.epgDate=today;
 const programmes=state.programmes.filter(p=>dayKey(p.start)===state.epgDate),now=Date.now();
 return `<main class="${embedded?'player-programme-body':'page'}">${embedded?'':heading('电视节目单',`<button class="round-icon" data-act="refresh-epg" ${state.epgRefreshing?'disabled':''}>${I('refresh-cw')}</button>`)}<div class="glass-card row-between epg-match-summary"><div class="row-left">${c.logo||c.preview?`<img src="${esc(c.logo||c.preview)}" style="width:61px;height:42px;object-fit:contain;border-radius:10px"/>`:I('tv')}<div><div class="row-title">${esc(c.name)}</div><p class="row-sub">当前匹配 · ${esc(state.epgMatched)}</p></div></div><button class="btn secondary small" data-go="epg-match">更换匹配</button></div><div class="day-strip">${dates.map(key=>`<button class="day-item ${key===state.epgDate?'active':''}" data-act="epg-day" data-val="${esc(key)}"><small>${key===today?'今天':''}</small><strong>${esc(key.split('-').slice(1).join('/'))}</strong></button>`).join('')}</div>${section('全天节目',null,`来源 · ${esc(state.epgSourceName||'暂无')}`)}${programmes.length?`<div class="glass-card" style="padding:4px 14px">${programmes.map(p=>{const status=now>=p.end?'已播出':now>=p.start?'正在播出':'即将播出';return `<div class="program-row ${status==='正在播出'?'now':''}"><div class="program-time">${clockText(p.start)}<div style="font-size:9px;font-weight:400;margin-top:4px">${clockText(p.end)}</div></div><div class="program-text"><strong>${esc(p.title)}</strong><small>${esc(c.name)} · 电视节目</small></div><span class="program-state" style="${status==='已播出'?'background:#f0f3f7;color:#a3aec0':''}">${status}</span></div>`;}).join('')}</div>`:empty('calendar',state.epgRefreshing?'正在获取节目单':'暂无节目单','此频道或日期尚未收录，可更换匹配或添加其他订阅。') }</main>`;
}
function matchList(query){const q=query.trim().toLowerCase();const list=state.epgStations.filter(s=>!q||s.names.some(n=>n.toLowerCase().includes(q)));
 return list.length?list.map(s=>`<label class="match-row"><span class="setting-icon">${I('tv')}</span><div><strong>${esc(s.names[0]||s.id)}</strong><small>${esc(s.sourceName)} · XMLTV</small></div><input type="radio" name="match" data-source="${esc(s.sourceId)}" value="${esc(s.id)}" ${state.matchSource===s.sourceId&&state.matchId===s.id?'checked':''}></label>`).join(''):empty('search-x','没有匹配结果','可以使用更短的关键词重新搜索。');
}
function searchResults(){const q=state.search.trim().toLowerCase();const results=channels.filter(c=>q&&[c.name,c.detail,c.source,c.group].join(' ').toLowerCase().includes(q));
 return q?`<div class="result-head"><span>搜索“${esc(state.search)}”</span><span>${results.length} 个结果</span></div>${results.length?grid(displayChannels(results)):empty('search-x','未找到相关频道','试试输入频道名称中的其他关键词。')}`:
 `${section('热门搜索',null,'快速找到常看的电视台')}<div class="chips">${[...new Set([...state.favorites,...state.recent].map(chById).filter(Boolean).concat(currentChannels()).map(c=>c.name))].slice(0,4).map(name=>`<button class="chip" data-act="hot-search" data-val="${esc(name)}">${esc(name)}</button>`).join('')}</div>${section('推荐频道')}${grid([...new Set([...state.favorites,...state.recent,...currentChannels().map(c=>c.id)])].map(chById).filter(Boolean).slice(0,4))}`;
}
function epgEdit(){const e=state.epgSources.find(s=>s.id===state.epgEditId);return `<main class="page">${heading(e?'编辑 EPG 订阅':'添加 EPG 订阅')}<div class="glass-card"><div class="row-left"><span class="type-icon purple">${I('calendar')}</span><div><div class="row-title">${e?'修改节目单来源':'添加 XMLTV 节目单'}</div><p class="row-sub">自定义来源优先，内置来源补缺</p></div></div></div><label class="field-label">订阅名称</label><input class="field" id="epg-name" value="${esc(e?.name||'')}" placeholder="例如：全国节目单"/><label class="field-label">EPG 订阅地址</label><textarea class="field textarea" id="epg-url" placeholder="https://example.com/epg.xml.gz">${esc(e?.url||'')}</textarea><div class="info-tip">${I('shield-check')}可添加 XML 或 XML.GZ 格式。节目内容由订阅来源提供。</div><button class="btn primary block" data-act="save-epg">${I('check','sm')} ${e?'保存修改':'添加订阅'}</button></main>`;}
function missingPlayer(){return `<main class="page">${heading(state.channelUnavailable?'频道不可用':'请选择频道')}${empty('tv',state.channelUnavailable?'频道不可用':'尚未选择频道',state.channelUnavailable?'此频道已被移除，返回频道列表继续浏览。':'先从频道列表选择电视台，再使用播放、节目单或听音功能。','浏览频道','go-home')}</main>`;}
const routeViews={home,favorites,recent,search:searchPage,player,epg,'epg-match':epgMatch,audio,'pip-info':pipInfo,permissions,
 sources,'source-detail':sourceDetail,'source-add':sourceAdd,'source-online':sourceOnline,'source-file':sourceFile,
 'import-result':importResult,'source-manual':()=>sourceManual(false),'channel-edit':()=>sourceManual(true),
 'group-edit':groupEdit,'epg-sources':epgSources,'epg-edit':epgEdit,settings,appearance,about,'playback-error':playbackError};
// Keep images, scroll containers and the video overlay alive during data updates.
function syncMarkup(root,markup,replace=false){
 if(replace){root.innerHTML=markup;return;}
 const template=document.createElement('template');template.innerHTML=markup;
 function sync(parent,next){
  const wanted=[...next.childNodes];
  wanted.forEach((fresh,i)=>{
   let old=parent.childNodes[i];
   const key=fresh.nodeType===1&&fresh.dataset.id;
   if(key&&(!old||old.nodeType!==1||old.dataset.id!==key)){
    const found=[...parent.children].find(n=>n.dataset.id===key&&n.tagName===fresh.tagName);
    if(found){parent.insertBefore(found,old||null);old=found;}
   }
   if(!old){parent.appendChild(fresh.cloneNode(true));return;}
   if(old.nodeType!==fresh.nodeType||old.nodeName!==fresh.nodeName){parent.replaceChild(fresh.cloneNode(true),old);return;}
   if(fresh.nodeType!==1){if(old.nodeValue!==fresh.nodeValue)old.nodeValue=fresh.nodeValue;return;}
   [...old.attributes].forEach(a=>{if(!fresh.hasAttribute(a.name))old.removeAttribute(a.name);});
   [...fresh.attributes].forEach(a=>{if(old.getAttribute(a.name)!==a.value)old.setAttribute(a.name,a.value);});
   sync(old,fresh);
  });
  while(parent.childNodes.length>wanted.length)parent.lastChild.remove();
 }
 sync(root,template.content);
 if(root.id==='popups')updateViewingDimmer();
}
function canDim(){return state.route==='player'&&!!state.settings.softViewing&&state.playing&&state.frameReady&&state.foreground&&!state.pictureInPicture&&!state.audioOn&&!modal&&!document.hidden;}
function updateViewingDimmer(activity=false){
 const layer=$('viewing-dimmer');if(!layer)return;
 const eligible=canDim();
 if(!eligible||activity||dimPointers.size){clearTimeout(dimTimer);dimTimer=null;layer.classList.remove('dimmed');}
 if(eligible&&!dimPointers.size&&(activity||!dimEligible)){
  clearTimeout(dimTimer);dimTimer=setTimeout(()=>{dimTimer=null;if(canDim()&&!dimPointers.size)layer.classList.add('dimmed');},5000);
 }
 dimEligible=eligible;
}
function showPictureModes(){
 modal=`<div class="overlay"><div class="sheet"><div class="sheet-handle"></div><h3 class="sheet-title">画面显示</h3>${[['fit','适应'],['fill','填充'],['stretch','拉伸']].map(([id,name])=>`<button class="sheet-action" data-act="set-picture-mode" data-val="${id}"><span>${name}</span>${state.pictureMode===id?I('check','panel-selected'):''}</button>`).join('')}<button class="btn secondary block" data-act="close-lines">取消</button></div></div>`;
 syncMarkup($('popups'),modal);
}
function updateRefreshAnimation(){
 const button=document.querySelector('.top-tv'),icon=button?.querySelector('.ico');
 if(button)button.setAttribute('aria-busy',String(!!state.screenshotRefreshing));
 if(!state.screenshotRefreshing){refreshAnimation?.cancel();refreshAnimation=null;refreshIcon=null;refreshAnimated=false;return;}
 if(!icon||refreshAnimated||window.matchMedia('(prefers-reduced-motion:reduce)').matches)return;
 refreshAnimated=true;refreshIcon=icon;
 const spin=icon.animate([{transform:'rotate(0deg)'},{transform:'rotate(1440deg)'}],{duration:3200,easing:'cubic-bezier(.12,.65,.3,1)'});
 refreshAnimation=spin;
 spin.onfinish=()=>{if(refreshAnimation===spin){spin.cancel();refreshAnimation=null;}};
}

function updatePlaybackControls(){
 updateViewingDimmer();
 updateRefreshAnimation();
 const ratio=Number(state.videoRatio);document.documentElement.style.setProperty('--video-ratio',Number.isFinite(ratio)&&ratio>0?String(ratio):String(16/9));
 document.querySelectorAll('.video-shell,.full-viewport').forEach(e=>e.classList.toggle('hide-controls',!state.controlsVisible));
 document.querySelectorAll('[data-act="toggle-play"],[data-act="audio-play"]').forEach(e=>{
  const icon=e.querySelector('use');if(icon)icon.setAttribute('href',`#i-${state.playing?'pause':'play'}`);
 });
 const caption=document.querySelector('.video-caption p');
 if(caption)caption.textContent=state.phase==='CONNECTING'?'正在连接':state.showPlayingError?(currentLines().length>1?'播放失败 · 请切换线路':'播放失败 · 请换台或刷新'):state.playing?'直播中':'已暂停';
 const timer=$('sleep-countdown');if(timer)timer.textContent=state.sleepTimerOn?`剩余 ${Math.floor(state.sleepRemaining/60)}:${String(state.sleepRemaining%60).padStart(2,'0')}`:'未开启';
 document.querySelectorAll('[data-act="sleep-timer"]').forEach(e=>e.classList.toggle('active',Number(e.dataset.val)===(state.sleepTimerOn?state.sleepMinutes:0)));
 document.querySelector('.sound-bars')?.classList.toggle('paused',!state.playing);
 for(const id of ['volume-range','full-volume','remote-volume']){const e=$(id);if(e&&e.id!==volumeDragging&&Number(e.value)!==state.volume)e.value=state.volume;}
 const audioStatus=document.querySelector('.audio-cover~p.subtle');if(audioStatus)audioStatus.textContent=state.playing?'正在播放音频':state.phase==='IDLE'?'已停止':'已暂停';
 document.querySelectorAll('.volume-count').forEach(count=>count.textContent=`${state.volume}%`);
 const brightnessInput=$('remote-brightness');if(brightnessInput&&volumeDragging!=='remote-brightness')brightnessInput.value=state.brightness??50;
 const brightnessBar=document.querySelector('.remote-brightness');if(brightnessBar)brightnessBar.style.setProperty('--brightness',`${volumeDragging==='remote-brightness'?brightnessInput.value:state.brightness??50}%`);
 document.querySelectorAll('.brightness-count').forEach(e=>e.textContent=`${state.brightness??50}%`);
 const bar=document.querySelector('.remote-volume');if(bar)bar.style.setProperty('--volume',`${volumeDragging==='remote-volume'?$('remote-volume').value:state.volume}%`);
 const volume=document.querySelector('.full-bottom>p');if(volume)volume.textContent=`音量 ${state.volume}%`;
 requestGeometry();
}
function patchChannelImages(){
 document.querySelectorAll('[data-act="play"][data-id]').forEach(node=>{
  const c=chById(node.dataset.id);if(!c)return;
  const image=node.querySelector('.channel-preview')||node.querySelector('img');
  if(image&&image.getAttribute('src')!==c.preview)image.src=c.preview;
  node.querySelector('.preview-wrap')?.classList.toggle('fallback',!c.hasPreview);
  const logo=node.querySelector('.channel-logo');if(logo&&logo.getAttribute('src')!==c.logo)logo.src=c.logo;
  const latency=node.querySelector('.latency');if(latency){if(latency.textContent!==c.msLabel)latency.textContent=c.msLabel;latency.style.color=c.msColor;}
 });
 const c=chById(state.current);
 for(const image of document.querySelectorAll('.video-shell>img,.full-image'))if(c&&image.getAttribute('src')!==c.preview)image.src=c.preview;
 if(c){
  const audioLogo=document.querySelector('.audio-cover img');if(audioLogo&&audioLogo.getAttribute('src')!==c.logo)audioLogo.src=c.logo;
  if(state.route==='epg'){const epgLogo=document.querySelector('.page>.glass-card.row-between img');const src=c.logo||c.preview;if(epgLogo&&epgLogo.getAttribute('src')!==src)epgLogo.src=src;}
 }
}
function startChannel(id){
 const c=chById(id);if(!c)return;
 if(['player','fullscreen','audio'].includes(state.route)&&sameStation(c,chById(state.current)))return;
 const group=state.route==='home'?state.group:['player','fullscreen','audio'].includes(state.route)?state.playerGroup:c.group;
 const fromHome=state.route==='home';if(!['player','fullscreen','audio'].includes(state.route)){rememberDraft();state.playerGroup=group;state.playerPanel=state.settings.defaultPlayerPanel||'channels';if(state.playerPanel==='programmes'){state.epgDate=dayKey(Date.now());pendingProgrammeLocation=true;}}
 pendingPlayerLocation=true;post('play',{id,sourceId:c.sourceId,group,fromHome,draft:savedState()});
}
function render(preserve=false){
 const view=$('viewport');if(!view)return;
 let focused=document.activeElement?.id,selection=document.activeElement?.selectionStart;
 if(preserve)rememberDraft();
 $('device').dataset.theme=state.dark?'dark':'light';
 $('device').dataset.route=state.route;
 if(state.route!=='fullscreen'&&state.pictureMode!=='fit'){state.pictureMode='fit';post('picture-mode',{mode:'fit'});}
 if(state.route==='source-detail'&&!state.sources.some(s=>s.id===state.editSource))state.route='sources';
 const replace=lastRenderedRoute!==state.route;
 let markup;
 if(!state.ready)markup=`<main class="page"><div class="empty">${I('refresh-cw','xl')}<h3>正在读取频道</h3></div></main>`;
 else if(state.catalogError)markup=`<main class="page">${empty('alert-circle','本地数据暂时无法读取',esc(state.catalogError))}</main>`;
 else if(['player','fullscreen','audio','epg','epg-match','playback-error'].includes(state.route)&&!chById(state.current))markup=missingPlayer();
 else markup=state.route==='fullscreen'?'':(routeViews[state.route]||home)();
 syncMarkup(view,markup,replace);
 syncMarkup($('bottomnav'),state.route==='fullscreen'?'':nav());
 syncMarkup($('popups'),modal||'');
 syncMarkup($('fullscreen-overlay'),state.route==='fullscreen'&&chById(state.current)?fullscreenHtml():'',replace);
 view.scrollTop=scrolls[scrollKey()]||0;
 if(replace)view.querySelectorAll('.chips,.player-group,.day-strip').forEach((e,i)=>e.scrollLeft=groupScrolls[`${scrollKey()}:${i}`]||0);
 const chipKey=`${state.route}:${state.sourceId}:${state.route==='player'?state.playerGroup:state.group}`;
 if(chipKey!==lastChipKey&&['home','player'].includes(state.route)){
  const active=view.querySelector('.chips .chip.active,.player-group .chip.active');
  if(active){const parent=active.parentElement,a=active.getBoundingClientRect(),p=parent.getBoundingClientRect();parent.scrollLeft+=a.left-p.left-(p.width-a.width)/2;}
 }
 lastChipKey=chipKey;
 const form=drafts[formKey()]||{};Object.keys(form).forEach(id=>{const e=$(id);if(e&&e.type!=='file'&&id!=='default-player-panel')e.value=form[id];});
 if(state.fileName){const label=$('upload-file-name');if(label)label.textContent=state.fileName;}
 if(state.busy)view.querySelectorAll('[data-act="parse-online"],[data-act="parse-file"],[data-act="confirm-import"],[data-act="save-source"],[data-act="save-channel"]').forEach(e=>e.disabled=true);
 view.querySelectorAll('input[type="file"]').forEach(e=>e.onclick=event=>{event.preventDefault();post('pick-file',{replace:e.id==='replace-file',id:state.editSource});});
 view.querySelectorAll('.video-shell').forEach(e=>e.classList.toggle('hide-controls',!state.controlsVisible));
 $('fullscreen-overlay').querySelector('.full-viewport')?.classList.toggle('hide-controls',!state.controlsVisible);
 view.querySelector('.sound-bars')?.classList.toggle('paused',!state.playing);
 if(focused){const e=$(focused);if(e&&e.type!=='file'){e.focus({preventScroll:true});if(typeof selection==='number'&&e.setSelectionRange&&e.type!=='radio')try{e.setSelectionRange(selection,selection);}catch(_){}}}
 view.classList.toggle('player-mode',state.route==='player');
 const deck=$('player-deck');if(deck){deck.scrollLeft=deck.clientWidth*['channels','programmes','remote'].indexOf(state.playerPanel);deck.querySelectorAll('.player-panel-scroll').forEach(e=>e.scrollTop=scrolls[`panel:${e.id}`]||0);}
 lastRenderedRoute=state.route;
 if(state.route==='player'){requestAnimationFrame(locateProgramme);requestAnimationFrame(locatePlayer);if(replace&&state.playerPanel==='programmes')post('open-player-epg');}
 updatePlaybackControls();requestVisible();
}
function fullscreenHtml(){const c=chById(state.current);return `<div class="full-viewport"><img class="full-image" src="${esc(c.preview)}"/><div class="full-top"><span class="full-title">${esc(c.name)} · LIVE</span><div class="full-tools"><button class="btn full-tool" data-act="picture-modes" aria-label="画面显示方式">${I('monitor','sm')}<span>画面</span></button><button class="btn full-tool" data-act="fullscreen-pip" aria-label="小窗播放">${I('picture-in-picture','sm')}<span>小窗</span></button><button class="btn full-tool" data-act="fullscreen-audio" aria-label="听音模式">${I('headphones','sm')}<span>听音</span></button><button class="btn full-tool" data-act="exit-fullscreen" aria-label="转为竖屏播放">${I('portrait','sm')}<span>转竖屏</span></button></div></div><div class="full-controls"><button data-act="prev-channel">${I('skip-back')}</button><button data-act="toggle-play" class="main">${I(state.playing?'pause':'play','xl')}</button><button data-act="next-channel">${I('skip-forward')}</button></div><div class="full-bottom"><p>音量 ${state.volume}%</p><button class="player-act" data-act="volume-down">${I('volume-1')}</button><input id="full-volume" type="range" value="${state.volume}" min="0" max="100"><button class="player-act" data-act="volume-up">${I('volume-2')}</button></div></div>`;}
function fullscreenModes(){
 modal=`<div class="overlay"><div class="sheet"><div class="sheet-handle"></div><h3 class="sheet-title">播放模式</h3><button class="sheet-action" data-act="fullscreen-audio">${I('headphones')}<span>听音模式</span></button><button class="sheet-action" data-act="fullscreen-pip">${I('picture-in-picture')}<span>小窗播放</span></button><button class="btn secondary block" data-act="close-lines">取消</button></div></div>`;
 syncMarkup($('popups'),modal);
}

function nativeModal(o){if(!o)return null;return `<div class="overlay confirm-overlay"><div class="confirm-box"><h3>${esc(o.title)}</h3><p>${esc(o.message)}</p><div class="button-row"><button class="btn secondary" data-act="dismiss-overlay">${o.error?'返回':'取消'}</button><button class="btn primary" data-act="accept-overlay">${esc(o.button||'确认')}</button></div></div></div>`;}
window.receiveNative=function(data){
 const initializing=!state.ready;let restoredPanel=false;
 const changed=Object.keys(data).filter(k=>JSON.stringify(state[k])!==JSON.stringify(data[k]));
 if(data.route!==undefined&&data.route!==state.route&&knownRoutes.has(data.route)&&state.ready)state.history.push(state.route);
 if(data.route!==undefined&&data.route!==state.route||data.sourceId!==undefined&&data.sourceId!==state.sourceId)rememberDraft();
 if(!state.ready&&data.draft){try{const saved=JSON.parse(data.draft);restoredPanel=['channels','programmes','remote'].includes(saved.playerPanel);Object.assign(state,saved);scrolls=saved.scrolls||{};drafts=saved.drafts||{};groupScrolls=saved.groupScrolls||{};}catch(_){}}
 if(data.current!==undefined&&data.current!==state.current)pendingPlayerLocation=true;
 if(data.current!==undefined&&data.current!==state.current&&state.playerPanel==='programmes'){pendingProgrammeLocation=true;state.epgDate=dayKey(Date.now());}
 const oldSource=state.sourceId;
 const oldRepresentatives=data.channels&&state.settings.mergeChannels?displayChannels(channels).map(c=>c.id).join('|'):null;
 const structural=['id','name','nameKey','sourceId','source','group','detail','custom','url','logoClass'];
 let imageOnly=!!data.channels&&data.channels.length===channels.length&&data.channels.every((c,i)=>{
  const old=channels[i];return structural.every(k=>c[k]===old[k])&&!!c.logo===!!old.logo;
 });
 if(data.channels){channels=data.channels;channelIndex=new Map(channels.map(c=>[c.id,c]));lineIndex=new Map();for(const c of channels){const key=lineKey(c);if(!lineIndex.has(key))lineIndex.set(key,[]);lineIndex.get(key).push(c);}}
 Object.assign(state,data);
 if(initializing&&!restoredPanel||!['channels','programmes','remote'].includes(state.playerPanel))state.playerPanel=state.settings.defaultPlayerPanel||'channels';
 if(data.channels&&oldRepresentatives!==null&&oldRepresentatives!==displayChannels(channels).map(c=>c.id).join('|'))imageOnly=false;
 if(data.channels||data.favorites)state.favorites=state.favorites.filter(chById);
 if(data.channels||data.recent)state.recent=state.recent.filter(chById);
 if(oldSource!==state.sourceId)state.group=state.restoredGroup||'全部';
 if(data.channels)state.customChannels=channels.filter(c=>c.custom);
 if(state.editChannel&&!state.customChannels.some(c=>c.id===state.editChannel))state.editChannel=null;
 if(!knownRoutes.has(state.route))state.route='home';
 if(!state.groups.includes(state.group))state.group=state.restoredGroup||'全部';
 if(!state.playerGroups.includes(state.playerGroup))state.playerGroup=chById(state.current)?.group||'全部';
 if(state.route==='import-result'&&state.report){state.importResult={...state.report,name:state.formName,type:state.importType};}
 if(Object.prototype.hasOwnProperty.call(data,'overlay')){const overlayKey=JSON.stringify(data.overlay);if(overlayKey!==lastNativeOverlay){modal=nativeModal(data.overlay);lastNativeOverlay=overlayKey;}}
 const controls=new Set(['draft','controlsVisible','playing','phase','volume','frameReady','toast','overlay','playerGroup','sleepRemaining','sleepTimerOn','sleepMinutes','videoRatio','brightness','foreground','pictureInPicture']);
 const playbackOnly=new Set(['current','audioOn','channelUnavailable','showPlayingError','playbackError','playerGroups','programmes','epgMatched','matchId','matchSource','epgSourceName']);
 const relevant=changed.some(k=>!(k==='channels'&&imageOnly&&state.route!=='recent')&&!controls.has(k)&&(!playbackOnly.has(k)||['player','fullscreen','audio','epg','epg-match','playback-error'].includes(state.route)));
 if(lastRenderedRoute!==state.route||relevant||changed.includes('playerGroup')&&state.route==='player')render(true);
 else {if(imageOnly)patchChannelImages();syncMarkup($('popups'),modal||'');updatePlaybackControls();}
 if(modal&&modal.includes('data-act="select-line"')&&(data.channels||data.current!==undefined))showLines();
 if(pendingFavoriteId&&data.favorites){
  if(state.favorites.includes(pendingFavoriteId))document.querySelectorAll('[data-act="toggle-fav"][data-id]').forEach(e=>{if(e.dataset.id===pendingFavoriteId){e.classList.remove('favorite-pop');void e.offsetWidth;e.classList.add('favorite-pop');}});
  pendingFavoriteId=null;
 }
 if(data.overlay?.error)pendingFavoriteId=null;
 if(Object.prototype.hasOwnProperty.call(data,'toast')){if(data.toast&&data.toast!==lastNativeToast)notify(data.toast);lastNativeToast=data.toast;}
};
function requestVisible(){if(visiblePending)return;visiblePending=true;requestAnimationFrame(()=>{
 visiblePending=false;if(!['home','favorites','recent','search'].includes(state.route)){if(lastVisibleKey!=='[]'){lastVisibleKey='[]';post('visible',{ids:[]});}return;}
 const ids=[];$('viewport').querySelectorAll('[data-act="play"][data-id]').forEach(e=>{const r=e.getBoundingClientRect();if(r.bottom>-160&&r.top<innerHeight+160){const c=chById(e.dataset.id);if(c&&state.settings.mergeChannels)ids.push(...(lineIndex.get(lineKey(c))||[]).map(x=>x.id));else ids.push(e.dataset.id);}});const unique=[...new Set(ids)],key=JSON.stringify(unique);if(key!==lastVisibleKey){lastVisibleKey=key;post('visible',{ids:unique});}
});}
function requestGeometry(){if(geometryPending)return;geometryPending=true;requestAnimationFrame(()=>{
 geometryPending=false;const e=state.route==='fullscreen'?document.querySelector('.full-viewport'):state.route==='player'?document.querySelector('.video-shell'):null;
 const r=e?.getBoundingClientRect(),usable=!!r&&r.width>0&&r.height>0&&!state.audioOn;
 const frame=usable?{x:r.left,y:r.top,w:r.width,h:r.height,radius:state.route==='fullscreen'?0:24,viewportWidth:innerWidth,route:state.route}:null;
 const frameKey=JSON.stringify(frame)+`:${state.phase==='IDLE'}`;if(frameKey!==lastFrameKey){post('video-frame',{frame});lastFrameKey=frameKey;}
 const active=usable&&state.frameReady;
 e?.classList.toggle('native-video',!!active);
 const backdrop=document.querySelector('.native-backdrop'),ambient=document.querySelector('.ambient'),dimmer=$('viewing-dimmer');
 const maskKey=active?`${frameKey}:${innerWidth}:${innerHeight}`:'none';
 if(maskKey===lastMaskKey)return;lastMaskKey=maskKey;
 let mask='none';if(active){const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="${innerWidth}" height="${innerHeight}"><defs><mask id="video-hole" maskUnits="userSpaceOnUse" x="0" y="0" width="${innerWidth}" height="${innerHeight}"><rect width="100%" height="100%" fill="white"/><rect x="${r.left}" y="${r.top}" width="${r.width}" height="${r.height}" rx="${frame.radius}" fill="black"/></mask></defs><rect width="100%" height="100%" fill="white" mask="url(#video-hole)"/></svg>`;mask=`url("data:image/svg+xml;base64,${btoa(svg)}")`;}
 [backdrop,ambient,dimmer].filter(Boolean).forEach(layer=>{layer.style.webkitMaskImage=mask;layer.style.maskImage=mask;});
});}
function values(){rememberDraft();const result={};$('viewport').querySelectorAll('input[id]:not([type=file]),textarea[id],select[id]').forEach(e=>result[e.id]=e.value);return result;}
function action(act,d){
 switch(act){
 case 'play':startChannel(d.id);return;
 case 'picture-modes':showPictureModes();return;
 case 'set-picture-mode':if(['fit','fill','stretch'].includes(d.val)){state.pictureMode=d.val;modal=null;syncMarkup($('popups'),'');post('picture-mode',{mode:d.val});}return;
 case 'toggle-fav':pendingFavoriteId=state.favorites.includes(d.id)?null:d.id;post('toggle-fav',{id:d.id});return;
 case 'choose-player-panel':modal=`<div class="overlay"><div class="sheet"><div class="sheet-handle"></div><h3 class="sheet-title">进入播放页默认显示</h3>${[['channels','频道列表','tv'],['programmes','节目单','calendar'],['remote','遥控器','layers']].map(([id,name,icon])=>`<button class="sheet-action" data-act="save-player-panel" data-val="${id}">${I(icon)}<span>${name}</span>${state.settings.defaultPlayerPanel===id?I('check','panel-selected'):''}</button>`).join('')}<button class="btn secondary block" data-act="close-lines">取消</button></div></div>`;syncMarkup($('popups'),modal);return;
 case 'save-player-panel':modal=null;syncMarkup($('popups'),'');post('setting',{key:'defaultPlayerPanel',value:d.val});return;
 case 'sleep-timer':post('sleep-timer',{minutes:Number(d.val)});return;
 case 'show-lines':showLines();return;
 case 'close-lines':modal=null;syncMarkup($('popups'),'');return;
 case 'select-line':modal=null;syncMarkup($('popups'),'');post('select-line',{id:d.id,group:state.playerGroup});return;
 case 'player-panel':setPlayerPanel(d.val);return;
 case 'back':back();return;case 'exit-fullscreen':rememberDraft();if(state.history[state.history.length-1]==='player')state.history.pop();state.route='player';modal=null;render();changedRoute();return;
 case 'go-home':go('home');return;case 'go-source-add':go('source-add');return;
 case 'set-group':state.group=d.val;scrolls[scrollKey()]=0;render();post('browse-group',{group:d.val});return;
 case 'player-group':rememberDraft();state.playerGroup=d.val;scrolls['panel:player-stations']=0;render();post('player-group',{group:d.val});return;
 case 'hot-search':state.search=d.val;go('search');return;
 case 'clear-search':state.search='';delete drafts[formKey()];render();return;
 case 'epg-day':pendingProgrammeLocation=false;state.epgDate=d.val;scrolls['panel:player-programmes']=0;render();return;
 case 'source-detail':state.editSource=d.id;go('source-detail');return;
 case 'edit-epg':state.epgEditId=state.epgSources[Number(d.index)]?.id;go('epg-edit');return;
 case 'open-sources':go('sources');return;case 'open-epg-sources':go('epg-sources');return;
 case 'open-about':go('about');return;case 'open-appearance':go('appearance');return;
 case 'open-permissions':case 'go-permissions':go('permissions');return;
 case 'open-audio':go('audio');return;
 case 'fullscreen-modes':fullscreenModes();return;
 case 'fullscreen-audio':modal=null;go('audio');return;
 case 'fullscreen-pip':modal=null;syncMarkup($('popups'),'');post('enter-pip');return;
 case 'show-pip':if(state.route==='pip-info')post('enter-pip');else {post('enter-pip');}return;
 case 'toggle':post('setting',{key:d.key,value:!state.settings[d.key]});return;
 case 'set-theme':post('theme',{mode:d.val});return;
 case 'edit-favorites':{const c=chById(state.favorites.includes(state.current)?state.current:state.favorites[0]);if(c)startChannel(c.id);else notify('暂无收藏频道');return;}
 case 'cancel-modal':case 'dismiss-overlay':modal=null;post('dismiss-overlay');render(true);return;
 case 'accept-overlay':modal=null;post('accept-overlay');render(true);return;
 case 'priority-epg':case 'delete-epg':post(act,{id:state.epgSources[Number(d.index)]?.id});return;
 case 'save-epg':post(act,{id:state.epgEditId,fields:values()});return;
 case 'save-source':post(act,{id:state.editSource,fields:values()});return;
 case 'save-channel':post(act,{id:state.route==='channel-edit'?state.editChannel:null,fields:values()});return;
 case 'delete-channel':post(act,{id:state.editChannel});return;
 case 'parse-online':case 'parse-file':post(act,{fields:values()});return;
 case 'add-group':post(act,{name:$('new-group-name')?.value});return;
 case 'delete-group':post(act,{name:d.val});return;
 case 'refresh-epg':post(act);return;
 case 'epg-refresh':post('refresh-epg');return;
 case 'refresh-builtin-epg':post('refresh-epg',{id:state.epgSources.find(s=>s.type==='内置')?.id});return;
 default:post(act,{...d,draft:savedState()});
 }
}
let volumePending=false,requestedVolume=0,volumeDragging=null,brightnessPending=false,requestedBrightness=50;
function requestBrightness(value){requestedBrightness=value;if(brightnessPending)return;brightnessPending=true;requestAnimationFrame(()=>{brightnessPending=false;post('brightness',{value:requestedBrightness});});}
function requestVolume(value){requestedVolume=value;if(volumePending)return;volumePending=true;requestAnimationFrame(()=>{volumePending=false;post('volume',{value:requestedVolume});});}
let holdTimer=null,held=false,holdOrigin=null;
document.addEventListener('pointerdown',event=>{
 if(['remote-volume','full-volume','remote-brightness'].includes(event.target.id))volumeDragging=event.target.id;
 const card=event.target.closest('.channel-card[data-id]');if(!card)return;
 held=false;holdOrigin={x:event.clientX,y:event.clientY};
 holdTimer=setTimeout(()=>{const c=chById(card.dataset.id);if(!c)return;held=true;
  if(c.custom){state.editChannel=c.id;go('channel-edit');}else startChannel(c.id);
 },550);
});
document.addEventListener('pointermove',event=>{if(holdOrigin&&Math.hypot(event.clientX-holdOrigin.x,event.clientY-holdOrigin.y)>8)clearTimeout(holdTimer);},{passive:true});
document.addEventListener('pointerup',()=>{clearTimeout(holdTimer);holdOrigin=null;if(volumeDragging){volumeDragging=null;updatePlaybackControls();}});
document.addEventListener('pointercancel',()=>{clearTimeout(holdTimer);holdOrigin=null;held=false;if(volumeDragging){volumeDragging=null;updatePlaybackControls();}});
document.addEventListener('contextmenu',event=>{if(event.target.closest('.channel-card,.station-row,.video-shell,.full-viewport'))event.preventDefault();});
document.addEventListener('click',event=>{
 if(suppressPullClick){event.preventDefault();return;}
 if(held){held=false;event.preventDefault();return;}
 const button=event.target.closest('[data-go],[data-act]');if(button){
  if(button.hasAttribute('data-go')){
   const route=button.dataset.go;
   if(route==='epg-edit')state.epgEditId=null;
   if(route==='channel-edit'&&!state.customChannels.some(c=>c.id===state.editChannel)){state.editChannel=state.customChannels.find(c=>c.sourceId===state.editSource)?.id;if(!state.editChannel){notify('请先添加自建频道');return;}}
   go(route);
  }else action(button.dataset.act,button.dataset);
 }else if(event.target.closest('.video-shell,.full-viewport'))post('toggle-controls');
});
document.addEventListener('input',event=>{
 const e=event.target;
 if(e.id==='search-input'){state.search=e.value;$('search-results').innerHTML=searchResults();requestVisible();}
 if(e.id==='epg-search')$('match-list').innerHTML=matchList(e.value);
 if(e.id==='remote-brightness'){document.querySelector('.remote-brightness')?.style.setProperty('--brightness',`${e.value}%`);requestBrightness(Number(e.value));return;}
 if(['volume-range','full-volume','remote-volume'].includes(e.id)){if(e.id==='remote-volume')document.querySelector('.remote-volume')?.style.setProperty('--volume',`${e.value}%`);requestVolume(Number(e.value));return;}
 rememberDraft();post('draft',{draft:savedState()});
});
document.addEventListener('change',event=>{if(event.target.name==='match')post('match',{sourceId:event.target.dataset.source,stationId:event.target.value});});
let pullGesture=null,suppressPullClick=false;
function resetPull(){const indicator=$('pull-refresh');if(indicator){indicator.parentElement.style.removeProperty('--pull-distance');indicator.classList.remove('ready','dragging');}pullGesture=null;}
document.addEventListener('touchstart',event=>{
 const viewport=$('viewport');if(state.route!=='home'||state.screenshotRefreshing||event.touches.length!==1||viewport.scrollTop>0||!viewport.contains(event.target)||event.target.closest('.chips,input,textarea,button.top-tv'))return;
 const t=event.touches[0];pullGesture={x:t.clientX,y:t.clientY,distance:0};
},{passive:true});
document.addEventListener('touchmove',event=>{
 if(!pullGesture||event.touches.length!==1)return;
 const t=event.touches[0],dy=t.clientY-pullGesture.y,dx=Math.abs(t.clientX-pullGesture.x);
 if(dx>16&&dx>Math.abs(dy)){resetPull();return;}
 if(dy<=10||dy<dx*1.3)return;
 event.preventDefault();clearTimeout(holdTimer);held=false;suppressPullClick=true;
 pullGesture.distance=Math.min(75,dy*.45);const indicator=$('pull-refresh');
 if(indicator){indicator.classList.add('dragging');indicator.parentElement.style.setProperty('--pull-distance',`${pullGesture.distance}px`);indicator.classList.toggle('ready',pullGesture.distance>=52);indicator.querySelector('span').textContent=pullGesture.distance>=52?'松开刷新':'下拉刷新';}
},{passive:false});
document.addEventListener('touchend',()=>{const refresh=pullGesture?.distance>=52;resetPull();if(refresh&&state.route==='home'&&!state.screenshotRefreshing)post('refresh-previews');setTimeout(()=>suppressPullClick=false,400);},{passive:true});
document.addEventListener('touchcancel',()=>{resetPull();setTimeout(()=>suppressPullClick=false,400);},{passive:true});
let deckScrollTimer=null;
document.addEventListener('scroll',event=>{
 const e=event.target;
 if(e.id==='player-deck'){clearTimeout(deckScrollTimer);deckScrollTimer=setTimeout(()=>{if(e.isConnected)setPlayerPanel(['channels','programmes','remote'][Math.round(e.scrollLeft/e.clientWidth)],true);},120);return;}
 if(e.matches?.('.chips,.player-group,.day-strip'))return;
 if(e.id==='viewport'){if(state.route==='player')requestGeometry();requestVisible();}
},{capture:true,passive:true});
window.addEventListener('resize',()=>{lastFrameKey=null;lastMaskKey=null;requestGeometry();requestVisible();});
setInterval(()=>{
 if(state.route==='epg')render(true);
 else if(state.route==='player'&&state.playerPanel==='programmes'){const panel=$('player-programmes');if(panel){const top=panel.scrollTop;syncMarkup(panel,epg(true));panel.scrollTop=top;}}
},30_000);
document.addEventListener('pointerdown',event=>{dimPointers.add(event.pointerId);updateViewingDimmer(true);},{capture:true,passive:true});
for(const name of ['pointerup','pointercancel'])document.addEventListener(name,event=>{dimPointers.delete(event.pointerId);updateViewingDimmer(true);},{capture:true,passive:true});
for(const name of ['pointermove','click','input','keydown','wheel','scroll'])document.addEventListener(name,()=>updateViewingDimmer(true),{capture:true,passive:true});
document.addEventListener('visibilitychange',()=>{dimPointers.clear();updateViewingDimmer(true);});
window.addEventListener('blur',()=>{dimPointers.clear();clearTimeout(dimTimer);dimTimer=null;$('viewing-dimmer')?.classList.remove('dimmed');dimEligible=false;});
window.addEventListener('focus',()=>updateViewingDimmer(true));
render();post('ready');
