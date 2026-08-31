package com.mobileclaw.skill

/**
 * Bundled catalog of one-tap installable regional and general-purpose skills.
 * HTTP entries use free public APIs accessible without a VPN.
 */
object SkillMarket {

    data class MarketEntry(
        val emoji: String,
        val category: String,
        val def: SkillDefinition,
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://api.vvhan.com/",
    )

    val catalog: List<MarketEntry> = listOf(

        // ── Trending ─────────────────────────────────────────────────────────

        MarketEntry("🔥", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "weibo_hot",
                name = "Weibo Hot Topics",
                description = "Fetches the current Weibo trending hot search list in real time.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/wbHot",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("📺", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "bilibili_hot",
                name = "Bilibili Hot Videos",
                description = "Fetches currently trending videos on Bilibili.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.MEDIA),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/bili",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("💡", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "zhihu_hot",
                name = "Zhihu Hot Questions",
                description = "Fetches the current Zhihu hot questions list.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/zhihuHot",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("🔍", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "baidu_hot",
                name = "Baidu Hot Search",
                description = "Fetches the current Baidu hot search trending list.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/baiduRD",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("📱", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "douyin_hot",
                name = "Douyin Hot Topics",
                description = "Fetches current trending topics on Douyin (TikTok China).",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.MEDIA),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/douyinHot",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("📰", "Trending", SkillDefinition(
            meta = SkillMeta(
                id = "toutiao_hot",
                name = "Toutiao Hot News",
                description = "Fetches trending news headlines from Toutiao (Today's Headlines).",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/hotlist/toutiao",
                headers = browserHeaders,
            ),
        )),

        // ── Utilities ─────────────────────────────────────────────────────────

        MarketEntry("🌤", "Utilities", SkillDefinition(
            meta = SkillMeta(
                id = "weather_cn",
                name = "Weather Query",
                description = "Gets current weather for a Chinese city. Provide city name in Chinese.",
                parameters = listOf(
                    SkillParam("city", "string", "Chinese city name written in Chinese characters"),
                ),
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Utilities"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/weather?city={city}&type=week",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("🌐", "Utilities", SkillDefinition(
            meta = SkillMeta(
                id = "ip_lookup",
                name = "IP Address Lookup",
                description = "Looks up geographic location and ISP for an IP address.",
                parameters = listOf(
                    SkillParam("ip", "string", "IP address to look up (leave empty for current IP)", required = false),
                ),
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Web"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.SYSTEM),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/getIpInfo?ip={ip}",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("💱", "Utilities", SkillDefinition(
            meta = SkillMeta(
                id = "exchange_rate",
                name = "Exchange Rate",
                description = "Gets current CNY exchange rates for common currencies.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Utilities"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/exchange",
                headers = browserHeaders,
            ),
        )),

        MarketEntry("🗓", "Utilities", SkillDefinition(
            meta = SkillMeta(
                id = "cn_calendar",
                name = "Chinese Calendar",
                description = "Gets today's Chinese calendar info including lunar date, solar terms, and lucky directions.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Utilities"),
                categories = listOf(SkillToolCategory.WEB),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/almanac",
                headers = browserHeaders,
            ),
        )),

        // ── Creative ─────────────────────────────────────────────────────────

        MarketEntry("✍️", "Creative", SkillDefinition(
            meta = SkillMeta(
                id = "hitokoto",
                name = "Random Quote (Hitokoto)",
                description = "Returns a random literary quote or inspiring sentence from the Hitokoto library.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Creative"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.CHAT),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://v1.hitokoto.cn/?encode=json&min_length=5&max_length=80",
                textResponsePath = "hitokoto",
            ),
        )),

        MarketEntry("📜", "Creative", SkillDefinition(
            meta = SkillMeta(
                id = "poem_cn",
                name = "Random Ancient Poem",
                description = "Returns a random line from classic Chinese poetry.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Creative"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.CHAT),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://v1.jinrishici.com/all.json",
                textResponsePath = "content",
            ),
        )),

        MarketEntry("😄", "Creative", SkillDefinition(
            meta = SkillMeta(
                id = "joke_cn",
                name = "Random Joke",
                description = "Returns a random funny joke in Chinese.",
                injectionLevel = 2,
                type = SkillType.HTTP,
                isBuiltin = false,
                minApiLevel = 21,
                tags = listOf("Creative"),
                categories = listOf(SkillToolCategory.WEB, SkillToolCategory.CHAT),
            ),
            httpConfig = HttpSkillConfig(
                url = "https://api.vvhan.com/api/joke",
                headers = browserHeaders,
                textResponsePath = "data.content",
            ),
        )),

    )
}
