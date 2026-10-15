package com.tunvix.app
import android.content.Context
import com.tunvix.app.services.*
import com.tunvix.contract.*
object ServiceFactory {
    fun create(context: Context): AppServices {
        val names = listOf("CCTV-1 综合", "湖南卫视", "浙江卫视", "东方卫视", "江苏卫视", "北京卫视", "凤凰中文", "广东卫视")
        val channels = names.mapIndexed { i,n -> Channel("c${i+1}", "online", n, "https://example.com/demo/$i", "央视频道", thumbnailUri = "android.resource://${context.packageName}/drawable/preview_c${i+1}") } + listOf(
            Channel("c9", "online", "CCTV-5 体育", "https://example.com/demo/sports", "体育", thumbnailUri = "android.resource://${context.packageName}/drawable/preview_c4"),
            Channel("c10", "sports", "赛事直播", "https://example.com/demo/sports2", "体育"),
            Channel("c11", "online", "电影频道", "https://example.com/demo/movie", "电影", thumbnailUri = "android.resource://${context.packageName}/drawable/preview_c3"),
            Channel("c12", "online", "少儿动画", "https://example.com/demo/kids", "少儿"),
            Channel("c13", "online", "香港资讯", "https://example.com/demo/hk", "港澳台", thumbnailUri = "android.resource://${context.packageName}/drawable/preview_c7"),
            Channel("c14", "online", "超清演示频道", "https://example.com/demo/4k", "4K专区"),
            Channel("c15", "custom", "我的测试频道", "https://example.com/demo/custom", "未分组"))
        val state = CatalogSnapshot(listOf(LiveSource("online","我的订阅源",SourceKind.SUBSCRIPTION,"https://example.com/playlist.m3u"),LiveSource("sports","体育精选",SourceKind.LOCAL_FILE),LiveSource("custom","自建频道",SourceKind.CUSTOM)), channels, "online", setOf("c1","c4","c7"),listOf("c2","c1","c5"),cacheBytes=40475033)
        return AppServices(MemoryCatalog(state), object : SourceImporter {
            override suspend fun inspect(request: ImportRequest): Outcome<ImportReport> = Outcome.Success(ImportReport(List(128) { i -> ChannelDraft("演示频道 ${i+1}","https://example.com/demo/$i", "演示分组 ${i%12+1}") },3))
        }, PreviewPlayback(context), true)
    }
}
