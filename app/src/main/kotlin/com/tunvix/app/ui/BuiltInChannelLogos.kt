package com.tunvix.app.ui

import com.tunvix.contract.Channel
import java.text.Normalizer
import java.util.Locale

/** Conservative station aliases: unknown/platform channels keep their source artwork. */
internal object BuiltInChannelLogos {
    private val canonicalNames = listOf(
        "CCTV1",
        "CCTV10",
        "CCTV11",
        "CCTV12",
        "CCTV13",
        "CCTV14",
        "CCTV15",
        "CCTV16",
        "CCTV17",
        "CCTV2",
        "CCTV3",
        "CCTV4",
        "CCTV4K",
        "CCTV4欧洲",
        "CCTV4美洲",
        "CCTV5",
        "CCTV5+",
        "CCTV6",
        "CCTV7",
        "CCTV8",
        "CCTV8K",
        "CCTV9",
        "CCTV第一剧场",
        "CGTN",
        "CGTN俄语",
        "CGTN法语",
        "CGTN纪录",
        "CGTN西语",
        "CGTN阿语",
        "三沙卫视",
        "上海外语",
        "上海新闻综合",
        "上海纪实人文",
        "上海都市",
        "世界地理",
        "东南卫视",
        "东方卫视",
        "东方影视",
        "东方财经",
        "中国教育1台",
        "中国教育2台",
        "中国教育3台",
        "中国教育4台",
        "乐游",
        "云南卫视",
        "五星体育",
        "优漫卡通",
        "兵器科技",
        "兵团卫视",
        "内蒙古卫视",
        "农林卫视",
        "凤凰卫视中文台",
        "凤凰卫视资讯台",
        "凤凰卫视香港台",
        "动漫秀场",
        "北京体育休闲",
        "北京卫视",
        "北京大健康",
        "北京影视",
        "北京文艺",
        "北京新闻",
        "北京生活",
        "北京纪实",
        "北京财经",
        "卡酷少儿",
        "卫生健康",
        "厦门卫视",
        "吉林卫视",
        "哈哈炫动",
        "嘉佳卡通",
        "四川卫视",
        "大湾区卫视",
        "天津卫视",
        "央视台球",
        "女性时尚",
        "宁夏卫视",
        "安多卫视",
        "安徽卫视",
        "山东卫视",
        "山东教育卫视",
        "山西卫视",
        "广东4K",
        "广东体育",
        "广东公共",
        "广东卫视",
        "广东少儿",
        "广东影视",
        "广东新闻",
        "广东民生",
        "广东珠江",
        "广东经济科教",
        "广东综艺",
        "广西卫视",
        "康巴卫视",
        "延边卫视",
        "怀旧剧场",
        "文化精品",
        "新疆卫视",
        "欢笑剧场",
        "江苏休闲体育",
        "江苏公共新闻",
        "江苏卫视",
        "江苏国际",
        "江苏城市",
        "江苏影视",
        "江苏教育电视台",
        "江苏新闻",
        "江苏综艺",
        "江西卫视",
        "河北卫视",
        "河南卫视",
        "法治天地",
        "浙江之江纪录",
        "浙江公共新闻",
        "浙江卫视",
        "浙江国际",
        "浙江少儿",
        "浙江教科影视",
        "浙江数码时代",
        "浙江民生休闲",
        "浙江经济生活",
        "浙江钱江都市",
        "海南卫视",
        "海峡卫视",
        "深圳卫视",
        "游戏风云",
        "湖北公共·新闻",
        "湖北卫视",
        "湖北影视",
        "湖北教育",
        "湖北生活",
        "湖北经济",
        "湖北综合",
        "湖北陇上",
        "湖南卫视",
        "湖南国际",
        "湖南娱乐",
        "湖南爱晚",
        "湖南电影",
        "湖南电视剧",
        "湖南经视",
        "湖南都市",
        "甘肃卫视",
        "生活时尚",
        "电视指南",
        "第一财经",
        "西藏卫视",
        "贵州公共",
        "贵州卫视",
        "贵州大众生活",
        "贵州影视文艺",
        "贵州生态乡村",
        "贵州科教健康",
        "贵州经济",
        "贵阳新闻综合",
        "贵阳法制",
        "贵阳经济生活",
        "辽宁卫视",
        "都市剧场",
        "重庆卫视",
        "金色学堂",
        "金鹰卡通",
        "陕西农林卫视",
        "陕西卫视",
        "青海卫视",
        "风云剧场",
        "风云足球",
        "风云音乐",
        "香港卫视",
        "高尔夫网球",
        "黑龙江卫视"
    )
    private val aliases: Map<String, String> = buildMap {
        fun add(station: String, vararg names: String) {
            require(station in canonicalNames)
            names.forEach { name -> put(normalize(name),station) }
        }
        canonicalNames.forEach { station ->
            add(station,station,station + "频道")
            if(!station.startsWith("CCTV") && !station.startsWith("CGTN") && !station.startsWith("中国教育")) {
                (1..9).forEach { feed -> add(station,station+"0$feed") }
            }
        }
        val cctvTitles=listOf("综合","财经","综艺","中文国际","体育","电影","国防军事","电视剧","纪录","科教","戏曲","社会与法","新闻","少儿","音乐","奥林匹克","农业农村")
        val chineseNumbers=listOf("一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七")
        cctvTitles.forEachIndexed { index,title ->
            val number=index+1
            val station="CCTV$number"
            add(station,station+title,station+title+"频道","央视${number}套","中央${number}套",
                "央视${chineseNumbers[index]}套","中央${chineseNumbers[index]}套","中央电视台${title}频道")
        }
        add("CCTV5+","CCTV5PLUS","CCTV5P","CCTV5+体育赛事","CCTV5+体育赛事频道","央视体育赛事")
        add("CCTV9","CCTV9纪录片","CCTV9记录","CCTV9记录频道")
        add("CCTV4","CCTV4中文国际亚洲","CCTV4亚洲","CCTV4亚洲版")
        add("CCTV4K","CCTV4K超高清","央视4K","央视超高清")
        add("CCTV8K","CCTV8K超高清","央视8K")
        add("CCTV4欧洲","CCTV4欧洲版","CCTV4EUROPE")
        add("CCTV4美洲","CCTV4美洲版","CCTV4AMERICA")
        listOf("风云剧场","风云音乐","风云足球","兵器科技","电视指南","怀旧剧场","女性时尚","世界地理","卫生健康","央视台球","高尔夫网球","文化精品").forEach { station ->
            add(station,"CCTV$station")
        }
        add("CCTV第一剧场","第一剧场")
        add("文化精品","央视文化精品","CCTV央视文化精品")
        add("CGTN","CGTN英语","CGTN英文","中国国际电视台","中国国际电视台英语频道")
        add("CGTN纪录","CGTN纪录片","CGTNDOCUMENTARY")
        add("CGTN法语","CGTNFRENCH")
        add("CGTN西语","CGTN西班牙语","CGTNSPANISH")
        add("CGTN阿语","CGTN阿拉伯语","CGTNARABIC")
        add("CGTN俄语","CGTNRUSSIAN")
        (1..4).forEach { number -> add("中国教育${number}台","CETV$number","中国教育${number}","中国教育电视台${number}频道") }
        val guizhou=listOf("贵州卫视","贵州公共","贵州影视文艺","贵州大众生活","贵州生态乡村","贵州科教健康","贵州经济")
        guizhou.forEachIndexed { index,station ->
            val number=index+1;val numeral=chineseNumbers[index]
            add(station,"贵州$number","贵州${number}频道","贵州${number}台","贵州${number}套",
                "贵州${numeral}频道","贵州${numeral}台","贵州${numeral}套","贵州电视台${number}频道","贵州电视台${numeral}频道",
                "贵州电视台${number}套","贵州电视台${numeral}套","GTV$number","GZTV$number")
            val title=station.removePrefix("贵州")
            add(station,"贵州${number}频道$title","贵州${number}$title","贵州${number}${title}频道","贵州电视台${title}频道")
        }
        add("贵州卫视","贵州新闻综合","贵州新闻综合频道")
        add("贵州影视文艺","贵州文艺","贵州影视")
        add("贵州生态乡村","贵州生态·乡村","贵州旅游","贵州体育旅游","贵州体育·旅游","贵州法制民生")
        add("浙江教科影视","浙江教育科技","浙江教育科技频道")
        add("东方卫视","上海东方卫视")
        add("海南卫视","旅游卫视","海南旅游卫视")
        add("凤凰卫视中文台","凤凰中文","凤凰中文台")
        add("凤凰卫视资讯台","凤凰资讯","凤凰资讯台")
        add("凤凰卫视香港台","凤凰香港","凤凰香港台")
        add("卡酷少儿","北京卡酷","北京卡酷少儿","卡酷动画")
        add("哈哈炫动","炫动卡通","哈哈炫动卫视")
        add("金鹰卡通","金鹰卡通卫视")
        add("嘉佳卡通","广东嘉佳卡通","嘉佳卡通卫视")
        add("优漫卡通","江苏优漫卡通","优漫卡通卫视")
        // Generic names such as 新闻综合 are intentionally not global aliases. Source tvg-id
        // may disambiguate them (for example 上海新闻综合), without guessing by substring.
    }

    fun uriFor(channel: Channel): String? {
        val station=match(channel.name) ?: channel.externalId?.let(::match) ?: return null
        return "asset:///channel_logos/$station.png"
    }

    private fun match(value: String): String? {
        var name=normalize(value)
        aliases[name]?.let { return it }
        // Remove only whole trailing quality annotations, then require an exact known alias.
        name=name.replace(Regex("(?:高清|标清|超清|超高清|HD|FHD|UHD|SD|1080P|720P|4K|8K)+$"),"")
        aliases[name]?.let { return it }
        // Some playlists number redundant satellite feeds 东方卫视01/02. Never strip digits
        // from CCTV/CETV identifiers, where they can identify a different channel.
        val feed=Regex("^(.+卫视)(?:0[1-9]|线路[0-9]+|源[0-9]+)$").matchEntire(name)
        return feed?.groupValues?.get(1)?.let(aliases::get)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value,Normalizer.Form.NFKC)
        .uppercase(Locale.ROOT).replace(Regex("[\\s_\\-·•.()（）\\[\\]【】]"),"")
}
